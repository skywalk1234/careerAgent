"""简历分析 / 润色核心：analyze（诊断）+ polish（润色）+ 外部 HTTP 访问。

两个专家工具均为同步、一次性、结构化输入输出，由主 LLM 编排（无服务端状态机）：
- analyze：简历 + JD(可选) + 相似简历历史点评参考(可选) → {analysis, questions}（文字诊断 + 可选追问）
- polish：简历 + JD(可选) + 用户补充/修改要求 → {revisedContent, changes[]}
  **三阶段 Reflection**：EXEC 生成 → REVIEW 独立评审（专职拦截「只学过却写成项目已使用」等越界/虚构）→
  不通过则 REFINE 修正 → 再评审，直到通过或达 polish_max_refine_rounds 轮。

本模块不持有任何状态。
"""
import asyncio
import json
import time

import httpx

from app.config import settings
from app.services.llm import get_json_llm

ANALYZE_SYSTEM = """你是"微光职引"求职平台的专业简历分析专家。请对用户简历做专业评估，从以下维度分析：
1. 语言表达：用词是否准确专业，有无口语化/空话套话；
2. 结构条理：段落组织、信息层次是否清晰，重点是否突出；
3. 内容完整性：教育/实习/项目/技能是否完整，有无可量化成果；
4. JD 契合度（若提供了目标岗位 JD）：与 JD 关键技能/经验的匹配与差距。

硬性约束：
- 只基于简历实际内容与（可选的）JD 分析，不虚构简历中不存在的经历。
- analysis 控制在 300 字以内，分维度、可直接展示给用户。
- 如需向用户追问（如目标岗位、具体项目细节），给出 1~3 个自然语言问题；无需追问则 questions 为空字符串。
- 若提供了【相似简历历史点评参考】：该段中的「相似简历片段」属于**他人的历史简历**，其中的公司、项目、经历、技能、数字均与当前用户无关，只是点评对象；它既不是当前简历的一部分，也不得写入诊断。
- 参考段仅用于提炼专家点评的**评判标准与改写思路**，判断当前简历是否存在同类问题；点评针对的缺陷若当前简历不具备，忽略该案例。
- 输出前核对：analysis 中出现的公司/项目/经历/量化数字必须全部出自【用户简历】原文，参考段内容混入即视为输出错误。

只输出 JSON：{"analysis": "完整分析文本", "questions": "追问或空字符串"}"""


# ---------- 润色专家三阶段（Reflection）：生成 → 评审 → 修正 ----------
# 每阶段一个模型、各司其职，避免把「写 + 自我审查 + 多项红线」压给同一个 prompt：
# - EXEC 只负责写一版草稿；
# - REVIEW 专职批判（拿到完整源材料，重点抓「只学过却被写成项目已使用」等越界）；
# - REFINE 只负责按评审反馈改稿。
# 评审不通过 → 修正 → 再评审，直到通过或达到最大轮数。

POLISH_EXEC_SYSTEM = """你是"微光职引"求职平台的专业简历润色专家。请基于【用户原始简历】与【用户补充/修改要求】（含可选的目标岗位 JD），输出一版润色后的完整简历 markdown，使其更匹配目标岗位。

写作红线：
1. 内容只能来自【用户原始简历】原文，以及【用户补充/修改要求】里用户明确说明过的内容；严禁虚构公司、项目、职位、经历、时间或量化数字。
2. 不得丢失、删改或篡改原始简历里已有的任何事实。
3. 用户只说「学过 / 了解 / 正在学」的技术，一律不得写成某项目/经历「已使用 / 应用了」。
4. 量化结果只能用用户提供的数据；未提供则保留原表述，绝不编数字。
5. 语言简洁有力，突出与岗位 JD 匹配的经历与技能（无 JD 则优化表达、保持原有结构）。

只输出 JSON：{"revisedContent": "完整markdown", "changes": ["简要改动说明1", "简要改动说明2"]}
changes 是相对【用户原始简历】的简短改动说明（每条 ≤30 字，说明改了什么、为什么），只列实际改动。"""


