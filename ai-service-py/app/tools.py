import json
from typing import Annotated, Optional

import httpx
from fastapi import Request
from langchain_core.tools import InjectedToolArg, tool

from app.config import settings
from app.services import flow as flow_service
from app.services import job_recommend, resume_polish


@tool
async def get_student_profile(
    user_id: Annotated[int, InjectedToolArg],
    token: Annotated[str, InjectedToolArg],
    profile_id: Optional[str] = None,
) -> str:
    """获取当前学生的简历画像信息（基本信息、教育背景、技能、工作经历等 markdown 简历原文），用于简历评估与岗位推荐。

    一个学生可有多份简历。当用户对话消息中涉及某个具体简历（形如 `profileId:xxx`、`profileID:xxx`，
    或用户明确指定了某份简历标题/编号）时，必须从对话中抽取简历id填入 profile_id 参数（可去掉
    profileId: 前缀只传纯ID），根据该 profile_id 查询对应简历的详情；
    若用户未指定简历，则默认获取最新一份简历。
    """
    # 转发用户 JWT：Java 网关 AuthGlobalFilter 会对 /users/me/profile 做登录校验，
    # 不带 token 会被网关直接 401 拦截，导致下游 profile-service 根本收不到请求。
    headers = {"Authorization": f"Bearer {token}"} if token else {}

    if profile_id and str(profile_id).strip():
        # 用户消息中可能携带 profileId: 前缀（形如 profileId:xxxx），去掉前缀只保留纯 ID
        raw_profile_id = str(profile_id).strip()
        normalized_profile_id = raw_profile_id.split(":", 1)[-1].strip() if ":" in raw_profile_id else raw_profile_id
        url = f"{settings.profile_service_base_url}/users/me/profile/{normalized_profile_id}"
    else:
        url = f"{settings.profile_service_base_url}/users/me/profile"

    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(url, params={"userId": user_id}, headers=headers)
            resp.raise_for_status()
            payload = resp.json()
    except Exception as e:
        return json.dumps({"error": f"获取简历失败: {e}"}, ensure_ascii=False)

    data = payload.get("data") or {}
    if not data.get("hasProfile") or data.get("profile") is None:
        return json.dumps({"error": f"用户 {user_id} 还没有简历画像"}, ensure_ascii=False)

    return json.dumps(data["profile"], ensure_ascii=False, default=str)


@tool
async def query_job_detail(
    job_id: str,
    token: Annotated[str, InjectedToolArg],
) -> str:
    """根据岗位ID查询岗位的详细信息（岗位名称、公司、城市、薪资、岗位描述、能力要求、核心技能等）。

    当用户发送的对话消息中涉及到某个具体的岗位ID（形如 `jobId:xxx`、`jobID:xxx` 或直接给出岗位编号如 JOB2024...）
    并希望了解该岗位的具体信息（工作内容、任职要求、薪资待遇等）时，必须调用本工具获取岗位详细信息，
    再基于查询结果回答用户。模型需要从对话中抽取岗位ID填入 job_id 参数（可去掉 jobId: 前缀，只传纯ID）。
    """
    raw_job_id = str(job_id or "").strip()
    if not raw_job_id:
        return json.dumps({"error": "缺少岗位ID，请从对话中抽取 jobId 后重试"}, ensure_ascii=False)

    # 用户消息中可能携带 jobId: 前缀（形如 jobId:123455），去掉前缀只保留纯 ID
    normalized_job_id = raw_job_id.split(":", 1)[-1].strip() if ":" in raw_job_id else raw_job_id
    if not normalized_job_id:
        return json.dumps({"error": "岗位ID无效，请从对话中抽取 jobId 后重试"}, ensure_ascii=False)

    url = f"{settings.career_service_base_url}/jobs/{normalized_job_id}"
    # 透传用户 JWT：网关对 /jobs/** 有登录校验，不带 token 会被 401 拦截
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(url, headers=headers)
            resp.raise_for_status()
            payload = resp.json()
    except Exception as e:
        return json.dumps({"error": f"获取岗位详情失败: {e}"}, ensure_ascii=False)

    data = payload.get("data")
    if not data:
        return json.dumps(
            {"error": payload.get("msg") or f"岗位 {normalized_job_id} 不存在或查询失败"},
            ensure_ascii=False,
        )

    return json.dumps(data, ensure_ascii=False, default=str)


