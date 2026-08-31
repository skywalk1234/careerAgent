"""简历润色核心：差距分析（gather）+ 润色（polish）+ 外部 HTTP 访问。

LLM 只做两件事，均为一次性、结构化输入输出：
- gather：JD vs 简历差距分析，输出 {next: ask_more|ready|abandon, text}（控制信号唯一结构化字段）
- polish：基于简历 + 补充问答 + JD 生成修订稿，输出 {revisedContent, changes[], reflectChecklist}

流程路由由 chat.py 的 Redis 状态机决定，本模块不持有任何状态。
"""
import json

import httpx

from app.config import settings
from app.services.llm import get_json_llm

# gather 阶段问答轮次上限（chat.py 也用它做硬性截断）
GATHER_MAX_ROUNDS = 4

GATHER_SYSTEM = """你是"微光职引"求职平台的专业简历润色专家。你的任务是：对比用户简历与目标岗位 JD，找出差距，并在必要时向用户追问补充信息。

你需要重点对比：
- 岗位 JD 的能力要求、岗位描述、技能要求；
- 用户简历（markdown 原文）中已有的教育背景、项目/实习经历、技能、量化成果。

判断规则：
1. 找出「JD 明确要求、但简历缺失或证据不足」的关键点（如要求高并发经验但简历未体现、要求量化成果但全是定性描述、要求某技术栈但简历没写）。
2. 若存在这样的关键缺口，且尚未问过 → 输出 next=ask_more，text 中给出简短差距诊断 + 最多 2~3 个关键追问（自然语言，具体、可回答，不要编号过多）。
3. 若信息已足够开始润色 → 输出 next=ready，text 为「信息已经比较充分，可以开始修改简历。要现在开始吗？」
4. 若用户最近的回答与所问问题完全无关（明显岔开话题或拒绝回答）→ 输出 next=abandon，text 为一句自然回应。

硬性约束：
- 只针对 JD 有要求而简历缺少的点提问；不重复已问过的问题；不编造用户简历中不存在的内容。
- 追问要具体可回答（如「你在 XX 项目里具体负责什么？有没有可量化的结果？」）。
- 回复整体控制在 200 字以内。
- 只输出 JSON：{"next": "ask_more|ready|abandon", "text": "..."}"""

POLISH_SYSTEM = """你是"微光职引"求职平台的专业简历润色专家。请基于用户原始简历、用户补充的问答信息与目标岗位 JD，重写简历，使其更匹配目标岗位。

必须遵守的红线：
1. 只能重组、改写、扩写用户已明确提供的内容；严禁虚构公司、项目、职位、经历、时间或量化数字。
2. 保留原简历全部已有事实（经历、时间、数字、项目），不得丢失、删改或篡改。
3. 对齐 JD 的能力要求与关键词，优先突出与岗位匹配的经历与技能，可调整描述顺序与措辞。
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
    resp = await get_json_llm().ainvoke(messages)
    return _extract_json(resp.content or "")


# ---------- gather：差距分析 + 出题 ----------

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


def _format_history(history: list) -> str:
    if not history:
        return "（暂无）"
    return "\n".join(f"问：{h.get('question', '')}\n答：{h.get('answer', '')}" for h in history)


async def gather(job: dict, resume: str, history: list) -> dict:
    """差距分析：根据 JD + 简历 + 已收集问答，决定继续追问 / 准备润色 / 放弃。

    返回 {"next": "ask_more|ready|abandon", "text": "..."}。解析失败时安全降级为 abandon。
    """
    user_content = (
        f"【目标岗位 JD】\n{_format_job(job)}\n\n"
        f"【用户简历（markdown 原文）】\n{resume}\n\n"
        f"【已收集的问答历史】\n{_format_history(history)}\n\n"
        "请按系统指令输出 JSON。"
    )
    try:
        result = await _call_json_llm(GATHER_SYSTEM, user_content)
        next_val = result.get("next")
        if next_val not in ("ask_more", "ready", "abandon"):
            next_val = "ready"
        return {"next": next_val, "text": str(result.get("text") or "")}
    except Exception:
        return {"next": "abandon", "text": "抱歉，简历分析暂时出了点问题，请稍后再试。"}


async def polish(job: dict, resume: str, history: list) -> dict:
    """润色：简历 + 补充问答 + JD → 修订稿（含 reflect 自检）。

    返回 {"revisedContent", "changes", "reflectChecklist"}。解析失败抛异常由上层兜底。
    """
    user_content = (
        f"【目标岗位 JD】\n{_format_job(job)}\n\n"
        f"【用户原始简历】\n{resume}\n\n"
        f"【用户补充的问答信息】\n{_format_history(history)}\n\n"
        "请按系统指令输出 JSON。"
    )
    result = await _call_json_llm(POLISH_SYSTEM, user_content)
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


async def save_profile(profile_id: str, content: str, token: str) -> None:
    """写回修订稿：POST /users/me/profile（resume-parser-service 转发 profile_storage 队列落库，自动触发重评分）"""
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
