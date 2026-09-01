import asyncio
import json
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_session
from app.models import ChatMessage, utcnow
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
from app.services import chat_service, resume_polish
from app.services.flow import clear_flow, get_flow, set_flow
from app.services.llm import get_llm, get_llm_with_tools
from app.tools import ALL_TOOLS

router = APIRouter(prefix="/users/me/home/assistant")

# ===================== 简历润色流程（服务端状态机 + 自由文本问答）=====================

# gather 阶段最多问答轮数，超出强制转 ready，防止无限追问
GATHER_MAX_ROUNDS = resume_polish.GATHER_MAX_ROUNDS

# ready / done 状态下的确认与取消判定（取消优先级高于确认，如「不保存」含「保存」）
_CONFIRM_WORDS = ("开始", "改吧", "保存", "确认", "就按这个", "开始改")
_CANCEL_WORDS = ("算了", "取消", "不保存", "不用了", "放弃", "不要了", "先不了")
# done 状态下「另存为新简历」的意图词（覆盖原简历之外的第二种保存方式）
_SAVE_AS_NEW_WORDS = ("另存", "新建", "新简历", "复制一份")

# 润色流程的选项卡选项：label 是前端展示文案，send 是点击后作为用户消息发送的文本
# （send 文本走 _match_confirm / _SAVE_AS_NEW_WORDS 关键词判定，前端只发文本、不接管状态逻辑）
_FLOW_OPTIONS = {
    "ready": [
        {"key": "start", "label": "开始润色", "send": "开始改吧"},
        {"key": "abandon", "label": "放弃润色", "send": "放弃"},
        {"key": "supplement", "label": "补充其他信息", "send": ""},
    ],
    "done": [
        {"key": "overwrite", "label": "覆盖当前简历", "send": "保存"},
        {"key": "save_as_new", "label": "保存到新的简历", "send": "另存为一份新简历"},
        {"key": "discard", "label": "不保存", "send": "不保存"},
        {"key": "supplement", "label": "补充其他信息", "send": ""},
    ],
}


def _match_confirm(text: str) -> str | None:
    """判断用户消息是确认、取消还是其他。取消优先于确认。"""
    t = text.strip()
    if any(w in t for w in _CANCEL_WORDS):
        return "cancel"
    if any(w in t for w in _CONFIRM_WORDS):
        return "confirm"
    return None


async def _sse_chunks(text: str):
    """把一段文本按 ~30ms 时间片切块，产出 (delta, 累计content)，避免高频 DOM 渲染卡顿"""
    loop = asyncio.get_event_loop()
    frame_start = loop.time()
    pending = ""
    accumulated = ""
    for ch in text:
        pending += ch
        accumulated += ch
        now = loop.time()
        if now - frame_start >= 0.03:
            yield pending, accumulated
            pending = ""
            frame_start = now
    if pending:
        yield pending, accumulated


def _format_polish_result(result: dict) -> str:
    """把 polish 结果整理成展示文本（修订稿 + 变更点 + 保存确认提示）"""
    lines = ["简历润色完成，下面是修订稿：", "", str(result.get("revisedContent") or "")]
    changes = result.get("changes") or []
    if changes:
        lines += ["", "主要变更："]
        for i, c in enumerate(changes, 1):
            reason = c.get("reason") if isinstance(c, dict) else str(c)
            lines.append(f"{i}. {reason}")
    lines += ["", "确认没问题的话，回复「保存」覆盖原简历，或回复「另存」保存为新简历；回复「不保存」放弃。"]
    return "\n".join(lines)


