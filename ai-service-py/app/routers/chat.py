import json

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_session
from app.schemas import (
    CreateSessionRequest,
    SendMessageRequest,
    SendMessageResponse,
    SessionItem,
    SessionListResponse,
    StreamConfigOut,
    UserMessageOut,
)
from app.services import chat_service
from app.services.llm import get_llm

router = APIRouter(prefix="/users/me/home/assistant")


def _sse(name: str, data: dict) -> str:
    """把事件格式化成 SSE 协议文本（与前端约定的事件名一致）"""
    return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False, default=str)}\n\n"


# ===================== 会话 =====================

@router.post("/sessions", response_model=SessionItem)
async def create_session(
    body: CreateSessionRequest,
    db: AsyncSession = Depends(get_session),
):
    """创建对话会话"""
    session = await chat_service.create_session(db, body.user_id, body.title)
    return SessionItem.model_validate(session)


@router.get("/sessions", response_model=SessionListResponse)
async def get_sessions(
    user_id: str = "111",
    db: AsyncSession = Depends(get_session),
):
    """获取用户会话列表（按置顶 + 最近更新排序）"""
    sessions = await chat_service.list_sessions(db, user_id)
    items = [SessionItem.model_validate(s) for s in sessions]
    return SessionListResponse(total=len(items), list=items)


@router.get("/sessions/{session_id}/messages")
async def get_messages(
    session_id: str,
    db: AsyncSession = Depends(get_session),
):
    """获取某个会话的全部消息"""
    messages = await chat_service.list_messages(db, session_id)
    return {
        "session_id": session_id,
        "total": len(messages),
        "list": [
            {
                "message_id": m.message_id,
                "role": m.role,
                "content": m.content,
                "status": m.status,
                "created_at": m.created_at,
                "actions": m.actions,
                "agent_trace": m.agent_trace,
            }
            for m in messages
        ],
    }


# ===================== 消息发送 + 流式回复 =====================

@router.post("/sessions/{session_id}/messages", response_model=SendMessageResponse)
async def send_message(
    session_id: str,
    body: SendMessageRequest,
    db: AsyncSession = Depends(get_session),
):
    """发送消息：保存用户消息并返回 SSE 流地址，前端随后连该地址收增量回复"""
    session = await chat_service.get_session(db, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")

    message_id = chat_service.gen_id()
    user_msg = await chat_service.save_user_message(db, session_id, message_id, body.content)

    return SendMessageResponse(
        session_id=session_id,
        user_message=UserMessageOut(
            message_id=user_msg.message_id,
            role=user_msg.role,
            content=user_msg.content,
            status=user_msg.status,
            created_at=user_msg.created_at,
        ),
        stream=StreamConfigOut(
            protocol="sse",
            url=f"/users/me/home/assistant/sessions/{session_id}/messages/{message_id}/stream",
        ),
    )


@router.get("/sessions/{session_id}/messages/{message_id}/stream")
async def stream_message(
    session_id: str,
    message_id: str,
    db: AsyncSession = Depends(get_session),
):
    """SSE 流式回复：逐字推送 delta 事件，结束后自动保存助手消息"""
    if not settings.deepseek_api_key:
        raise HTTPException(
            status_code=500,
            detail="未配置 DEEPSEEK_API_KEY，请在项目根目录 .env 文件中填写（参考 .env.example）",
        )

    user_msg = await chat_service.get_message(db, message_id)
    if user_msg is None or user_msg.session_id != session_id or user_msg.role != "user":
        raise HTTPException(status_code=404, detail="消息不存在")

    history = [m for m in await chat_service.list_messages(db, session_id) if m.message_id != message_id]
    messages = chat_service.build_llm_messages(history, user_msg.content)

    async def event_stream():
        yield _sse("start", {"type": "start", "messageId": message_id})
        full = ""
        try:
            async for chunk in get_llm().astream(messages):
                text = chunk.content or ""
                if text:
                    full += text
                    yield _sse("delta", {"type": "delta", "delta": text, "content": full})

            await chat_service.save_assistant_message(db, session_id, full)
            yield _sse(
                "done",
                {
                    "type": "done",
                    "message": {
                        "messageId": message_id,
                        "role": "assistant",
                        "content": full,
                        "status": "succeeded",
                        "actions": [],
                        "agentTrace": {"status": "succeeded", "activeStepId": None},
                    },
                },
            )
        except Exception as e:
            yield _sse(
                "error",
                {"type": "error", "messageId": message_id, "error": f"AI处理出错: {e}"},
            )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # 关闭代理缓冲，保证流式实时性
        },
    )