POLISH_REVIEW_SYSTEM = """你是一位极其严格的简历评审专家。你的任务是审查一份「润色后的简历」，找出其中**必须修正**的问题并给出可操作反馈。你只评审、不重写。

请对照下列来源审查【待评审的修订稿】：
- 【目标岗位 JD】(可选)：判断是否命中岗位关键词/能力要求。
- 【用户原始简历】：事实基线，修订稿不得增删改其事实。
- 【用户补充/修改要求】：区分「用户明确说已实际用于某项目的技术」与「用户只是学过 / 了解 / 正在学的技术」。

必须核查（每条问题都要能具体指向修订稿的某个片段，只报告真实存在的问题，不吹毛求疵）：
1. 事实越界（最高优先级）：修订稿是否出现【用户原始简历】中不存在、用户也未明确说明「已用于某项目」的公司 / 项目 / 职位 / 经历 / 技术 / 量化数字？
   特别注意：用户只是「学过 / 了解」某项技术 ≠ 该项目用到了它——若修订稿把这类技术写进「项目 / 实习做了什么」，必须标为 must-fix。
2. 完整性：原始简历的已有事实 / 经历 / 项目 / 技能是否被丢失、删改、篡改？
3. 语言：是否有口语化、空话套话、含糊表述（空洞的「负责 / 参与」、无信息量的堆砌）？
4. JD 契合度（若有 JD）：是否突出与岗位匹配的技能与成果；明显该强调而未强调的算 must-fix。
5. 结构与重点：段落层次是否清晰、与 JD 相关的经历是否被放在显眼位置。

只输出 JSON：{"passed": true/false, "mustFix": [{"detail": "具体问题（引用修订稿片段）", "fixHint": "怎么改"}]}
- mustFix 只列「不改会出问题」的项；passed 为 true 时 mustFix 必须为空数组。"""


POLISH_REFINE_SYSTEM = """你是"微光职引"求职平台的专业简历润色专家。你的上一版润色稿被评审专家指出了必须修正的问题，请据此改进并输出**完整**的简历 markdown（不是片段）。

修正时结合【目标岗位 JD】(可选)、【用户原始简历】与【用户补充/修改要求】，针对评审的每条 must-fix 逐一处理，且不得在修正中引入新错误：
- 不得虚构公司 / 项目 / 职位 / 经历 / 时间 / 量化数字；
- 不得把用户「只是学过 / 了解」的技术写成项目「已使用」；
- 不得丢失原始简历已有事实；保留上一稿中正确的改进。

只输出 JSON：{"revisedContent": "完整markdown", "changes": ["相对【用户原始简历】的简短改动说明，每条 ≤30 字"]}"""


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


# 单个 LLM 调用允许的最长耗时。生成整篇简历 markdown 偏重，放宽到 150s；
# 超过即视为挂死，抛超时错误，由上层工具兜底返回 error（不会无限卡住前端）。
_LLM_TIMEOUT = 150


async def _call_json_llm(system: str, user_content: str) -> dict:
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": user_content},
    ]
    t0 = time.perf_counter()
    # langchain-openai 的 timeout 参数在 async 下可能不生效（曾有挂起问题），
    # 这里用 asyncio.wait_for 做硬超时兜底，保证工具调用不会无限挂起。
    try:
        resp = await asyncio.wait_for(get_json_llm().ainvoke(messages), timeout=_LLM_TIMEOUT)
    except asyncio.TimeoutError:
        raise TimeoutError(f"LLM 调用超时（>{_LLM_TIMEOUT}s），输入len={len(user_content)}")
    elapsed = time.perf_counter() - t0
    print(
        f"[resume-tool] LLM调用完成: {elapsed:.2f}s, 输入len={len(user_content)}, "
        f"输出len={len(resp.content or '')}, system={system[:20]!r}",
        flush=True,
    )
    return _extract_json(resp.content or "")


# ---------- 工具：analyze（简历分析专家） ----------

def _format_job(job: dict) -> str:
    """把岗位字典整理成易读的 JD 文本（只挑已知字段，缺字段时回退到整段 JSON）"""
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


def _format_references(references: list) -> str:
    """把检索到的相似简历样例整理成 few-shot 参考段。

    每个案例都显式标注为「他人简历 + 点评」并加分隔框隔离，首尾各放一句提醒，
    防止分析专家把样例片段误当成当前用户简历内容（上下文污染）。
    """
    lines = ["以下为平台沉淀的「历史他人简历 + 专家点评」样例，均与当前用户无关，仅用于提炼评判标准：", ""]
    for i, ref in enumerate(references, 1):
        category = ref.get("jobCategory")
        head = f"案例 {i}" + (f"（岗位方向：{category}）" if category else "")
        lines.append(f"━━━ {head} ━━━")
        lines.append("[他人简历片段]（非当前用户）")
        lines.append(ref.get("resumeSnippet", ""))
        lines.append("[专家当时的点评]")
        lines.append(ref.get("comment", ""))
        lines.append("")
    lines.append(
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
        "以上案例中的片段与点评均属他人，不是当前用户的简历内容，仅供借鉴评判标准；\n"
        "诊断必须只基于下方【用户简历】部分，不得混入以上任何案例内容。"
    )
    return "\n".join(lines)