@tool
async def start_resume_polish(
    job_id: str,
    profile_id: str,
    session_id: Annotated[str, InjectedToolArg],
    token: Annotated[str, InjectedToolArg] = "",
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """针对目标岗位润色学生的某份简历：对比 JD 找出差距，向用户追问补充信息，最后生成修订稿。

    仅当用户**同时**明确指出了目标岗位与具体简历时才调用，两者都必须从对话中确定：
    - job_id：目标岗位ID（消息中可能带 `jobId:` 前缀，或岗位编号如 JOB2024...）
    - profile_id：要润色的简历ID（消息中可能带 `profileId:` 前缀，或用户明确指定的某份简历）
    若两者缺一，**不要调用本工具**，先向用户追问缺少的信息（如让用户从简历列表里选一份）。
    调用后本工具会提出 1~3 个澄清问题，之后由服务端按流程继续追问，直至信息充足后生成修订稿。
    """
    # 去掉可能携带的 jobId: / profileId: 前缀，只保留纯 ID
    raw_job_id = str(job_id or "").strip().split(":", 1)[-1].strip()
    raw_profile_id = str(profile_id or "").strip().split(":", 1)[-1].strip()
    if not raw_job_id or not raw_profile_id:
        return json.dumps(
            {"error": "缺少 job_id 或 profile_id，请先向用户确认目标岗位与要润色的简历"},
            ensure_ascii=False,
        )

    redis_client = getattr(request.app.state, "redis", None) if request is not None else None
    if redis_client is None:
        return json.dumps({"error": "简历润色服务暂不可用（状态存储未连接）"}, ensure_ascii=False)

    # 拉取目标岗位 JD + 指定简历
    try:
        job = await resume_polish.fetch_job_detail(raw_job_id, token)
        profile = await resume_polish.fetch_profile(raw_profile_id, token)
    except Exception as e:
        return json.dumps({"error": f"获取岗位或简历失败: {e}"}, ensure_ascii=False)

    resume_text = profile.get("content") if isinstance(profile, dict) else str(profile)
    if not resume_text:
        return json.dumps({"error": "该简历内容为空，无法润色"}, ensure_ascii=False)

    # 首次差距分析（历史为空）→ 进入流程状态
    signal = await resume_polish.gather(job, resume_text, [])
    if signal["next"] == "abandon":
        # 异常/岔开话题：不写入流程状态，让 chat.py 直接流式输出 text 后回到普通对话
        return json.dumps(
            {"flow": "resume_polish", "next": signal["next"], "text": signal["text"]},
            ensure_ascii=False,
        )
    flow = {
        "state": "gathering" if signal["next"] == "ask_more" else "ready",
        "jobId": raw_job_id,
        "profileId": raw_profile_id,
        "job": job,
        "resume": resume_text,
        "history": [],
        "last_question": signal["text"] if signal["next"] == "ask_more" else None,
        "revised": None,
    }
    await flow_service.set_flow(redis_client, session_id, flow)

    # flow 标记供 chat.py 识别：直接流式输出 text 并短路跳出主循环，不让主 LLM 复述
    return json.dumps(
        {"flow": "resume_polish", "next": signal["next"], "text": signal["text"]},
        ensure_ascii=False,
    )


@tool
async def save_resume_edit(
    instruction: str,
    profile_id: str = "",
    token: Annotated[str, InjectedToolArg] = "",
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """修改并保存一份简历：按用户的修改要求改写简历后写回简历库。

    当用户在对话中要求「修改/更新某份简历」时调用，例如改标题、补充技能、调整描述措辞、
    标注版本（如「标题加上 AI 修改版」）等。流程：获取简历原文 → 按 instruction 改写 → 保存。
    - instruction：用户的修改要求（必填），如「把标题改成 AI 修改版」「在技能栏加上 Redis」
    - profile_id：要修改的简历 id（消息中可能带 `profileId:` 前缀）；为空则修改最新一份简历
    注意：本工具用于流程结束后的追加修改；针对某个岗位做系统性润色请用 start_resume_polish。
    """
    raw_instruction = str(instruction or "").strip()
    if not raw_instruction:
        return json.dumps({"error": "缺少修改要求 instruction，请描述要如何修改简历"}, ensure_ascii=False)
    # 去掉可能携带的 profileId: 前缀
    raw_profile_id = str(profile_id or "").strip().split(":", 1)[-1].strip()

    try:
        if raw_profile_id:
            profile = await resume_polish.fetch_profile(raw_profile_id, token)
            content = profile.get("content") if isinstance(profile, dict) else str(profile)
        else:
            # 未指定简历 → 修改最新一份（覆盖更新，不新建副本）
            latest = await resume_polish.fetch_latest_profile(token)
            raw_profile_id = latest.get("profileId") or ""
            content = latest.get("content") or ""
        if not content:
            return json.dumps({"error": "该简历内容为空，无法修改"}, ensure_ascii=False)

        new_content = await resume_polish.rewrite_resume(content, raw_instruction)
        await resume_polish.save_profile(raw_profile_id, new_content, token)
    except Exception as e:
        return json.dumps({"error": f"修改简历失败: {e}"}, ensure_ascii=False)

    return json.dumps(
        {
            "success": True,
            "profileId": raw_profile_id,
            "summary": "已按你的要求修改并保存简历，评分任务已触发",
        },
        ensure_ascii=False,
    )


@tool
async def recommend_specific_jobs(
    job_intention: str,
    city: Optional[str] = None,
    salary_expectation: Optional[str] = None,
    skills: Optional[str] = None,
    experience: Optional[str] = None,
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """根据学生的求职意愿推荐具体岗位（人岗匹配）。

    当学生表达了求职意向（想做什么岗位、目标城市、薪资期望、技能栈、经验水平等）时，
    调用本工具进行岗位推荐。模型需要从对话中抽取并填写以下参数：
    - job_intention：求职意向/目标岗位（必填），如「Java 后端开发工程师」
    - city：期望城市（选填），如「上海」
    - salary_expectation：薪资期望（选填），如「15k-25k」
    - skills：技能栈（选填），如「Java, Spring, MySQL, Redis」
    - experience：经验水平（选填），如「应届生 / 3 年经验」

    内部会把这些信息拼成用户画像文本，走「向量检索 + DeepSeek 精排」返回最佳匹配岗位及备选岗位。
    """
    # 拼装成与 /jobs/recommend/specific 接口一致的「用户画像」查询文本
    profile = {
        "jobIntention": job_intention,
        "city": city,
        "salaryExpectation": salary_expectation,
        "skills": skills,
        "experience": experience,
    }
    # 去掉空字段，避免无意义信息干扰向量检索
    profile = {k: v for k, v in profile.items() if v}
    query_text = json.dumps(profile, ensure_ascii=False)

    # 复用 job_recommend 的核心业务函数，从 app.state 拿 pgvector 连接池
    pool = getattr(request.app.state, "pg_pool", None)
    if pool is None:
        return json.dumps({"error": "岗位推荐服务暂不可用（向量库未连接）"}, ensure_ascii=False)

    return await job_recommend.recommend_specific_job(pool, query_text)


# 所有可注册给模型的工具（新增工具只需追加到这里）
ALL_TOOLS = [get_student_profile, recommend_specific_jobs, query_job_detail, start_resume_polish, save_resume_edit]
