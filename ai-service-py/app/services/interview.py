"""模拟面试专家核心：独立会话型 agent 流程（面试官主 LLM）+ 报告生成工具逻辑。

设计见 fc2026/模拟面试专家方案.md：与主对话助理（chat.py）隔离——
- 独立的 session/message 存储：MySQL chat_history 库 interview_sessions / interview_messages 两张表；
- 面试官主 LLM = 独立上下文 + 专属工具集（tools.py 的 INTERVIEW_TOOLS）；
- 会话生命周期：active（进行中）→ ended（已出报告）；ended 后拒收新消息；
- 材料（简历/JD）开场按用户给的 id 拉取后快照进 session.material，每轮提问与最终报告都锚定该快照；
- 报告生成是**工具内部的一次独立结构化 LLM 子调用**（读 interview_messages 全量原文 + 材料快照），
  生成 {title, content} 后经网关 POST /users/me/interview-reports 落 careers.user_interview_reports，
  并把会话回写为 ended。

本模块与 DB 交互分两种形态：
- 路由用 FastAPI 注入的 AsyncSession（参数 db 传入，见下方 CRUD / 快照函数）；
- 报告工具（tools.py 的 submit_mock_interview_report 调用 generate_and_save_report）不在请求作用域内，
  自行用 async_session 开会话读原文 / 回写状态。
"""
import asyncio
import json
import time
from datetime import datetime

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import async_session
from app.models import InterviewMessage, InterviewSession, utcnow
from app.services.chat_service import gen_id
from app.services.llm import get_json_llm

# 落库接口（career-service InterviewReportController，网关 career-user 路由已覆盖 /users/me/**）
_REPORTS_URL = "/users/me/interview-reports"

# 报告生成 LLM 硬超时（要输出整篇 markdown，比单轮问答宽松；真正的兜底见 resume_polish 同款 wait_for 用法）
_REPORT_LLM_TIMEOUT = 180

# 面试类型 → 人设口径
_TYPE_LABELS = {
    "technical": "技术面试（围绕简历中的技术栈、项目细节与岗位相关技术深入提问）",
    "behavior": "行为面试（围绕项目经历、团队协作、自我认知与岗位软素质提问，可用 STAR 法追问）",
    "mixed": "综合面试（技术 + 行为混合，兼顾岗位技能与软素质）",
}


# ===================== 基础 CRUD（路由注入的 db） =====================

def _normalize_id(raw) -> str | None:
    """去掉 profileId:/jobId: 前缀，只留纯 id；空串返回 None"""
    s = str(raw or "").strip().split(":", 1)[-1].strip()
    return s or None


def type_label(interview_type: str | None) -> str:
    return _TYPE_LABELS.get(interview_type or "mixed", _TYPE_LABELS["mixed"])


async def create_session(
    db: AsyncSession,
    user_id: int,
    interview_type: str,
    job_id: str | None = None,
    profile_id: str | None = None,
) -> InterviewSession:
    """建一场面试（active）。jobId/profileId 由用户在开场时提供，可不全；材料在开场流里拉取快照"""
    session = InterviewSession(
        session_id=gen_id(),
        user_id=user_id,
        type=(interview_type or "mixed").lower(),
        job_id=_normalize_id(job_id),
        profile_id=_normalize_id(profile_id),
        status="active",
    )
    db.add(session)
    await db.commit()
    await db.refresh(session)
    return session


async def get_session(db: AsyncSession, session_id: str) -> InterviewSession | None:
    stmt = select(InterviewSession).where(InterviewSession.session_id == session_id)
    result = await db.execute(stmt)
    return result.scalar_one_or_none()