async def analyze(job: dict | None, resume: str, references: list | None = None) -> dict:
    """简历分析：语言/结构/内容（+ 可选 JD 契合度 + 可选历史点评参考）→ 文字诊断 + 可选追问。

    返回 {"analysis", "questions"}。解析失败抛异常由上层兜底。
    """
    parts = []
    if job:
        parts.append(f"【目标岗位 JD】\n{_format_job(job)}")
    if references:
        parts.append(f"【相似简历历史点评参考】\n{_format_references(references)}")
    parts.append(f"【用户简历（唯一事实来源，诊断只能基于此部分）】\n{resume}")
    parts.append("请按系统指令输出 JSON。")
    result = await _call_json_llm(ANALYZE_SYSTEM, "\n\n".join(parts))
    analysis = result.get("analysis")
    if not analysis:
        raise ValueError("分析结果缺少 analysis")
    return {
        "analysis": str(analysis),
        "questions": str(result.get("questions") or ""),
    }


# ---------- 工具：polish（简历润色专家 · 三阶段 Reflection） ----------

def _source_parts(job: dict | None, resume: str, extra_info: str) -> list[str]:
    """三阶段共用的源材料段（JD 可选），统一拼接避免各阶段口径漂移"""
    parts = []
    if job:
        parts.append(f"【目标岗位 JD】\n{_format_job(job)}")
    parts.append(f"【用户原始简历】\n{resume}")
    parts.append(f"【用户补充/修改要求】\n{extra_info or '（无）'}")
    return parts


def _review_prompt(job: dict | None, resume: str, extra_info: str, draft: str) -> str:
    parts = _source_parts(job, resume, extra_info)
    parts.append(f"【待评审的修订稿】\n{draft}")
    parts.append("请按系统指令输出评审 JSON。")
    return "\n\n".join(parts)


def _refine_prompt(job: dict | None, resume: str, extra_info: str, draft: str, feedback: str) -> str:
    parts = _source_parts(job, resume, extra_info)
    parts.append(f"【上一次修订稿】\n{draft}")
    parts.append(f"【评审反馈】\n{feedback}")
    parts.append("请按系统指令输出修正后的完整 JSON。")
    return "\n\n".join(parts)


def _format_feedback(review: dict) -> str:
    """把评审 mustFix 列表整理成可读的反馈文本，供 REFINE 使用"""
    must_fix = review.get("mustFix") or []
    lines = []
    for i, item in enumerate(must_fix, 1):
        detail = item.get("detail") if isinstance(item, dict) else str(item)
        hint = item.get("fixHint") if isinstance(item, dict) else ""
        lines.append(f"{i}. {detail}" + (f"\n   修复建议：{hint}" if hint else ""))
    return "\n".join(lines) if lines else "（无）"


