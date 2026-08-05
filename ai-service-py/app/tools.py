
from langchain_core.tools import tool

@tool
async def get_student_profile(user_id: int = 111) -> str:
    """获取学生的简历画像信息（基本信息、教育、技能等），供评估和推荐使用"""
    # 这里调用 profile-service 的 HTTP 接口或直连数据库
    profile_json = await fetch_profile_from_service(user_id)
    return profile_json