async def list_sessions(db: AsyncSession, user_id: int) -> list[InterviewSession]:
    stmt = (
        select(InterviewSession)
        .where(InterviewSession.user_id == user_id)
        .order_by(InterviewSession.created_at.desc())
    )
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def save_interview_message(
    db: AsyncSession, session_id: str, role: str, content: str
) -> InterviewMessage:
    """落一条面试问答原文，并刷新会话 updated_at"""
    msg = InterviewMessage(
        message_id=gen_id(),
        session_id=session_id,
        role=role,
        content=content,
        status="succeeded",
    )
    db.add(msg)

    session = await get_session(db, session_id)
    if session is not None:
        session.updated_at = utcnow()

    await db.commit()
    await db.refresh(msg)
    return msg


async def list_messages(db: AsyncSession, session_id: str) -> list[InterviewMessage]:
    stmt = (
        select(InterviewMessage)
        .where(InterviewMessage.session_id == session_id)
        .order_by(InterviewMessage.created_at.asc())
    )
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def mark_ended(db: AsyncSession, session_id: str, report_id: int) -> None:
    """回写会话 ended（幂等：重复调用/已 ended 只刷新时间不报错）"""
    session = await get_session(db, session_id)
    if session is None:
        return
    session.status = "ended"
    session.report_id = report_id
    session.ended_at = utcnow()
    session.updated_at = utcnow()
    await db.commit()


# ===================== 材料快照（开场 / 换基准时捕获工具结果） =====================

async def capture_snapshot(
    db: AsyncSession,
    session_id: str,
    tool_name: str,
    tool_args: dict | None,
    result: str,
) -> None:
    """面试官调用取数工具后，把返回的简历/JD 快照进 interview_sessions.material。

    - get_student_profile 返回的简历对象 → material["resume"]（markdown 原文）
    - query_job_detail 返回的岗位对象 → material["job"]（完整 dict，供 JD 文本 / 落库快照列取字段）
    同时回填 session 的 job_id/profile_id 与岗位名快照。失败（错误 JSON / 无内容）静默跳过，
    不影响主链路。
    """
    if tool_name not in ("get_student_profile", "query_job_detail"):
        return
    session = await get_session(db, session_id)
    if session is None or session.status != "active":
        return
    try:
        data = json.loads(result or "")
    except Exception:
        return
    if not isinstance(data, dict) or data.get("error"):
        return

    material = dict(session.material or {})
    changed = False
    if tool_name == "get_student_profile":
        content = data.get("content")
        if isinstance(content, str) and content.strip():
            material["resume"] = content
            pid = _normalize_id((tool_args or {}).get("profile_id"))
            if pid:
                session.profile_id = pid
            changed = True
    else:  # query_job_detail
        material["job"] = data
        jid = _normalize_id((tool_args or {}).get("job_id"))
        if jid:
            session.job_id = jid
        session.job_title = (str(data.get("jobName") or "")[:200]) or None
        session.company_name = (str(data.get("companyName") or "")[:200]) or None
        changed = True

    if changed:
        session.material = material
        await db.commit()


def format_job(job: dict | None) -> str:
    """把岗位快照整理成易读 JD 文本（给面试官/报告 LLM 注入用）；无 JD 返回空串"""
    if not isinstance(job, dict):
        return ""
    labels = [
        ("jobName", "岗位名称"),
        ("companyName", "公司名称"),
        ("companyType", "公司类型"),
        ("companySize", "公司规模"),
        ("city", "城市"),
        ("salaryNormalized", "薪资"),
        ("educationRequirement", "学历要求"),
        ("level", "级别"),
        ("industryTags", "行业标签"),
        ("jobDescription", "岗位描述"),
        ("abilityRequirements", "能力要求"),
        ("companyBrief", "公司简介"),
    ]
    lines = []
    for key, label in labels:
        v = job.get(key)
        if v is None or v == "":
            continue
        if isinstance(v, (dict, list)):
            text = json.dumps(v, ensure_ascii=False)
        else:
            text = str(v)
        lines.append(f"- {label}：{text}")
    return "\n".join(lines) or json.dumps(job, ensure_ascii=False)


