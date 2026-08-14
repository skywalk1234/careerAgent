import json
from typing import Annotated

import httpx
from langchain_core.tools import InjectedToolArg, tool

from app.config import settings


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


# 所有可注册给模型的工具（新增工具只需追加到这里）
ALL_TOOLS = [get_student_profile]
