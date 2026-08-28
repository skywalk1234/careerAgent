import json
from typing import Annotated, Optional

import httpx
from fastapi import Request
from langchain_core.tools import InjectedToolArg, tool

from app.config import settings
from app.services import job_recommend


@tool
async def get_student_profile(
    user_id: Annotated[int, InjectedToolArg],
    token: Annotated[str, InjectedToolArg],
) -> str:
    """获取当前学生的简历画像信息（基本信息、教育背景、技能、工作经历等），用于简历评估与岗位推荐。"""
    url = f"{settings.profile_service_base_url}/users/me/profile"
    # 转发用户 JWT：Java 网关 AuthGlobalFilter 会对 /users/me/profile 做登录校验，
    # 不带 token 会被网关直接 401 拦截，导致下游 profile-service 根本收不到请求。
    headers = {"Authorization": f"Bearer {token}"} if token else {}
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
ALL_TOOLS = [get_student_profile, recommend_specific_jobs, query_job_detail]
