"""职业规划专家（行动方案）核心：AI 助手第 7 个工具 create_career_plan 的实现。

设计见 fc2026/职业规划专家方案.md：与 analyze/polish 同款——同步、一次性、结构化 I/O，
由主 LLM 编排（无服务端状态机）：
  取数（简历 + 长期记忆 current/timeline-view + 可选目标岗位 JD + 本会话最近几条原文）
  → 单次 LLM 生成方案 JSON（一遍过，无 REVIEW/REFINE 评审修正，红线写进生成 prompt）
  → 渲染成整份 content markdown
  → 经网关 POST /users/me/plans 落库（career-service，user_career_plans 表，透传 JWT）
  → 返回 {planId, title, goal, summary, markdown}

本模块不持有任何状态；pg 连接池由调用方（tools.py，取自 request.app.state）传入，
MySQL chat_history 直连自身 async_session（只读本会话最近几条原文）。
"""
import asyncio
import json
import re
import time

import httpx
from sqlalchemy import select

from app.config import settings
from app.database import async_session
from app.models import ChatMessage
from app.services import memory, resume_polish
from app.services.llm import get_json_llm

# 落库接口（career-service）：CareerPlanController，经网关 career-user 路由（order 3）已覆盖
_PLANS_URL = "/users/me/plans"

# 单次生成 LLM 硬超时。get_json_llm 本身 timeout=150，这里再加 wait_for 兜底（对齐 memory/resume_polish 做法）
_LLM_TIMEOUT = 150

# 目标岗位 id 可能在 target 里以 jobId:xxx / jobID：xxx / job_id:xxx 形式出现，从里抽出纯 id
_JOB_ID_RE = re.compile(r"(?:jobId|jobID|job_id)\s*[:：]\s*([A-Za-z0-9_\-]+)", re.I)

PLAN_SYSTEM = """你是一名资深「大学生求职 / 职业规划顾问」，服务于求职助手平台。请基于用户当前真实情况，产出一份分阶段、带目标与检查点的行动方案（action plan）。

【输入素材】
- 【用户简历】：结果态事实底座（掌握了什么、做过什么），规划的事实来源。
- 【长期记忆 · 当前状态】：用户最近表达的学习/项目/目标/自评（每类一条最新），即「现在坐标」。
- 【长期记忆 · 近期轨迹】：同一维度随时间的变化（可能含已被更新的旧进展，如更早有「刚开始学X」、后来「已学完X」），用于判断进步速度、卡点演变、目标是否漂移。
- 【目标岗位 JD】（可选）：用户想投的方向，用于反推差距与动作优先级。
- 【本会话最近消息】（可选、低优先）：用户刚说完可能还没沉淀进记忆，仅作即时参考，不得当权威事实。
- 【用户本次诉求】（可选）：用户明确给的方向或约束。

【生成要求】
1. 只基于上述素材呈现的事实规划；动作 / 步骤可以合理拆解推断，但**事实不可虚构**——不得编造简历里没有的经历、没表达过的技能掌握程度、不存在的课程/证书/项目。
2. 「正在学 / 想学 / 了解」≠「已经会」：这类进展只用于分期与排序，不得当成已掌握能力写进差距结论。
3. 不给精确承诺（不做「X 周内一定拿到 offer / 保证上岸」这类保证），给的是「做什么、按什么顺序、怎么自检」。
4. 阶段要有时间感（dueLabel 如「第 1-2 周 / 8 月」），动作可执行、可自检，不要空话。
5. diagnosis 指出与目标之间的关键差距；gapList 每条对应一个可行动的差距，宁少勿滥。
6. 若素材里连「用户现状」都几乎没有（无简历、无记忆），output 里给出 notice 说明缺什么，phases 给空数组。

只输出 JSON，结构如下：
{
  "title": "方案标题（≤20 字，给列表页展示）",
  "goal": "一句话核心目标",
  "diagnosis": "现状诊断（基于事实，指出关键卡点/差距，150 字内）",
  "gapList": ["差距1", "差距2"],
  "phases": [
    {"name": "阶段名", "objective": "本阶段目标", "actions": ["动作1", "动作2"], "dueLabel": "时间窗，如：第 1-2 周"}
  ],
  "risks": ["可能的风险 / 注意点"],
  "checkpoint": "复盘检查点：什么时候、按什么标准自检，是否需要调整",
  "summary": "一句可读摘要（供前端 trace 展示）",
  "notice": "给用户的说明（可选，如缺简历/缺记忆时提醒补什么）"
}"""


