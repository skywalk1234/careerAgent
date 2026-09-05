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


# ===================== 模拟面试（面试官 agent，独立会话，见 fc2026/模拟面试专家方案.md） =====================

INTERVIEW_TYPES = ("technical", "behavior", "mixed")


class InterviewCreateRequest(BaseModel):
    """开场建会话：jobId / profileId 由用户在开场时提供（页面带入或对话里给），可不全，缺哪个开场由面试官索取"""

    jobId: str | None = None
    profileId: str | None = None
    type: str = "mixed"  # technical / behavior / mixed


class InterviewSessionItem(BaseModel):
    """面试会话列表项（alias 输出驼峰）"""

    session_id: str = Field(alias="sessionId")
    type: str
    status: str
    job_id: str | None = Field(default=None, alias="jobId")
    job_title: str | None = Field(default=None, alias="jobTitle")
    company_name: str | None = Field(default=None, alias="companyName")
    profile_id: str | None = Field(default=None, alias="profileId")
    report_id: int | None = Field(default=None, alias="reportId")
    created_at: datetime = Field(alias="createdAt")
    ended_at: datetime | None = Field(default=None, alias="endedAt")

    model_config = {"from_attributes": True, "populate_by_name": True}


class InterviewSessionListResponse(BaseModel):
    total: int
    list: list[InterviewSessionItem]


class InterviewCreateResponse(BaseModel):
    sessionId: str
    type: str
    status: str
    createdAt: datetime
    stream: StreamConfigOut


class InterviewSendRequest(BaseModel):
    content: str = Field(..., min_length=1, description="用户本轮回答")


class InterviewMessageItem(BaseModel):
    """面试问答消息（alias 输出驼峰）"""

    message_id: str = Field(alias="messageId")
    role: str
    content: str
    status: str
    created_at: datetime = Field(alias="createdAt")

    model_config = {"from_attributes": True, "populate_by_name": True}


class InterviewMessageListResponse(BaseModel):
    sessionId: str
    total: int
    list: list[InterviewMessageItem]


class InterviewSendResponse(BaseModel):
    sessionId: str
    userMessage: InterviewMessageItem
    stream: StreamConfigOut