async def polish(job: dict | None, resume: str, extra_info: str) -> dict:
    """润色（三阶段 Reflection）：JD(可选) + 简历 + 用户补充/修改要求 → 修订稿。

    流程：EXEC 生成一版 → REVIEW 评审 → 不通过则 REFINE 修正 → 再评审，
    直到评审通过或达到 polish_max_refine_rounds 轮。评审职责独立于写作，
    专职拦截「把只是学过/了解的技术写成项目已使用」等越界与虚构。
    返回 {"revisedContent", "changes"}（changes 相对【用户原始简历】）。
    解析失败抛异常由上层兜底。
    """
    t_start = time.perf_counter()

    # 阶段 1：生成草稿
    result = await _call_json_llm(POLISH_EXEC_SYSTEM, "\n\n".join(_source_parts(job, resume, extra_info)))
    draft = str(result.get("revisedContent") or "").strip()
    if not draft:
        raise ValueError("润色结果缺少 revisedContent")
    changes = result.get("changes") or []
    print(
        f"[resume-tool] polish ①生成完成 {time.perf_counter() - t_start:.2f}s, "
        f"revised_len={len(draft)}",
        flush=True,
    )

    # 阶段 2+3：评审 → 修正循环（最多 polish_max_refine_rounds 轮修正）
    max_rounds = max(0, int(settings.polish_max_refine_rounds))
    for attempt in range(max_rounds + 1):
        t1 = time.perf_counter()
        review = await _call_json_llm(
            POLISH_REVIEW_SYSTEM, _review_prompt(job, resume, extra_info, draft)
        )
        passed = bool(review.get("passed"))
        n_fix = len(review.get("mustFix") or [])
        print(
            f"[resume-tool] polish ②评审#{attempt} 通过={passed} mustFix={n_fix} "
            f"耗时 {time.perf_counter() - t1:.2f}s",
            flush=True,
        )
        if passed or attempt == max_rounds:
            break

        feedback = _format_feedback(review)
        t2 = time.perf_counter()
        refined = await _call_json_llm(
            POLISH_REFINE_SYSTEM, _refine_prompt(job, resume, extra_info, draft, feedback)
        )
        new_draft = str(refined.get("revisedContent") or "").strip()
        if not new_draft:
            print("[resume-tool] polish ③修正返回空稿，保留上一版", flush=True)
            break
        draft = new_draft
        if refined.get("changes"):
            changes = refined["changes"]
        print(
            f"[resume-tool] polish ③修正#{attempt} 完成 {time.perf_counter() - t2:.2f}s, "
            f"revised_len={len(draft)}",
            flush=True,
        )

    print(f"[resume-tool] polish 总耗时 {time.perf_counter() - t_start:.2f}s", flush=True)
    return {"revisedContent": draft, "changes": changes}


# ---------- 外部 HTTP 访问（透传 JWT 走网关） ----------

async def fetch_job_detail(job_id: str, token: str) -> dict:
    """按 jobId 拉取完整岗位 JD（career-service）"""
    url = f"{settings.career_service_base_url}/jobs/{job_id}"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(url, headers=headers)
        resp.raise_for_status()
        payload = resp.json()
    data = payload.get("data")
    if not data:
        raise ValueError(payload.get("msg") or f"岗位 {job_id} 不存在或查询失败")
    return data


async def fetch_profile(profile_id: str, token: str) -> dict:
    """按 profileId 拉取指定简历（profile-service，多简历场景）"""
    url = f"{settings.profile_service_base_url}/users/me/profile/{profile_id}"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(url, headers=headers)
        resp.raise_for_status()
        payload = resp.json()
    data = payload.get("data") or {}
    if not data.get("hasProfile") or data.get("profile") is None:
        raise ValueError("简历不存在")
    return data["profile"]


async def fetch_latest_profile(token: str) -> dict:
    """拉取最新一份简历，返回 {profileId, content}。

    注意：GET /users/me/profile 响应顶层的 profileId 是 userId（不是简历 id），
    真实简历 id 在 profile.profileId 里，必须从这里取，否则覆盖更新会定位到错误的简历。
    """
    url = f"{settings.profile_service_base_url}/users/me/profile"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(url, headers=headers)
        resp.raise_for_status()
        payload = resp.json()
    data = payload.get("data") or {}
    profile = data.get("profile")
    if not data.get("hasProfile") or profile is None:
        raise ValueError("用户还没有简历")
    if not isinstance(profile, dict):
        return {"profileId": "", "content": str(profile)}
    return {"profileId": profile.get("profileId") or "", "content": profile.get("content") or ""}


async def save_profile(profile_id: str, content: str, token: str) -> None:
    """写回简历：POST /users/me/profile（resume-parser-service 转发 profile_storage 队列落库）"""
    url = f"{settings.profile_service_base_url}/users/me/profile"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    body = {"content": content, "profileId": profile_id}
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.post(url, json=body, headers=headers)
        resp.raise_for_status()
        payload = resp.json()
    code = payload.get("code")
    if code is None or not (200 <= int(code) < 300):
        raise ValueError(payload.get("msg") or f"保存失败（code={code}）")


async def save_profile_as_new(content: str, token: str) -> None:
    """把修订稿另存为一份新简历，原简历保持不变。

    profileId 传空，由 profile-service 落库时用 UUID.randomUUID() 自动生成新简历 id 并插入
    （SaveProfileService.saveProfile 的空 profileId 分支）。
    注意：Java 端 FileController 对 fileName 写死 null，新简历在前端列表暂无标题，只会出现在最近更新位置。
    """
    await save_profile("", content, token)
