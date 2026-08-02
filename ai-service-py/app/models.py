from datetime import datetime, timezone

from sqlalchemy import JSON, Boolean, DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


def utcnow() -> datetime:
    """SQLite 存 naive datetime，统一用 UTC"""
    return datetime.now(timezone.utc).replace(tzinfo=None)


class ChatSession(Base):
    """会话表，对应原 Java 服务的 chat_session"""

    __tablename__ = "chat_session"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    user_id: Mapped[str] = mapped_column(String(64), index=True, default="111")
    title: Mapped[str] = mapped_column(String(128), default="新对话")
    last_message_preview: Mapped[str] = mapped_column(Text, default="")
    pinned: Mapped[bool] = mapped_column(Boolean, default=False)
    favorited: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, onupdate=utcnow)


class ChatMessage(Base):
    """消息表，对应原 Java 服务的 chat_message"""

    __tablename__ = "chat_message"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    message_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    session_id: Mapped[str] = mapped_column(String(64), index=True)
    role: Mapped[str] = mapped_column(String(16))  # user / assistant
    content: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(16), default="succeeded")
    actions: Mapped[list] = mapped_column(JSON, default=list)
    agent_trace: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
