from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """统一响应外壳 {code, msg, data}，与前端 services/http.ts 约定一致"""

    code: int = 200
    msg: str = "success"
    data: T | None = None


# ===================== 会话 =====================

class CreateSessionRequest(BaseModel):
    """前端 createHomeSession 发送的请求体"""

    scene: str = "home"
    temperature: float = 0.3


class CreateSessionResponse(BaseModel):
    sessionId: str
    createdAt: datetime
    welcomeMessage: None = None


class SessionItem(BaseModel):
    """会话列表项（alias 输出驼峰，对齐前端 HomeSession）"""

    session_id: str = Field(alias="sessionId")
    title: str
    last_message_preview: str = Field(alias="lastMessagePreview")
    pinned: bool
    favorited: bool
    updated_at: datetime = Field(alias="updatedAt")

    model_config = {"from_attributes": True, "populate_by_name": True}


class SessionListResponse(BaseModel):
    total: int
    list: list[SessionItem]


# ===================== 消息 =====================

class FileContextIn(BaseModel):
    hasResumeFile: bool = False
    fileNames: list[str] = []


class SendMessageRequest(BaseModel):
    """前端 createHomeSessionMessage 发送的请求体（暂未用到的字段仅做兼容接收）"""

    content: str = Field(..., min_length=1, description="用户输入的消息内容")
    temperature: float = 0.4
    traceId: str | None = None
    fileContext: FileContextIn | None = None
    pageContext: dict | None = None
    executionMode: str = "auto"
    goal: str = ""
    taskPolicy: dict | None = None
    toolContext: dict | None = None


class HomeMessage(BaseModel):
    """聊天消息（alias 输出驼峰，对齐前端 HomeMessage）"""

    message_id: str = Field(alias="messageId")
    role: str
    content: str
    status: str
    created_at: datetime = Field(alias="createdAt")
    file_names: list[str] = Field(default=[], alias="fileNames")
    agent_trace: dict | None = Field(default=None, alias="agentTrace")
    actions: list[dict] = []
    task_result_card: dict | None = Field(default=None, alias="taskResultCard")

    model_config = {"from_attributes": True, "populate_by_name": True}


class MessageListResponse(BaseModel):
    sessionId: str
    total: int
    list: list[HomeMessage]


class StreamConfigOut(BaseModel):
    protocol: str
    url: str


class SendMessageResponse(BaseModel):
    sessionId: str
    userMessage: HomeMessage
    assistantMessage: HomeMessage
    stream: StreamConfigOut
