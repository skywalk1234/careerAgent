from datetime import datetime, timezone

from sqlalchemy import JSON, BigInteger, Boolean, DateTime, Index, Integer, String, Text
from sqlalchemy.dialects.mysql import MEDIUMTEXT
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
    # 上下文压缩：已折叠的最早一段对话的滚动摘要 + 已折叠消息条数（见 fc2026/上下文压缩方案.md）
    # ⚠️ 这两列需手动 DDL（附录 A），create_all 不会给已存在的表加列
    context_summary: Mapped[str | None] = mapped_column(MEDIUMTEXT, default=None)
    compressed_count: Mapped[int] = mapped_column(Integer, default=0)
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


class InterviewSession(Base):
    """模拟面试会话表，对应 MySQL chat_history.interview_sessions。

    设计见 fc2026/模拟面试专家方案.md §3.1：与主对话 sessions 隔离；一场面试 = 一次记录，
    材料（简历/JD）在开场按用户给的 id 拉取后快照进 material，之后的提问与报告都锚定这份快照。
    status: active（进行中）→ ended（已出报告）。
    """

    __tablename__ = "interview_sessions"
    __table_args__ = (Index("idx_interview_user_time", "user_id", "created_at"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    user_id: Mapped[int] = mapped_column(BigInteger)  # 硬隔离
    type: Mapped[str] = mapped_column("type", String(20), default="mixed")  # technical / behavior / mixed
    job_id: Mapped[str | None] = mapped_column(String(64), default=None)  # 意向岗位 id（开场用户提供后回填）
    job_title: Mapped[str | None] = mapped_column(String(200), default=None)  # 岗位名快照（列表展示/防删）
    company_name: Mapped[str | None] = mapped_column(String(200), default=None)  # 公司名快照
    profile_id: Mapped[str | None] = mapped_column(String(64), default=None)  # 简历 id（开场用户提供后回填）
    material: Mapped[dict] = mapped_column(JSON, default=dict)  # {"resume": md 全文, "job": JD dict 快照}
    report_id: Mapped[int | None] = mapped_column(BigInteger, default=None)  # careers.user_interview_reports.id
    status: Mapped[str] = mapped_column(String(20), default="active")  # active / ended
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, onupdate=utcnow)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime, default=None)


class InterviewMessage(Base):
    """模拟面试一问一答原文表，对应 MySQL chat_history.interview_messages。

    与主对话 messages 表隔离；报告生成工具读本表全量原文（真实一问一答）交给独立报告 LLM。
    """

    __tablename__ = "interview_messages"
    __table_args__ = (Index("idx_interview_msg_session", "session_id", "created_at"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    message_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    session_id: Mapped[str] = mapped_column(String(64))
    role: Mapped[str] = mapped_column(String(16))  # user / assistant
    content: Mapped[str] = mapped_column(MEDIUMTEXT)  # 问答原文可能较长，用 MEDIUMTEXT
    status: Mapped[str] = mapped_column(String(20), default="succeeded")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