async def _run_polish_flow(
    session_id: str,
    token: str,
    user_msg: ChatMessage,
    redis_client,
    flow: dict,
) -> tuple[str, str | None]:
    """润色流程的状态分支处理。

    仅处理「状态非 idle」的消息；gathering 期间完全不跑主 LLM 循环。
    返回 (text, display_state)：text 是要流式输出并保存的助手文本；
    display_state 是该消息处理后的流程状态，供前端渲染选项卡：ready → 开始润色/放弃/补充，
    done → 覆盖/另存/不保存/补充，其余状态（gathering 提问、polishing、已结束）为 None。
    """
    state = flow.get("state")
    job = flow.get("job") or {}
    resume = flow.get("resume") or ""
    history = flow.get("history") or []
    print(f"[resume-flow] _run_polish_flow 进入，当前状态: {state}，问答轮次: {len(history)}")

    if state == "gathering":
        # 本条消息视为对上一批问题的回答
        history = history + [{"question": flow.get("last_question") or "", "answer": user_msg.content}]
        if len(history) >= GATHER_MAX_ROUNDS:
            signal = {"next": "ready", "text": "信息已经收集得比较充分了，可以开始修改简历。要现在开始吗？"}
        else:
            signal = await resume_polish.gather(job, resume, history)
        if signal["next"] == "abandon":
            print(f"[resume-flow] gathering → 放弃（abandon）：{signal['text'][:50]!r}")
            await clear_flow(redis_client, session_id)
            return signal["text"], None
        flow["history"] = history
        if signal["next"] == "ready":
            print(f"[resume-flow] gathering → ready（信息充足，问答 {len(history)} 轮）")
            flow["state"] = "ready"
            flow["last_question"] = None
        else:
            print(f"[resume-flow] gathering → gathering（继续追问，问答 {len(history)} 轮）")
            flow["state"] = "gathering"
            flow["last_question"] = signal["text"]
        await set_flow(redis_client, session_id, flow)
        return signal["text"], flow["state"] if flow["state"] == "ready" else None

    if state == "ready":
        verdict = _match_confirm(user_msg.content)
        if verdict == "cancel":
            print(f"[resume-flow] ready → 取消，清空状态回到 idle")
            await clear_flow(redis_client, session_id)
            return "好的，已取消简历润色，回到正常对话。", None
        if verdict == "confirm":
            print(f"[resume-flow] ready → polishing（用户确认开始改）")
            # 先落 polishing 状态再执行（防止并发消息看到旧状态），生成完成后转 done
            flow["state"] = "polishing"
            await set_flow(redis_client, session_id, flow)
            try:
                result = await resume_polish.polish(job, resume, history)
            except Exception as e:
                await clear_flow(redis_client, session_id)
                return f"生成修订稿时出错：{e}，已退出简历润色流程。", None
            print(f"[resume-flow] polishing → done（修订稿生成完成）")
            flow["state"] = "done"
            flow["revised"] = result
            await set_flow(redis_client, session_id, flow)
            return _format_polish_result(result), "done"
        # 既非确认也非取消 → 当作补充信息，回到 gathering 再分析一次
        print(f"[resume-flow] ready → 补充信息，回到 gathering 再分析")
        history = history + [{"question": flow.get("last_question") or "（补充说明）", "answer": user_msg.content}]
        flow["history"] = history
        signal = await resume_polish.gather(job, resume, history)
        if signal["next"] == "abandon":
            print(f"[resume-flow] ready → 放弃（abandon）：{signal['text'][:50]!r}")
            await clear_flow(redis_client, session_id)
            return signal["text"], None
        if signal["next"] == "ready":
            print(f"[resume-flow] gathering → ready（补充信息后仍可开始）")
            flow["state"] = "ready"
            flow["last_question"] = None
        else:
            print(f"[resume-flow] gathering → gathering（继续追问）")
            flow["state"] = "gathering"
            flow["last_question"] = signal["text"]
        await set_flow(redis_client, session_id, flow)
        return signal["text"], flow["state"] if flow["state"] == "ready" else None

    if state == "polishing":
        print(f"[resume-flow] polishing 进行中，收到新消息（保持状态不变）")
        return "修订稿正在生成中，请稍候片刻再继续。", None

    if state == "done":
        is_save_as_new = any(w in user_msg.content for w in _SAVE_AS_NEW_WORDS)
        verdict = _match_confirm(user_msg.content)
        if verdict == "cancel":
            print(f"[resume-flow] done → 未保存，清空状态回到 idle")
            await clear_flow(redis_client, session_id)
            return "好的，未保存修改，已退出简历润色。", None
        if verdict == "confirm" or is_save_as_new:
            try:
                revised_content = (flow.get("revised") or {}).get("revisedContent") or ""
                if is_save_as_new:
                    await resume_polish.save_profile_as_new(revised_content, token)
                    text = "已另存为一份新简历，原简历保持不变，评分任务已触发。可以继续问我其他问题～"
                else:
                    await resume_polish.save_profile(flow.get("profileId") or "", revised_content, token)
                    text = "已保存到简历，评分任务已触发。可以继续问我其他问题～"
            except Exception as e:
                text = f"保存失败：{e}"
            print(
                f"[resume-flow] done → {'另存为新简历' if is_save_as_new else '覆盖原简历'}，"
                f"清空状态回到 idle（profileId={flow.get('profileId')!r}）"
            )
            await clear_flow(redis_client, session_id)
            return text, None
        # 未匹配任何操作 → 提示用户从选项卡选择，状态保持 done，前端仍展示保存选项
        return "未识别你的操作，请从下方选项中选择，或直接补充其他信息。", "done"

    # 未知状态兜底：清掉状态，回到正常对话
    print(f"[resume-flow] 未知状态 {state!r}，清空状态回到 idle")
    await clear_flow(redis_client, session_id)
    return "简历润色流程状态异常，已回到正常对话。", None


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
        # 本流结束后前端应展示的润色流程选项卡状态（ready/done），其余为 None
        resume_flow_state = None

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

            # ---------- 简历润色流程接管（状态非 idle 时，本消息直接由服务端状态机处理）----------
            # gathering 期间完全不跑主 LLM 循环；文本通过现有 delta 事件普通文字流出，前端零改动
            redis_client = getattr(request.app.state, "redis", None)
            flow = await get_flow(redis_client, session_id) if redis_client else None
            flow_state = (flow or {}).get("state")
            print(
                f"[resume-flow] 会话 {session_id} 本轮消息: {user_msg.content[:60]!r}，"
                f"当前状态: {flow_state}"
            )

            if flow_state and flow_state != "idle":
                flow_text, flow_display = await _run_polish_flow(session_id, token or "", user_msg, redis_client, flow)
                async for delta, content in _sse_chunks(flow_text):
                    yield _sse("delta", {"type": "delta", "delta": delta, "content": content})
                full = flow_text
                resume_flow_state = flow_display
                finished_at = _now_iso()
                yield emit_trace(status="succeeded", finished_at=finished_at)
                await chat_service.save_assistant_message(db, session_id, full)
                done_message = {
                    "messageId": message_id,
                    "role": "assistant",
                    "content": full,
                    "status": "succeeded",
                    "actions": [],
                    "agentTrace": build_trace(status="succeeded", finished_at=finished_at),
                }
                if resume_flow_state:
                    done_message["resumeFlow"] = {
                        "state": resume_flow_state,
                        "options": _FLOW_OPTIONS.get(resume_flow_state) or [],
                    }
                yield _sse("done", {"type": "done", "message": done_message})
                return

            # ---------- 工具调用循环 ----------
            # 最多迭代 5 轮，防止模型反复调用工具陷入死循环
            entry_triggered = False
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
                        # 强制注入真实用户 id / token / request / session_id，覆盖模型可能传入的任何值（身份/上下文参数不可由模型决定）
                        tool_args = dict(call["args"] or {})
                        tool_args["user_id"] = user_id
                        tool_args["token"] = token or ""
                        tool_args["request"] = request
                        tool_args["session_id"] = session_id
                        try:
                            result = await tool_inst.ainvoke(tool_args)
                        except Exception as e:
                            result = f"工具执行失败: {e}"

                    # 入口工具特殊处理：成功时返回的 text 直接流给用户并短路跳出，不让主 LLM 复述
                    if tool_name == "start_resume_polish":
                        try:
                            parsed_entry = json.loads(result) if isinstance(result, str) else None
                        except Exception:
                            parsed_entry = None
                        if isinstance(parsed_entry, dict) and parsed_entry.get("flow") == "resume_polish":
                            tool_step["status"] = "succeeded"
                            tool_step["outputSummary"] = "进入简历润色流程"
                            yield emit_trace()
                            yield _sse("tool_result", {"tool": tool_name, "result": result})
                            entry_text = str(parsed_entry.get("text") or "")
                            if entry_text:
                                async for delta, content in _sse_chunks(entry_text):
                                    yield _sse("delta", {"type": "delta", "delta": delta, "content": content})
                                full = entry_text
                            entry_triggered = True
                            # 入口工具首次差距分析即判定信息充足（next=ready）→ 前端展示 ready 选项卡
                            resume_flow_state = "ready" if parsed_entry.get("next") == "ready" else None
                            break
                        # 失败（缺参 / Redis 不可用 / 拉取失败）→ 落入通用路径，让主 LLM 基于错误信息回复

                    # 更新工具步骤为完成，附结果摘要（通用路径）
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

                if entry_triggered:
                    break

            if not entry_triggered:
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
            done_message = {
                "messageId": message_id,
                "role": "assistant",
                "content": full,
                "status": "succeeded",
                "actions": [],
                "agentTrace": build_trace(status="succeeded", finished_at=finished_at),
            }
            if resume_flow_state:
                done_message["resumeFlow"] = {
                    "state": resume_flow_state,
                    "options": _FLOW_OPTIONS.get(resume_flow_state) or [],
                }
            yield _sse("done", {"type": "done", "message": done_message})
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
