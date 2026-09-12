"""模拟面试专家 · 会话型接口（前端 aiHttp 直连 8086）。

设计见 fc2026/模拟面试专家方案.md §2：与主对话助理（chat.py）同款 SSE + 工具循环骨架
（事件名与字段完全一致：start/trace/tool_call/tool_result/delta/done/error，前端可复用现有
SSE 解析与气泡渲染），但绑定**面试官主 LLM**：专属人设 prompt + 专属工具集 INTERVIEW_TOOLS
（get_student_profile / query_job_detail / submit_mock_interview_report），问答原文落
interview_messages 表（与主对话 messages 隔离）。

接口：
- POST   /users/me/interview/sessions                      建会话（jobId/profileId 由用户在开场时提供）
- GET    /users/me/interview/sessions                      面试会话列表
- GET    /users/me/interview/sessions/{sid}/start          开场 SSE（索取/按 id 拉材料 → 快照 → 开场白 + 第 1 问）
- POST   /users/me/interview/sessions/{sid}/messages       发用户回答 → 返回 SSE 流地址
- GET    /users/me/interview/sessions/{sid}/messages/{messageId}/stream  面试官 SSE（下一问 / 结束出报告）
- GET    /users/me/interview/sessions/{sid}/messages       本场问答回看

会话生命周期 active → ended（生成报告后）；ended 后拒绝再发消息。
"""
import asyncio
import json
import re
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_session
from app.schemas import (
    ApiResponse,
    INTERVIEW_TYPES,
    InterviewCreateRequest,
    InterviewCreateResponse,
    InterviewMessageItem,
    InterviewMessageListResponse,
    InterviewSendRequest,
    InterviewSendResponse,
    InterviewSessionItem,
    InterviewSessionListResponse,
    StreamConfigOut,
)
from app.security import extract_token, get_current_user_id
from app.services import interview
from app.services.llm import get_llm, get_llm_with_tools
from app.tools import INTERVIEW_TOOLS

router = APIRouter(prefix="/users/me/interview")


def _sse(name: str, data: dict) -> str:
    """把事件格式化成 SSE 协议文本（事件名与 chat.py 约定一致）"""
    return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False, default=str)}\n\n"