# ---------- LLM 调用 ----------

def _extract_json(text: str) -> dict:
    """解析模型输出的 JSON，容忍 ```json ... ``` 围栏"""
    text = (text or "").strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return json.loads(text)


async def _call_plan_llm(user_content: str) -> dict:
    """一次生成 LLM 调用 → 方案 JSON。解析失败抛异常由上层兜底。"""
    messages = [
        {"role": "system", "content": PLAN_SYSTEM},
        {"role": "user", "content": user_content},
    ]
    t0 = time.perf_counter()
    try:
        resp = await asyncio.wait_for(get_json_llm().ainvoke(messages), timeout=_LLM_TIMEOUT)
    except asyncio.TimeoutError:
        raise TimeoutError(f"规划 LLM 调用超时（>{_LLM_TIMEOUT}s），输入len={len(user_content)}")
    content = resp.content
    if isinstance(content, list):  # 防御：个别 provider 返回 block 列表
        content = "".join(b.get("text", "") for b in content if isinstance(b, dict))
    result = _extract_json(content or "")
    print(
        f"[plan] LLM 生成完成: {time.perf_counter() - t0:.2f}s, 输入len={len(user_content)}, 输出len={len(content or '')}",
        flush=True,
    )
    return result


# ---------- 取数 ----------

async def _load_resume(profile_id, token: str) -> str:
    """按 profile_id 拉取简历 markdown 全文；未指定则取最新一份。没有简历返回空串（由上层降级提示）。"""
    try:
        if profile_id and str(profile_id).strip():
            profile = await resume_polish.fetch_profile(str(profile_id).strip().split(":", 1)[-1].strip(), token)
            return profile.get("content") if isinstance(profile, dict) else str(profile)
        latest = await resume_polish.fetch_latest_profile(token)
        return latest.get("content") or ""
    except ValueError as e:
        print(f"[plan] 拉简历为空(降级): {e}", flush=True)
        return ""


def _format_job(job: dict) -> str:
    """把岗位字典整理成易读的 JD 文本（只挑已知字段，缺字段时回退整段 JSON），对齐 resume_polish 口径"""
    keys = [
        "jobName", "jobTitle", "companyName", "company", "city",
        "salaryRange", "salary", "jobDescription", "abilityRequirements",
        "skills", "coreSkills",
    ]
    lines = []
    for k in keys:
        v = job.get(k)
        if v:
            lines.append(f"## {k}\n{v}")
    if not lines:
        lines.append(json.dumps(job, ensure_ascii=False))
    return "\n\n".join(lines)


def _extract_job_id(target) -> str | None:
    """从 target 抽出 jobId:xxx 形式的岗位 id（形如 jobId:JOB2024...），没有返回 None"""
    m = _JOB_ID_RE.search(target or "")
    return m.group(1) if m else None


def _clean_target(target) -> str:
    """去掉 target 里的 jobId:xxx 前缀 token，只留可读的目标描述文本"""
    return _JOB_ID_RE.sub("", target or "").strip(" ,，:：")


async def _load_recent_messages(session_id: str, limit: int) -> list[dict]:
    """读本会话最近 limit 条 user/assistant 消息（MySQL chat_history），按时间升序返回（低优先即时速览）"""
    if not session_id or limit <= 0:
        return []
    async with async_session() as db:
        result = await db.execute(
            select(ChatMessage)
            .where(
                ChatMessage.session_id == session_id,
                ChatMessage.role.in_(("user", "assistant")),
            )
            .order_by(ChatMessage.created_at.desc())
            .limit(limit)
        )
        rows = list(result.scalars().all())
    rows.reverse()  # 倒序取回 → 翻回升序，保证最新在前、输出仍按时间线
    return [{"role": m.role, "content": m.content or ""} for m in rows]