def load_material(session: InterviewSession) -> tuple[str, str, dict | None]:
    """取材料快照 → (简历 markdown, JD 文本, job dict)；快照为空返回 ("", "", None)"""
    material = session.material if isinstance(session.material, dict) else {}
    resume = str(material.get("resume") or "")
    job = material.get("job") if isinstance(material.get("job"), dict) else None
    return resume, format_job(job), job


# ===================== 面试官 system prompt（每轮组装） =====================

def build_interviewer_system(
    interview_type: str,
    resume_text: str,
    job_text: str,
    profile_id: str | None,
    job_id: str | None,
    asked: int,
    max_questions: int,
) -> str:
    """组装面试官人设 + 【面试材料】分区 + 进度。材料锚定开场/换基准时拉取并快照的内容。

    asked：已产生的面试官消息数（开场白第 1 问即算 1），用作进度提示。
    """
    resume_block = resume_text or "（尚未拉取到简历——缺 profileId 就先向用户索取，格式：profileId:xxx）"
    job_block = job_text or "（用户未提供岗位或尚未拉取——缺 jobId 就先向用户索取，格式：jobId:xxx）"
    parts = [
        "你是「微光职引」求职平台的**模拟面试官**，现在为用户主持一场真实感的模拟面试。面试类型："
        f"{type_label(interview_type)}",
        "【你的职责】",
        "1. 材料就绪后以一问一答的节奏提问，像真实面试官一样针对用户回答追问、探究细节，而不是评审机器；",
        "2. 提问必须锚定下方【面试材料】里的简历与岗位 JD 事实，不得编造简历/岗位中不存在的内容向用户提问；",
        "3. 每轮只问 1~2 个紧密相关的问题，不要一次抛出一堆题；宁可多追问几轮，也不跳题；",
        "4. 开场先核对材料：缺简历或岗位 id 时先向用户索取（明确告知需要 profileId:xxx / jobId:xxx 格式），"
        "拿到后立即调用 get_student_profile / query_job_detail 按 id 拉取材料，材料就绪后才开始正式提问；",
        "5. 用户中途说「简历我改过了按新的来 / 换个岗位考我」并给出新 id 时，调用对应工具按新 id 重取，"
        "以新材料为后续提问基准；",
        "6. 用户明确表示结束（如「结束面试 / 出报告 / 就到这里 / 不面了」）或已问满上限时，"
        "**必须调用 submit_mock_interview_report** 生成报告并收尾，结束后不要再提新问题；",
        "7. 只输出给用户看的话（提问/索取/回应），不要解释你的内部流程，不要输出大段开场白以外的介绍。",
        "【面试材料】",
        f"- 面试类型：{interview_type or 'mixed'}",
        f"- 简历 id：{profile_id or '未提供'}",
        f"- 岗位 id：{job_id or '未提供'}",
        "- 用户简历（markdown）：",
        resume_block,
        "- 意向岗位 JD：",
        job_block,
        f"- 面试进度：已问 {asked} 题（提问上限 {max_questions} 题）",
    ]
    return "\n".join(parts)


# ===================== 报告生成（submit_mock_interview_report 工具内部） =====================

