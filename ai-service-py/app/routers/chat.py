import json

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_session
from app.models import utcnow
from app.schemas import (
    ApiResponse,
    CreateSessionRequest,
    CreateSessionResponse,
    HomeMessage,
    MessageListResponse,
    SendMessageRequest,
    SendMessageResponse,
    SessionItem,
    SessionListResponse,
    StreamConfigOut,
)
from app.security import extract_token, get_current_user_id
from app.services import chat_service
from app.services.llm import get_llm, get_llm_with_tools
from app.tools import ALL_TOOLS

router = APIRouter(prefix="/users/me/home/assistant")


def _sse(name: str, data: dict) -> str:
    """把事件格式化成 SSE 协议文本（与前端约定的事件名一致）"""
    return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False, default=str)}\n\n"


# ===================== 会话 =====================

@router.post("/sessions", response_model=ApiResponse[CreateSessionResponse])
async def create_session(
    body: CreateSessionRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """创建对话会话（前端只传 scene/temperature，标题使用默认值）"""
    session = await chat_service.create_session(db, user_id)
    return ApiResponse(
        data=CreateSessionResponse(
            sessionId=session.session_id,
            createdAt=session.created_at,
        )
    )


@router.get("/sessions", response_model=ApiResponse[SessionListResponse])
async def get_sessions(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """获取用户会话列表（按置顶 + 最近更新排序）"""
    sessions = await chat_service.list_sessions(db, user_id)
    items = [SessionItem.model_validate(s) for s in sessions]
    return ApiResponse(data=SessionListResponse(total=len(items), list=items))


@router.get("/sessions/{session_id}/messages", response_model=ApiResponse[MessageListResponse])
async def get_messages(
    session_id: str,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """获取某个会话的全部消息"""
    session = await chat_service.get_session(db, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if session.user_id != user_id:
        raise HTTPException(status_code=403, detail="无权访问该会话")
    messages = await chat_service.list_messages(db, session_id)
    items = [HomeMessage.model_validate(m) for m in messages]
    return ApiResponse(
        data=MessageListResponse(sessionId=session_id, total=len(items), list=items)
    )


# ===================== 消息发送 + 流式回复 =====================

@router.post("/sessions/{session_id}/messages", response_model=ApiResponse[SendMessageResponse])
async def send_message(
    session_id: str,
    body: SendMessageRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """发送消息：保存用户消息并返回 SSE 流地址，前端随后连该地址收增量回复"""
    session = await chat_service.get_session(db, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if session.user_id != user_id:
        raise HTTPException(status_code=403, detail="无权访问该会话")

    message_id = chat_service.gen_id()
    user_msg = await chat_service.save_user_message(db, session_id, message_id, body.content)

    # 前端第 1286 行依赖 assistantMessage.messageId 发起 SSE 连接，这里先给占位对象
    assistant_placeholder = HomeMessage(
        message_id=chat_service.gen_id(),
        role="assistant",
        content="",
        status="processing",
        created_at=utcnow(),
    )

    return ApiResponse(
        data=SendMessageResponse(
            sessionId=session_id,
            userMessage=HomeMessage.model_validate(user_msg),
            assistantMessage=assistant_placeholder,
            stream=StreamConfigOut(
                protocol="sse",
                url=f"/users/me/home/assistant/sessions/{session_id}/messages/{message_id}/stream",
            ),
        )
    )


@router.get("/sessions/{session_id}/messages/{message_id}/stream")
async def stream_message(
    session_id: str,
    message_id: str,
    request: Request,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """SSE 流式回复：逐字推送 delta 事件，结束后自动保存助手消息。

    注意：SSE 连接前端用 EventSource（无法自定义请求头），token 通过 URL 查询参数传递，
    即 stream.url 需拼上 `?token=<JWT>`（与 Java 版一致）。
    """
    # 透传原始 JWT，供工具调用 Java 网关时附带 Authorization 头
    token = extract_token(request)

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

    # 绑定工具后的模型实例 + 工具查找表
    llm_with_tools = get_llm_with_tools(ALL_TOOLS)
    tools_by_name = {t.name: t for t in ALL_TOOLS}

    async def event_stream():
        yield _sse("start", {"type": "start", "messageId": message_id})
        full = ""
        try:
            # ---------- 工具调用循环 ----------
            # 最多迭代 5 轮，防止模型反复调用工具陷入死循环
            final_answer = None
            for _ in range(5):
                response = await llm_with_tools.ainvoke(messages)
                tool_calls = response.tool_calls

                # 模型没有调用工具 → 这就是最终回答，直接采用，避免二次生成导致内容跑偏
                if not tool_calls:
                    final_answer = response.content or ""
                    break

                # 模型要求调用工具 → 追加 assistant 消息（含 tool_calls），逐个执行
                messages.append(
                    {
                        "role": "assistant",
                        "content": response.content or "",
                        "tool_calls": tool_calls,
                    }
                )
                for call in tool_calls:
                    tool_name = call["name"]
                    tool_inst = tools_by_name.get(tool_name)

                    yield _sse("tool_call", {"tool": tool_name, "args": call["args"] or {}})

                    if tool_inst is None:
                        result = f"未知工具: {tool_name}"
                    else:
                        # 强制注入真实用户 id 与 token，覆盖模型可能传入的任何值（身份参数不可由模型决定）
                        tool_args = dict(call["args"] or {})
                        tool_args["user_id"] = user_id
                        tool_args["token"] = token or ""
                        try:
                            result = await tool_inst.ainvoke(tool_args)
                        except Exception as e:
                            result = f"工具执行失败: {e}"

                    yield _sse("tool_result", {"tool": tool_name, "result": result})
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call["id"],
                            "content": result,
                        }
                    )

            # ---------- 输出最终回答 ----------
            if final_answer is not None:
                # 工具循环内模型已给出最终回答，直接下发，不要再次生成
                full = final_answer
                yield _sse("delta", {"type": "delta", "delta": final_answer, "content": final_answer})
            else:
                # 兜底：5 轮内模型始终在调用工具（异常情况），再走一次纯流式生成
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
