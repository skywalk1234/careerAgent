from datetime import datetime, timezone

from sqlalchemy import JSON, BigInteger, Boolean, DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


def utcnow() -> datetime:
    """MySQL DATETIME 存 naive datetime，统一用 UTC"""
    return datetime.now(timezone.utc).replace(tzinfo=None)


class ChatSession(Base):
    """会话表，对应 MySQL chat_history.sessions"""

    __tablename__ = "sessions"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    user_id: Mapped[int] = mapped_column(BigInteger, default=111)
    title: Mapped[str] = mapped_column(String(255), default="新对话")
    last_message_preview: Mapped[str] = mapped_column(Text, default="")
    pinned: Mapped[bool] = mapped_column("is_pinned", Boolean, default=False)
    favorited: Mapped[bool] = mapped_column("is_favorited", Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, onupdate=utcnow)


class ChatMessage(Base):
    """消息表，对应 MySQL chat_history.messages"""

    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    message_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    session_id: Mapped[str] = mapped_column(String(64), index=True)
    role: Mapped[str] = mapped_column(String(20))  # user / assistant
    content: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(20), default="succeeded")
    actions: Mapped[list] = mapped_column(JSON, default=list)
    agent_trace: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