REPORT_SYSTEM = """你是「微光职引」求职平台的资深模拟面试评审官。请基于一场完整模拟面试的「问答原文」与「用户简历」（及可选的目标岗位 JD），撰写一份结构化的面试总结报告。

【输入素材】
- 【问答原文】：整场面试逐条记录（面试官提问 + 用户回答）。这是评价用户表现的唯一依据。
- 【用户简历】：本场面试所依据的简历事实。
- 【目标岗位 JD】（可选）：本场面试的岗位基准。
- 【面试类型 / 日期】：见输入。

【内容要求】
1. 「逐题简评」要覆盖本场主要问答（题目多则按考察主题归并），点出每题的考察点与用户表现；
2. 「亮点」与「薄弱项」必须能在问答原文或简历中找到依据，尽量引用具体表现，**严禁臆造**：用户没说过的经历、简历里没有的技能一律不得出现；
3. 「薄弱项」要具体、可补强，不说空话（如「xx 概念解释不完整」可以，「能力不足」不行）；
4. 「补强建议」按优先级给 3~6 条，落到可执行动作（学什么、练什么、怎么自检）；
5. 若问答内容很少或用户基本没有作答，如实说明「本场问答材料有限，评价仅基于现有内容」，不要硬凑表现细节。

【输出格式】只输出 JSON：
{"title": "报告标题（如：Java 后端开发实习生 · 模拟面试报告（2026-09-05），60 字内）",
 "content": "报告正文 markdown（**不含顶层 # 标题行**，服务端会拼接标题）"}

content 必须且只含以下小节（顺序不变，均用二级标题）：
## 总体评价
## 亮点
## 薄弱项
## 逐题简评
## 补强建议

每节用短文或列表均可；语气专业、建设性，面向学生本人。"""


def _extract_json(text: str) -> dict:
    """解析模型输出的 JSON，容忍 ```json ... ``` 围栏（对齐 plan/resume_polish 口径）"""
    text = (text or "").strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return json.loads(text)


def _format_transcript(messages: list[InterviewMessage]) -> str:
    """interview_messages 原文 → 报告 LLM 的逐轮问答文本（面试官/用户交替，按轮编号）"""
    lines: list[str] = []
    round_no = 0
    for m in messages:
        if m.role == "user":
            round_no += 1
            lines.append(f"第 {round_no} 轮（用户回答）：{m.content}")
        else:
            lines.append(f"面试官：{m.content}")
    return "\n".join(lines)


async def _call_report_llm(
    resume_text: str, job_text: str, interview_type: str, transcript: str, interview_date: str
) -> dict:
    """独立报告 LLM：问答原文 + 简历 + JD → {title, content}。解析失败抛异常由上层兜底"""
    parts = [
        f"【面试类型】{type_label(interview_type)}",
        f"【面试日期】{interview_date}",
        "【用户简历】",
        resume_text or "（暂无简历内容）",
        "【目标岗位 JD】",
        job_text or "（未提供岗位 JD，报告基于简历与问答原文评价）",
        "【问答原文（整场）】",
        transcript,
        "请按系统指令只输出 JSON。",
    ]
    messages = [
        {"role": "system", "content": REPORT_SYSTEM},
        {"role": "user", "content": "\n\n".join(parts)},
    ]
    t0 = time.perf_counter()
    try:
        resp = await asyncio.wait_for(get_json_llm().ainvoke(messages), timeout=_REPORT_LLM_TIMEOUT)
    except asyncio.TimeoutError:
        raise TimeoutError(f"报告 LLM 调用超时（>{_REPORT_LLM_TIMEOUT}s），输入len={len('\n\n'.join(parts))}")
    content = resp.content
    if isinstance(content, list):  # 防御：个别 provider 返回 block 列表
        content = "".join(b.get("text", "") for b in content if isinstance(b, dict))
    result = _extract_json(content or "")
    print(
        f"[interview] 报告 LLM 生成完成: {time.perf_counter() - t0:.2f}s, 输出len={len(content or '')}",
        flush=True,
    )
    return result


