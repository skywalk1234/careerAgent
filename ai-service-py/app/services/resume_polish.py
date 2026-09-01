"""简历分析 / 润色核心：analyze（诊断）+ polish（润色）+ 外部 HTTP 访问。

两个专家工具均为同步、一次性、结构化输入输出，由主 LLM 编排（无服务端状态机）：
- analyze：简历（+ 可选 JD）→ {analysis, questions}（文字诊断 + 可选追问）
- polish：简历 + JD(可选) + 用户补充/修改要求 → {revisedContent, changes[], reflectChecklist[]}（含 reflect 自检）

本模块不持有任何状态。
"""
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

只输出 JSON：{"analysis": "完整分析文本", "questions": "追问或空字符串"}"""


POLISH_SYSTEM = """你是"微光职引"求职平台的专业简历润色专家。请基于用户原始简历、用户补充/修改要求与（可选的）目标岗位 JD，重写简历，使其更匹配目标岗位。

必须遵守的红线：
1. 只能重组、改写、扩写用户已明确提供的内容；严禁虚构公司、项目、职位、经历、时间或量化数字。
2. 保留原简历全部已有事实（经历、时间、数字、项目），不得丢失、删改或篡改。
3. 若提供了 JD，对齐 JD 的能力要求与关键词，优先突出与岗位匹配的经历与技能，可调整描述顺序与措辞。
4. 使用清晰有力的简历语言；量化结果只能用用户提供的数据，用户未提供则保留原表述，绝不自己编数字。
5. 输出完整 markdown 格式简历。

输出前必须自检（reflect），逐项核对并在不通过时自行修正后再输出：
- 是否保留原简历全部事实？
- 是否编造了经历或量化数字？
- 是否命中 JD 的关键词与能力要求？
- 是否遗漏原简历内容？

只输出 JSON：{"revisedContent": "完整markdown", "changes": [{"before":"改动前","after":"改动后","reason":"原因"}], "reflectChecklist": [{"item":"核对项","ok":true}]}"""


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


async def _call_json_llm(system: str, user_content: str) -> dict:
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": user_content},
    ]
    t0 = time.perf_counter()
    resp = await get_json_llm().ainvoke(messages)
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


async def analyze(job: dict | None, resume: str) -> dict:
    """简历分析：语言/结构/内容（+ 可选 JD 契合度）→ 文字诊断 + 可选追问。

    返回 {"analysis", "questions"}。解析失败抛异常由上层兜底。
    """
    parts = []
    if job:
        parts.append(f"【目标岗位 JD】\n{_format_job(job)}")
    parts.append(f"【用户简历（markdown 原文）】\n{resume}")
    parts.append("请按系统指令输出 JSON。")
    result = await _call_json_llm(ANALYZE_SYSTEM, "\n\n".join(parts))
    analysis = result.get("analysis")
    if not analysis:
        raise ValueError("分析结果缺少 analysis")
    return {
        "analysis": str(analysis),
        "questions": str(result.get("questions") or ""),
    }


# ---------- 工具：polish（简历润色专家） ----------

async def polish(job: dict | None, resume: str, extra_info: str) -> dict:
    """润色：JD(可选) + 简历 + 用户补充/修改要求 → 修订稿（含 reflect 自检）。

    返回 {"revisedContent", "changes", "reflectChecklist"}。解析失败抛异常由上层兜底。
    """
    parts = []
    if job:
        parts.append(f"【目标岗位 JD】\n{_format_job(job)}")
    parts.append(f"【用户原始简历】\n{resume}")
    parts.append(f"【用户补充/修改要求】\n{extra_info or '（无）'}")
    parts.append("请按系统指令输出 JSON。")
    result = await _call_json_llm(POLISH_SYSTEM, "\n\n".join(parts))
    revised = result.get("revisedContent")
    if not revised:
        raise ValueError("润色结果缺少 revisedContent")
    return {
        "revisedContent": str(revised),
        "changes": result.get("changes") or [],
        "reflectChecklist": result.get("reflectChecklist") or [],
    }


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