def _now_iso() -> str:
    """带时区的 ISO 时间，前端 Date.parse 可直接解析（trace 的 startedAt/finishedAt 用）"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _extract_thinking(response) -> str:
    """提取模型在调用工具前可能附带的「预回答/思考」文本（对齐 chat.py）"""
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


# 用户明确表达「结束面试」的口径；命中后本轮系统 prompt 追加结束信号，强制面试官收尾出报告
_END_HINTS = re.compile(
    r"(结束面试|面试结束|结束这场|结束吧|不面了|不想(?:再)?面了|出报告|出总结|收尾"
    r"|就到这里|到此为止|生成(?:面试)?报告|面试(?:总结|小结)|总结(?:一下)?.{0,3}面试)"
)


def _trim(content: str, limit: int = 3000) -> str:
    """超长消息在喂给模型时截断（落库仍是全量原文），防单条长文本撑爆上下文"""
    if content and len(content) > limit:
        return content[:limit] + f"\n…（内容过长已截断，共 {len(content)} 字）"
    return content or ""


# ===================== 会话 =====================

@router.post("/sessions", response_model=ApiResponse[InterviewCreateResponse])
async def create_session(
    body: InterviewCreateRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """建一场模拟面试。jobId/profileId 缺省为空，由用户在开场时提供（页面带入或对话里给）。"""
    interview_type = (body.type or "mixed").lower()
    if interview_type not in INTERVIEW_TYPES:
        raise HTTPException(status_code=400, detail="type 仅支持 technical / behavior / mixed")

    session = await interview.create_session(
        db, user_id, interview_type, body.jobId, body.profileId
    )
    return ApiResponse(
        data=InterviewCreateResponse(
            sessionId=session.session_id,
            type=session.type,
            status=session.status,
            createdAt=session.created_at,
            stream=StreamConfigOut(
                protocol="sse",
                url=f"/users/me/interview/sessions/{session.session_id}/start",
            ),
        )
    )


@router.get("/sessions", response_model=ApiResponse[InterviewSessionListResponse])
async def list_sessions(
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """面试会话列表（按创建时间倒序）"""
    sessions = await interview.list_sessions(db, user_id)
    items = [InterviewSessionItem.model_validate(s) for s in sessions]
    return ApiResponse(data=InterviewSessionListResponse(total=len(items), list=items))


async def _get_owned_session(db: AsyncSession, session_id: str, user_id: int):
    """查会话并校验归属；不存在 404、非本人 403"""
    session = await interview.get_session(db, session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="面试会话不存在")
    if int(session.user_id) != int(user_id):
        raise HTTPException(status_code=403, detail="无权访问该面试会话")
    return session


# ===================== 开场 SSE =====================

@router.get("/sessions/{session_id}/start")
async def start_interview(
    session_id: str,
    request: Request,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """开场 SSE：面试官确认/索取用户提供的简历 id + 岗位 id → 按 id 调工具拉材料（快照注入）
    → 材料就绪后输出开场白 + 第 1 问。开场白落库（interview_messages，assistant），不伪造用户消息。
    """
    session = await _get_owned_session(db, session_id, user_id)
    if session.status != "active":
        raise HTTPException(status_code=400, detail="本场面试已结束，无法再次开场")
    existing = await interview.list_messages(db, session_id)
    if existing:
        raise HTTPException(status_code=400, detail="本场面试已开场，请直接发送消息继续")

    if not settings.deepseek_api_key:
        raise HTTPException(
            status_code=500,
            detail="未配置 DEEPSEEK_API_KEY，请在项目根目录 .env 文件中填写（参考 .env.example）",
        )

    token = extract_token(request)
    resume_text, _job_text, _job = interview.load_material(session)

    # 开场是「材料索取/拉取 + 第 1 问」这一特殊轮：用不落库的合成指令充当 user 消息
    synthetic_user = (
        "【开场指令（系统合成，请按流程开始本场面试，本消息不落库）】\n"
        f"- 面试类型：{session.type}（{interview.type_label(session.type)}）\n"
        f"- 用户已在开场时提供：简历 id = {session.profile_id or '（未提供）'}；"
        f"岗位 id = {session.job_id or '（未提供）'}\n"
        "请按下面流程推进：\n"
        "1. 若缺简历 id 或岗位 id：先向用户索取（明确说明需要的格式，如 profileId:xxx / jobId:xxx），"
        "**本轮不要开始正式提问**；用户下一轮提供后再拉材料开问。\n"
        "2. 若已提供：调用 get_student_profile / query_job_detail 按用户给的 id 拉取简历与岗位 JD"
        "（服务端会自动把结果保存为面试材料快照）。\n"
        "3. 材料就绪后：用 1~2 句话做面试官开场（身份 + 流程提示，如「我会围绕你的简历和意向岗位提问，"
        "大概 8 题左右，准备好我们就开始」），然后**只提出第 1 个问题**。\n"
        "不要输出与以上无关的长篇说明。"
    )
    system_content = interview.build_interviewer_system(
        session.type, resume_text, "", session.profile_id, session.job_id,
        asked=0, max_questions=settings.interview_max_questions,
    )
    messages = [
        {"role": "system", "content": system_content},
        {"role": "user", "content": synthetic_user},
    ]
    llm_with_tools = get_llm_with_tools(INTERVIEW_TOOLS)
    tools_by_name = {t.name: t for t in INTERVIEW_TOOLS}

    async def event_stream():
        yield _sse("start", {"type": "start"})
        full = ""
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
                "mode": "interview",
                "status": status,
                "startedAt": started_at,
                "finishedAt": finished_at,
                "activeStepId": active_step_id,
                "steps": list(trace_steps),
            }

        def emit_trace(status: str = "processing", active_step_id: str | None = None, finished_at: str | None = None) -> str:
            return _sse("trace", {"type": "trace", "trace": build_trace(status, active_step_id, finished_at)})

        try:
            think_id = next_step_id()
            trace_steps.append(
                {"stepId": think_id, "type": "thought", "title": "正在确认面试材料", "status": "succeeded"}
            )
            yield emit_trace()

            # 工具循环（同 chat.py）：开场最多 5 轮工具迭代，供面试官拉材料
            for _ in range(5):
                response = await llm_with_tools.ainvoke(messages)
                tool_calls = response.tool_calls
                if not tool_calls:
                    break

                pre_answer = _extract_thinking(response)
                messages.append(
                    {
                        "role": "assistant",
                        "content": response.content or "",
                        "tool_calls": tool_calls,
                    }
                )

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
                            await asyncio.sleep(0.0)

                    if accumulated != thought_step["detail"]:
                        thought_step["detail"] = accumulated
                        yield emit_trace(active_step_id=thought_step_id)

                    thought_step["status"] = "succeeded"
                    yield emit_trace()

                for call in tool_calls:
                    tool_name = call["name"]
                    tool_inst = tools_by_name.get(tool_name)

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
                        # 身份/上下文参数强制注入真实值，覆盖模型可能传入的任何值
                        tool_args = dict(call["args"] or {})
                        tool_args["user_id"] = user_id
                        tool_args["token"] = token or ""
                        tool_args["request"] = request
                        tool_args["session_id"] = session_id
                        try:
                            result = await tool_inst.ainvoke(tool_args)
                        except Exception as e:
                            result = f"工具执行失败: {e}"

                    # 拉取简历/JD 成功后服务端把材料快照进会话（本轮后续提问与报告都锚定这份材料）
                    try:
                        await interview.capture_snapshot(db, session_id, tool_name, tool_args, result)
                    except Exception as e:
                        print(f"[interview] 材料快照失败(忽略): {e}", flush=True)

                    tool_step["status"] = "succeeded"
                    tool_step["outputSummary"] = interview.summarize_tool_result(result)
                    yield emit_trace()
                    yield _sse("tool_result", {"tool": tool_name, "result": result})
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call["id"],
                            "content": result,
                        }
                    )

            # 真实流式输出开场白 + 第 1 问（合帧缓冲，同 chat.py）
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

            finished_at = _now_iso()
            yield emit_trace(status="succeeded", finished_at=finished_at)

            # 开场白落库为第一条 assistant 消息（材料索取轮 / 材料就绪轮都落）
            opening = await interview.save_interview_message(db, session_id, "assistant", full)
            done_message = {
                "messageId": opening.message_id,
                "role": "assistant",
                "content": full,
                "status": "succeeded",
                "actions": [],
                "agentTrace": build_trace(status="succeeded", finished_at=finished_at),
            }
            yield _sse("done", {"type": "done", "message": done_message})
        except Exception as e:
            yield emit_trace(status="failed", finished_at=_now_iso())
            yield _sse(
                "error",
                {"type": "error", "sessionId": session_id, "error": f"AI处理出错: {e}"},
            )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


# ===================== 逐轮问答 =====================

@router.post("/sessions/{session_id}/messages", response_model=ApiResponse[InterviewSendResponse])
async def send_message(
    session_id: str,
    body: InterviewSendRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """发用户回答：落库 interview_messages 并返回 SSE 流地址，前端随后连该地址收面试官回复"""
    session = await _get_owned_session(db, session_id, user_id)
    if session.status != "active":
        raise HTTPException(status_code=400, detail="本场面试已结束并生成报告，无法继续提问")

    user_msg = await interview.save_interview_message(db, session_id, "user", body.content)
    return ApiResponse(
        data=InterviewSendResponse(
            sessionId=session_id,
            userMessage=InterviewMessageItem.model_validate(user_msg),
            stream=StreamConfigOut(
                protocol="sse",
                url=f"/users/me/interview/sessions/{session_id}/messages/{user_msg.message_id}/stream",
            ),
        )
    )


@router.get("/sessions/{session_id}/messages", response_model=ApiResponse[InterviewMessageListResponse])
async def get_messages(
    session_id: str,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_session),
):
    """本场问答回看（一问一答原文）"""
    session = await _get_owned_session(db, session_id, user_id)
    messages = await interview.list_messages(db, session_id)
    items = [InterviewMessageItem.model_validate(m) for m in messages]
    return ApiResponse(
        data=InterviewMessageListResponse(
            sessionId=session.session_id, total=len(items), list=items
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
    """面试官 SSE：正常 = 下一问；用户结束 / 已问满上限 = 调报告工具 → 收尾语 → 会话 ended。

    注意：SSE 连接前端用 EventSource（无法自定义请求头），token 通过 URL 查询参数传递，
    即 stream.url 需拼上 `?token=<JWT>`（与 chat.py 一致）。
    """
    if not settings.deepseek_api_key:
        raise HTTPException(
            status_code=500,
            detail="未配置 DEEPSEEK_API_KEY，请在项目根目录 .env 文件中填写（参考 .env.example）",
        )

    session = await _get_owned_session(db, session_id, user_id)
    if session.status != "active":
        raise HTTPException(status_code=400, detail="本场面试已结束并生成报告，无法继续提问")

    token = extract_token(request)
    all_messages = await interview.list_messages(db, session_id)
    user_msg = next((m for m in all_messages if m.message_id == message_id), None)
    if user_msg is None or user_msg.role != "user":
        raise HTTPException(status_code=404, detail="消息不存在")

    # 历史 = 除本条外的全部原文（本条作为本轮 user 消息最后注入）
    history = [m for m in all_messages if m.message_id != message_id]
    asked = sum(1 for m in history if m.role == "assistant")
    max_questions = settings.interview_max_questions
    resume_text, job_text, _job = interview.load_material(session)

    system_content = interview.build_interviewer_system(
        session.type, resume_text, job_text,
        session.profile_id, session.job_id, asked=asked, max_questions=max_questions,
    )
    # 服务端兜底：硬上限（已问满）与用户明确结束意图，都把「收尾出报告」写进本轮 system，约束模型行为
    force_finish = asked >= max_questions
    end_intent = bool(_END_HINTS.search(user_msg.content or ""))
    if force_finish:
        system_content += (
            f"\n\n【系统硬性要求（本场提问已达上限 {max_questions} 题）】\n"
            "本轮禁止再提出新问题：先针对用户本条消息给一句简短反馈（若有内容可反馈），"
            "然后**必须调用 submit_mock_interview_report** 生成面试总结报告并结束本场面试。"
            "不要做长篇总结，报告正文已由工具生成。"
        )
    elif end_intent and asked > 0:
        system_content += (
            "\n\n【系统提示（用户表达了结束面试的意愿）】\n"
            "请不要再继续提问或追问，本轮**必须调用 submit_mock_interview_report** "
            "结束面试并生成报告；收到工具结果后向用户转述报告标题与核心结论，提示已保存可回看。"
        )

    messages = [
        {"role": "system", "content": system_content},
    ]
    for m in history:
        messages.append({"role": m.role, "content": _trim(m.content)})
    messages.append({"role": "user", "content": _trim(user_msg.content)})

    llm_with_tools = get_llm_with_tools(INTERVIEW_TOOLS)
    tools_by_name = {t.name: t for t in INTERVIEW_TOOLS}

    async def event_stream():
        yield _sse("start", {"type": "start", "messageId": message_id})
        full = ""
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
                "mode": "interview",
                "status": status,
                "startedAt": started_at,
                "finishedAt": finished_at,
                "activeStepId": active_step_id,
                "steps": list(trace_steps),
            }

        def emit_trace(status: str = "processing", active_step_id: str | None = None, finished_at: str | None = None) -> str:
            return _sse("trace", {"type": "trace", "trace": build_trace(status, active_step_id, finished_at)})

        try:
            think_id = next_step_id()
            trace_steps.append(
                {"stepId": think_id, "type": "thought", "title": "正在理解你的回答", "status": "succeeded"}
            )
            yield emit_trace()

            # ---------- 工具调用循环（对齐 chat.py）----------
            # 最多迭代 5 轮，防止模型反复调用工具陷入死循环
            report_tool_called = False
            for _ in range(5):
                response = await llm_with_tools.ainvoke(messages)
                tool_calls = response.tool_calls
                if not tool_calls:
                    break

                pre_answer = _extract_thinking(response)
                print(
                    f"[interview] 工具调用前内容 content={response.content!r} "
                    f"reasoning={getattr(response, 'additional_kwargs', {}).get('reasoning_content', '')!r}"
                )
                messages.append(
                    {
                        "role": "assistant",
                        "content": response.content or "",
                        "tool_calls": tool_calls,
                    }
                )

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
                            await asyncio.sleep(0.0)

                    if accumulated != thought_step["detail"]:
                        thought_step["detail"] = accumulated
                        yield emit_trace(active_step_id=thought_step_id)

                    thought_step["status"] = "succeeded"
                    yield emit_trace()

                for call in tool_calls:
                    tool_name = call["name"]
                    tool_inst = tools_by_name.get(tool_name)

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
                        tool_args = dict(call["args"] or {})
                        tool_args["user_id"] = user_id
                        tool_args["token"] = token or ""
                        tool_args["request"] = request
                        tool_args["session_id"] = session_id
                        try:
                            result = await tool_inst.ainvoke(tool_args)
                        except Exception as e:
                            result = f"工具执行失败: {e}"

                    # 换基准：面试官重取简历/JD → 服务端覆盖材料快照，后续提问/报告锚定新材料
                    try:
                        await interview.capture_snapshot(db, session_id, tool_name, tool_args, result)
                    except Exception as e:
                        print(f"[interview] 材料快照失败(忽略): {e}", flush=True)

                    if tool_name == "submit_mock_interview_report":
                        report_tool_called = True

                    tool_step["status"] = "succeeded"
                    tool_step["outputSummary"] = interview.summarize_tool_result(result)
                    yield emit_trace()
                    yield _sse("tool_result", {"tool": tool_name, "result": result})
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call["id"],
                            "content": result,
                        }
                    )

            # ---------- 真实流式输出最终回答（合帧缓冲，对齐 chat.py）----------
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

            finished_at = _now_iso()
            yield emit_trace(status="succeeded", finished_at=finished_at)

            # 面试官本条回复落库；若本轮调过报告工具，会话已由工具内部置 ended（这里打印留痕便于排查）
            await interview.save_interview_message(db, session_id, "assistant", full)
            if report_tool_called:
                refreshed = await interview.get_session(db, session_id)
                if refreshed is not None and refreshed.status == "ended":
                    print(
                        f"[interview] 本场面试已结束: session_id={session_id}, report_id={refreshed.report_id}",
                        flush=True,
                    )

            done_message = {
                "messageId": message_id,
                "role": "assistant",
                "content": full,
                "status": "succeeded",
                "actions": [],
                "agentTrace": build_trace(status="succeeded", finished_at=finished_at),
            }
            yield _sse("done", {"type": "done", "message": done_message})
        except Exception as e:
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
            "X-Accel-Buffering": "no",
        },
    )