async def _save_report(
    title: str, content: str, session: InterviewSession, token: str
) -> int:
    """经网关 POST 落库 careers.user_interview_reports，返回 reportId。失败抛异常由上层兜底"""
    url = f"{settings.career_service_base_url}{_REPORTS_URL}"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    body = {
        "title": title,
        "content": content,
        "sessionId": session.session_id,
        "jobId": session.job_id,
        "jobName": session.job_title,
        "companyName": session.company_name,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(url, json=body, headers=headers)
        resp.raise_for_status()
        payload = resp.json()
    data = payload.get("data") or {}
    if data.get("reportId") is None:
        raise ValueError(payload.get("msg") or f"保存面试报告失败（code={payload.get('code')}）")
    return int(data["reportId"])


async def generate_and_save_report(
    session_id: str, user_id: int, token: str
) -> dict:
    """面试结束时的报告生成主流程（submit_mock_interview_report 工具调用）。

    流程：读会话 → 校验材料/问答 → 独立报告 LLM 生成 {title, content} → POST careers 落库 →
    回写会话 ended（report_id/ended_at）。任何关键步骤失败抛异常，由 tools.py 包成 error JSON
    返回面试官转述，**不产生假成功**；会话保持 active 可重试。

    返回 {"reportId", "title"}。注意：本函数不在 FastAPI 请求作用域内，自行开 async_session。
    """
    t0 = time.perf_counter()
    async with async_session() as db:
        session = await get_session(db, session_id)
        if session is None:
            raise ValueError("面试会话不存在，无法生成报告")
        if int(session.user_id) != int(user_id):
            raise ValueError("无权访问该面试会话")
        if session.status == "ended":
            raise ValueError("本场面试已结束并生成过报告，无法重复生成")

        resume_text, job_text, _job = load_material(session)
        if not resume_text.strip():
            raise ValueError(
                "还没有获取到本场面试的简历材料：请先向用户索取简历（profileId:xxx）并调用 "
                "get_student_profile 拉取后再结束出报告"
            )

        messages = await list_messages(db, session_id)
        user_count = sum(1 for m in messages if m.role == "user")
        assistant_count = sum(1 for m in messages if m.role == "assistant")
        if user_count == 0 or assistant_count == 0:
            raise ValueError(
                "本场面试还没有一问一答（至少需要一轮真实问答），暂无法生成有意义的报告；"
                "请先向用户提一个与材料相关的问题并等其回答"
            )
        transcript = _format_transcript(messages)

    interview_date = datetime.now().strftime("%Y-%m-%d")
    raw = await _call_report_llm(
        resume_text, job_text, session.type, transcript, interview_date
    )

    title = str(raw.get("title") or "").strip()[:200] or "模拟面试报告"
    content = str(raw.get("content") or "").strip()
    if not content:
        raise ValueError("报告生成缺少正文内容，请稍后重试")

    # 正文拼上顶层标题（与报告列表/详情展示一致），保证 content 是完整 markdown
    content = f"# {title}\n\n{content}"

    report_id = await _save_report(title, content, session, token)

    # 落库成功后回写面试会话为 ended（幂等）
    async with async_session() as db:
        await mark_ended(db, session_id, report_id)

    print(
        f"[interview] 报告完成: {time.perf_counter() - t0:.2f}s, reportId={report_id}, "
        f"title={title!r}, content_len={len(content)}",
        flush=True,
    )
    return {"reportId": report_id, "title": title}


# ===================== 供路由用的格式化小工具（对齐 chat.py 的事件协议） =====================

def summarize_tool_result(result: str) -> str:
    """把工具返回的 JSON 摘要成一句话，用于前端 trace 步骤的结果展示。

    chat.py 同名函数 + 面试报告分支（reportId → 「已生成面试报告：{title}」）。
    """
    try:
        data = json.loads(result)
    except Exception:
        return (result[:80] + "...") if len(result) > 80 else (result or "已返回结果")

    if isinstance(data, dict):
        if data.get("error"):
            return f"工具返回错误：{str(data['error'])[:60]}"
        if data.get("reportId") is not None:
            title = str(data.get("title") or "").strip()
            return f"已生成面试报告：{title[:30] or '（未命名）'}"
        if data.get("jobName"):
            return f"已获取岗位详情：{str(data['jobName'])[:30]}"
        if data.get("content"):
            return "已获取用户简历材料"
        return "已获取面试材料"
    return "已获取面试材料"
