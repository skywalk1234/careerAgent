"""简历润色流程的状态存储（Redis）。

状态机: idle → gathering → ready → polishing → done → idle
key: resume_flow:{session_id}，值为 JSON，TTL 30 分钟（活动时刷新）。
进度不依赖 LLM 记忆，由服务端按状态路由每一条用户消息。
"""
import json

from redis.asyncio import Redis

# 流程状态超时时间（秒）：30 分钟无活动自动过期，避免残留状态干扰后续会话
FLOW_TTL = 30 * 60


def _key(session_id: str) -> str:
    return f"resume_flow:{session_id}"


async def get_flow(redis: Redis, session_id: str) -> dict | None:
    """读取当前会话的流程状态；不存在或解析失败返回 None（视为 idle）"""
    if redis is None:
        return None
    raw = await redis.get(_key(session_id))
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


async def set_flow(redis: Redis, session_id: str, flow: dict) -> None:
    """写入流程状态（带 TTL）"""
    if redis is None:
        return
    await redis.set(_key(session_id), json.dumps(flow, ensure_ascii=False), ex=FLOW_TTL)


async def clear_flow(redis: Redis, session_id: str) -> None:
    """清除流程状态（流程结束/放弃时调用）"""
    if redis is None:
        return
    await redis.delete(_key(session_id))
