import asyncio
import json
from datetime import datetime, timezone

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


def _now_iso() -> str:
    """返回带时区的 ISO 时间，前端 Date.parse 可直接解析（用于 trace 的 startedAt/finishedAt）"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _summarize_tool_result(result: str) -> str:
    """把工具返回的 JSON 摘要成一句话，用于前端 trace 步骤的结果展示"""
    try:
        data = json.loads(result)
    except Exception:
        return (result[:80] + "...") if len(result) > 80 else (result or "已返回结果")

    if isinstance(data, dict):
        if data.get("error"):
            return f"工具返回错误：{str(data['error'])[:60]}"
        skills = data.get("skills") or []
        basic = data.get("basicInfo") or {}
        name = basic.get("name") if isinstance(basic, dict) else None
        if name:
            return f"已获取 {name} 的简历画像（技能 {len(skills)} 项）"
        return "已获取简历画像数据"
    return "已获取简历数据"


def _extract_thinking(response) -> str:
    """提取模型在调用工具前可能附带的「预回答/思考」文本。

    - 普通模型：内容通常放在 response.content
    - 推理模型（如 deepseek-reasoner）：推理内容通常在 additional_kwargs["reasoning_content"]
    返回 str；没有则返回空字符串。
    """
    content = getattr(response, "content", None)
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                parts.append(str(block.get("text", "")))
            elif isinstance(block, str):
                parts.append(block)
        text = "".join(parts).strip()
    elif isinstance(content, str):
        text = content.strip()
    else:
        text = ""

    if text:
        return text

    ak = getattr(response, "additional_kwargs", None) or {}
    rc = ak.get("reasoning_content")
    return rc.strip() if isinstance(rc, str) else ""


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

        # ---------- agent trace 状态：供前端展示「思考过程 + 工具调用」 ----------
        started_at = _now_iso()
        trace_steps: list[dict] = []
        step_seq = 0

        def next_step_id() -> str:
            nonlocal step_seq
            step_seq += 1
            return f"step_{step_seq}"

        def build_trace(status: str = "processing", active_step_id: str | None = None, finished_at: str | None = None) -> dict:
            return {
                "traceVersion": "1.0",
                "mode": "chat",
                "status": status,
                "startedAt": started_at,
                "finishedAt": finished_at,
                "activeStepId": active_step_id,
                "steps": list(trace_steps),
            }

        def emit_trace(status: str = "processing", active_step_id: str | None = None, finished_at: str | None = None) -> str:
            return _sse("trace", {"type": "trace", "trace": build_trace(status, active_step_id, finished_at)})

        try:
            # 思考步骤：让前端立刻显示「思考中」面板
            think_id = next_step_id()
            trace_steps.append(
                {"stepId": think_id, "type": "thought", "title": "正在理解你的请求", "status": "succeeded"}
            )
            yield emit_trace()

            # ---------- 工具调用循环 ----------
            # 最多迭代 5 轮，防止模型反复调用工具陷入死循环
            for _ in range(5):
                response = await llm_with_tools.ainvoke(messages)
                tool_calls = response.tool_calls

                # 模型没有调用工具 → 退出循环，最终回答交给下面 astream 真实流式生成
                if not tool_calls:
                    break

                # 模型要求调用工具 → 追加 assistant 消息（含 tool_calls），逐个执行
                pre_answer = _extract_thinking(response)
                print(
                    f"[ai-service] 工具调用前内容 content={response.content!r} "
                    f"reasoning={getattr(response, 'additional_kwargs', {}).get('reasoning_content', '')!r}"
                )
                messages.append(
                    {
                        "role": "assistant",
                        "content": response.content or "",
                        "tool_calls": tool_calls,
                    }
                )

                # 若模型在调用工具前有「预回答」，先流式输出到思考面板，再显示工具调用
                if pre_answer.strip():
                    thought_step_id = next_step_id()
                    thought_step = {
                        "stepId": thought_step_id,
                        "type": "thought",
                        "title": "正在思考",
                        "status": "processing",
                        "detail": "",
                    }
                    trace_steps.append(thought_step)
                    yield emit_trace(active_step_id=thought_step_id)

                    # 按时间片合帧输出，避免每字符一条 SSE 事件导致前端高频渲染卡顿
                    accumulated = ""
                    frame_start = asyncio.get_event_loop().time()
                    FRAME_MS = 0.03
                    for ch in pre_answer:
                        accumulated += ch
                        now = asyncio.get_event_loop().time()
                        if now - frame_start >= FRAME_MS:
                            thought_step["detail"] = accumulated
                            yield emit_trace(active_step_id=thought_step_id)
                            frame_start = now
                            await asyncio.sleep(0.0)  # 让出事件循环，避免阻塞其他任务

                    if accumulated != thought_step["detail"]:
                        thought_step["detail"] = accumulated
                        yield emit_trace(active_step_id=thought_step_id)

                    thought_step["status"] = "succeeded"
                    yield emit_trace()

                for call in tool_calls:
                    tool_name = call["name"]
                    tool_inst = tools_by_name.get(tool_name)

                    # 新增「工具调用」步骤并通知前端（进行中）
                    tool_step_id = next_step_id()
                    tool_step = {
                        "stepId": tool_step_id,
                        "type": "tool",
                        "title": f"调用工具：{tool_name}",
                        "status": "processing",
                        "toolName": tool_name,
                        "toolParams": call["args"] or {},
                    }
                    trace_steps.append(tool_step)
                    yield emit_trace(active_step_id=tool_step_id)

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

                    # 更新工具步骤为完成，附结果摘要
                    tool_step["status"] = "succeeded"
                    tool_step["outputSummary"] = _summarize_tool_result(result)
                    yield emit_trace()

                    yield _sse("tool_result", {"tool": tool_name, "result": result})
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call["id"],
                            "content": result,
                        }
                    )

            # ---------- 真实流式输出最终回答（合帧缓冲）----------
            # 注意：此时 messages 末尾是工具结果（而非预生成的回答），astream 会据此逐 token 生成
            # 用 ~30ms 时间片把多个小 chunk 合并成一条事件，减少前端高频 DOM 渲染带来的卡顿
            loop = asyncio.get_event_loop()
            FRAME_MS = 0.03
            frame_start = loop.time()
            pending = ""
            async for chunk in get_llm().astream(messages):
                text = chunk.content or ""
                if not text:
                    continue
                pending += text
                full += text
                now = loop.time()
                if now - frame_start >= FRAME_MS:
                    yield _sse("delta", {"type": "delta", "delta": pending, "content": full})
                    pending = ""
                    frame_start = now
            if pending:
                yield _sse("delta", {"type": "delta", "delta": pending, "content": full})

            # 收尾：完整 trace 标记成功
            finished_at = _now_iso()
            yield emit_trace(status="succeeded", finished_at=finished_at)

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
                        "agentTrace": build_trace(status="succeeded", finished_at=finished_at),
                    },
                },
            )
        except Exception as e:
            # 出错时也把 trace 标成失败，前端能看到状态
            yield emit_trace(status="failed", finished_at=_now_iso())
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