async def _load_memory_rows(pool, user_id: int) -> tuple[list[dict], list[dict]]:
    """并行取 current-view + timeline-view；pg 池不可用 / 出错都降级为空列表，不影响主链路"""
    if pool is None:
        return [], []
    cur_rows: list[dict] = []
    tl_rows: list[dict] = []
    per_category = max(1, settings.plan_timeline_rows // max(1, len(memory.CORE_CATEGORIES)))
    try:
        cur_rows, tl_rows = await asyncio.gather(
            memory.load_current_view(pool, user_id),
            memory.load_timeline_view(pool, user_id, per_category=per_category),
        )
    except Exception as e:
        print(f"[plan] 长期记忆读取失败(忽略): {e}", flush=True)
    return cur_rows, tl_rows


# ---------- 生成 → 渲染 → 落库 ----------

def _build_prompt(resume: str, cur_rows, tl_rows, job, recent, target_text, focus) -> str:
    """把取到的素材按固定分区拼给生成 LLM，分区标签即事实源边界，防止素材互相污染"""
    parts = []
    if job:
        parts.append(f"【目标岗位 JD】\n{_format_job(job)}")
    parts.append(f"【用户简历】\n{resume or '（暂无简历，缺少结果态事实底座）'}")
    parts.append(f"【长期记忆 · 当前状态】\n{memory.format_core_rows(cur_rows) if cur_rows else '（暂无）'}")
    tl_block = memory.format_core_rows(tl_rows) if tl_rows else ""
    if tl_block:
        parts.append(f"【长期记忆 · 近期轨迹（含可能已被更新的旧进展，看进步速度/卡点演变）】\n{tl_block}")
    if recent:
        lines = [f"{'用户' if m['role'] == 'user' else '助手'}: {m['content']}" for m in recent]
        parts.append("【本会话最近消息（低优先，可能未沉淀进记忆，仅作即时参考）】\n" + "\n\n".join(lines))
    intent = []
    if target_text:
        intent.append(f"目标 / 方向：{target_text}")
    if focus:
        intent.append(f"侧重 / 约束：{focus}")
    if intent:
        parts.append(f"【用户本次诉求】\n{'；'.join(intent)}")
    parts.append("请按系统指令只输出方案 JSON。")
    return "\n\n".join(parts)


def _render_markdown(plan: dict) -> str:
    """把结构化方案 JSON 渲染成整份 content markdown（落库正文）。

    本期不做逐条勾选，动作用普通圆点而非 [ ] 复选框，避免前端误以为可勾选。
    """
    title = (str(plan.get("title") or "").strip()) or "职业规划行动方案"
    goal = str(plan.get("goal") or "").strip()
    diagnosis = str(plan.get("diagnosis") or "").strip()
    gap_list = plan.get("gapList") or []
    phases = plan.get("phases") or []
    risks = plan.get("risks") or []
    checkpoint = str(plan.get("checkpoint") or "").strip()
    notice = str(plan.get("notice") or "").strip()

    lines = [f"# {title}", ""]
    if goal:
        lines += ["## 🎯 目标", goal, ""]
    if diagnosis:
        lines += ["## 🔍 现状诊断", diagnosis, ""]
    if gap_list:
        lines += ["## 📋 当前主要差距", ""]
        for i, g in enumerate(gap_list, 1):
            lines.append(f"{i}. {g}")
        lines.append("")
    if phases:
        lines += ["## 🗺️ 行动方案（分阶段）", ""]
        for idx, ph in enumerate(phases, 1):
            name = str(ph.get("name") or f"阶段 {idx}").strip()
            due = str(ph.get("dueLabel") or "").strip()
            objective = str(ph.get("objective") or "").strip()
            actions = ph.get("actions") or []
            head = f"### 阶段 {idx}：{name}"
            if due:
                head += f"（{due}）"
            lines.append(head)
            if objective:
                lines += ["", f"**目标**：{objective}"]
            if actions:
                lines.append("")
                lines += [f"- {a}" for a in actions if str(a).strip()]
            lines.append("")
    if risks:
        lines += ["## ⚠️ 风险与注意", ""]
        for r in risks:
            if str(r).strip():
                lines.append(f"- {r}")
        lines.append("")
    if checkpoint:
        lines += ["## ✅ 复盘检查点", checkpoint, ""]
    if notice:
        lines += ["## 💬 说明", notice, ""]
    return "\n".join(lines).rstrip() + "\n"


async def _save_plan(title: str, content: str, session_id: str, token: str) -> int:
    """经网关 POST 落库（career-service user_career_plans），返回 planId。失败抛异常由上层兜底。"""
    url = f"{settings.career_service_base_url}{_PLANS_URL}"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    body = {"title": title, "content": content, "sessionId": session_id or None}
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.post(url, json=body, headers=headers)
        resp.raise_for_status()
        payload = resp.json()
    data = payload.get("data") or {}
    if data.get("planId") is None:
        raise ValueError(payload.get("msg") or f"保存方案失败（code={payload.get('code')}）")
    return int(data["planId"])


async def create_plan(
    user_id: int,
    token: str,
    session_id: str,
    target,
    profile_id,
    focus,
    pool,
) -> dict:
    """职业规划专家主流程（一次调用内完成取数 → 生成 → 落库 → 返回）。

    任何关键步骤失败抛异常，由 tools.py 包成 error JSON 返回主模型，不产生半成品 / 假成功。
    返回 {"planId", "title", "goal", "summary", "markdown", "notice"?""}
    """
    t0 = time.perf_counter()

    job_id = _extract_job_id(target)
    target_text = _clean_target(target)
    focus_text = str(focus or "").strip()

    # 取数：简历 / 记忆（current+timeline）并行；JD、本会话原文有才取
    resume_fut = asyncio.ensure_future(_load_resume(profile_id, token))
    memory_fut = asyncio.ensure_future(_load_memory_rows(pool, user_id))
    recent_fut = asyncio.ensure_future(
        _load_recent_messages(session_id, settings.plan_recent_messages)
    )
    job_fut = None
    if job_id:
        job_fut = asyncio.ensure_future(resume_polish.fetch_job_detail(job_id, token))

    resume_text = await resume_fut
    cur_rows, tl_rows = await memory_fut
    recent_msgs = await recent_fut
    job = await job_fut if job_fut else None
    print(
        f"[plan] 取数完成({time.perf_counter() - t0:.2f}s): 简历len={len(resume_text)}, "
        f"current={len(cur_rows)}, timeline={len(tl_rows)}, recent={len(recent_msgs)}, job={'有' if job else '无'}",
        flush=True,
    )

    # 无任何事实底座直接提示缺料，不硬造一份没有依据的方案
    if not resume_text and not cur_rows and not tl_rows:
        raise ValueError("还缺少简历画像与长期记忆，暂时无法生成靠谱的行动方案；请先完善简历或先聊一聊你的近况")

    # 单次生成（一遍过，无 REVIEW/REFINE）
    raw = await _call_plan_llm(
        _build_prompt(resume_text, cur_rows, tl_rows, job, recent_msgs, target_text, focus_text)
    )

    # 关键字段校验：缺 phases 说明生成质量不合格，直接失败不落库
    title = str(raw.get("title") or "").strip()[:200] or "职业规划行动方案"
    goal = str(raw.get("goal") or "").strip()
    phases = raw.get("phases")
    if not goal:
        raise ValueError("方案生成缺少 goal，请稍后重试")
    if not isinstance(phases, list) or not phases:
        raise ValueError("方案生成缺少分阶段动作，请稍后重试")

    content = _render_markdown(raw)
    summary = str(raw.get("summary") or "").strip() or f"已生成行动方案：{title}"

    plan_id = await _save_plan(title, content, session_id, token)
    print(
        f"[plan] 完成: {time.perf_counter() - t0:.2f}s, planId={plan_id}, title={title!r}, "
        f"content_len={len(content)}",
        flush=True,
    )
    return {
        "planId": plan_id,
        "title": title,
        "goal": goal,
        "summary": summary,
        "markdown": content,
        "notice": str(raw.get("notice") or "").strip() or None,
    }
