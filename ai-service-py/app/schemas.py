from datetime import datetime

from pydantic import BaseModel, Field


class CreateSessionRequest(BaseModel):
    title: str | None = None
    user_id: str = "111"


class SessionItem(BaseModel):
    session_id: str
    title: str
    last_message_preview: str
    pinned: bool
    favorited: bool
    updated_at: datetime

    model_config = {"from_attributes": True}


class SessionListResponse(BaseModel):
    total: int
    list: list[SessionItem]


class SendMessageRequest(BaseModel):
    content: str = Field(..., min_length=1, description="用户输入的消息内容")


class UserMessageOut(BaseModel):
    message_id: str
    role: str
    content: str
    status: str
    created_at: datetime


class StreamConfigOut(BaseModel):
    protocol: str
    url: str


class SendMessageResponse(BaseModel):
    session_id: str
    user_message: UserMessageOut
    stream: StreamConfigOut
