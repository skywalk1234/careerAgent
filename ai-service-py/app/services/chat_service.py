import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import ChatMessage, ChatSession


def gen_id() -> str:
    """生成短 id，替代原 Java 服务的 IdGenerator.generateShortId"""
    return uuid.uuid4().hex


async def create_session(db: AsyncSession, user_id: str, title: str | None = None) -> ChatSession:
    session = ChatSession(session_id=gen_id(), user_id=user_id, title=title or "新对话")
    db.add(session)
    await db.commit()
    await db.refresh(session)
    return session


async def get_session(db: AsyncSession, session_id: str) -> ChatSession | None:
    stmt = select(ChatSession).where(ChatSession.session_id == session_id)
    result = await db.execute(stmt)
    return result.scalar_one_or_none()


async def list_sessions(db: AsyncSession, user_id: str) -> list[ChatSession]:
    stmt = (
        select(ChatSession)
        .where(ChatSession.user_id == user_id)
        .order_by(ChatSession.pinned.desc(), ChatSession.updated_at.desc())
    )
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def save_user_message(db: AsyncSession, session_id: str, message_id: str, content: str) -> ChatMessage:
    msg = ChatMessage(
        message_id=message_id,
        session_id=session_id,
        role="user",
        content=content,
        status="succeeded",
    )
    db.add(msg)
    await db.commit()
    await db.refresh(msg)
    return msg


async def get_message(db: AsyncSession, message_id: str) -> ChatMessage | None:
    stmt = select(ChatMessage).where(ChatMessage.message_id == message_id)
    result = await db.execute(stmt)
    return result.scalar_one_or_none()


async def list_messages(db: AsyncSession, session_id: str) -> list[ChatMessage]:
    stmt = (
        select(ChatMessage)
        .where(ChatMessage.session_id == session_id)
        .order_by(ChatMessage.created_at.asc())
    )
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def save_assistant_message(db: AsyncSession, session_id: str, content: str) -> str:
    """保存助手消息并更新会话标题/预览，返回消息 id"""
    message_id = gen_id()
    msg = ChatMessage(
        message_id=message_id,
        session_id=session_id,
        role="assistant",
        content=content,
        status="succeeded",
        agent_trace={"steps": [], "status": "succeeded", "activeStepId": None},
    )
    db.add(msg)

    session = await get_session(db, session_id)
    if session is not None:
        session.last_message_preview = content[:100] + ("..." if len(content) > 100 else "")
        if session.title == "新对话":
            session.title = content[:20] + ("..." if len(content) > 20 else "")

    await db.commit()
    await db.refresh(msg)
    return msg.message_id


def build_llm_messages(history: list[ChatMessage], current_content: str) -> list[dict]:
    """构造发给 LLM 的上下文：system + 历史对话 + 当前用户消息"""
    messages: list[dict] = [
        {
            "role": "system",
            "content": (
                "你是微光职引智能求职系统中的求职助手。\n"
                "1. 回答必须基于工具返回的真实数据，逐条给出具体、可执行的分析与建议；\n"
                "2. 如果工具返回错误或没有获取到数据（例如认证失败、暂无简历画像），要如实告知用户原因，严禁编造分析结论或谎称已完成分析。"
            ),
        }
    ]
    for m in history:
        if m.role in ("user", "assistant"):
            messages.append({"role": m.role, "content": m.content})
    messages.append({"role": "user", "content": current_content})
    return messages
