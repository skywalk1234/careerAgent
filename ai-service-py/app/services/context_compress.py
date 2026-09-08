"""会话上下文自动压缩 · 后台折叠（M，设计见 fc2026/上下文压缩方案.md §四~§七）。

每轮回复结束后由 chat.py 后台触发（asyncio.create_task，不阻塞 SSE）：
  读 sessions.context_summary / compressed_count + 本会话消息（MySQL chat_history）
  按方案 4.3 计算折叠边界；需要则调摘要 LLM（get_summary_llm）把最老一段对话合并进滚动摘要
  回写 sessions.context_summary，并推进 compressed_count

口径（方案 §二/§三）：
- 触发信号 = API 每次返回的真实 prompt_tokens（由调用方在收尾处判断阈值后再调本模块）；
- 折叠只前移、不倒退；保留最近 N 条原文（CONTEXT_COMPRESS_KEEP_RECENT）；
- 压缩动作异步、幂等，任何失败只记日志，绝不影响主对话 / SSE。
本模块不持有状态；模块级 _compressing 集合防同一会话并发重入。
"""

import asyncio
import time

from sqlalchemy import select

from app.config import settings
from app.database import async_session
from app.models import ChatMessage, ChatSession
from app.services.llm import get_summary_llm

# 摘要 LLM 单次硬超时。get_summary_llm 本身 timeout=150，这里再加 wait_for 兜底，
# 保证后台任务不会无限挂起（对齐 memory.py / resume_polish 的做法）。
_LLM_TIMEOUT = 150

# 同一会话正在压缩时，新触发的压缩直接跳过（连续两轮都过阈值时不会叠任务）
_compressing: set[str] = set()

SUMMARIZE_SYSTEM = """你是「对话摘要器」，服务于大学生求职规划助手。你需要把一段较早的对话合并进会话摘要，供后续轮次作背景参考。

【输入】
- 【已有摘要】：之前折叠轮次的摘要（可能为空，表示首次压缩）
- 【本轮待折叠对话】：按时间顺序的 user / assistant 消息原文

【规则】
1. 输出是对已有摘要 + 待折叠对话的合并结果，必须自包含、无悬空指代；
2. 优先保留：用户的目标/决定/承诺、项目与学习进展、技术栈与掌握程度、已给出并应长期参考的关键结论；
3. 助手早期的寒暄、过程性输出、重复解释可删除；保留每件事的「状态与时间感」（刚开始/已学完/正在做）；
4. 以精简的要点式中文输出，长度控制在几百 token 内，不逐条复述；
5. 不要输出 JSON，只输出压缩后的摘要文本。"""


async def summarize_context(existing_summary: str | None, rows: list[ChatMessage]) -> str:
    """调摘要 LLM，把已有摘要 + 待折叠对话合并成新摘要。解析/空结果抛异常由上层兜底。"""
    lines = []
    for m in rows:
        role_label = "用户" if m.role == "user" else "助手"
        lines.append(f"{role_label}: {m.content or ''}")
    parts = [
        "【已有摘要】",
        (existing_summary or "").strip() or "（暂无——首次压缩）",
        "",
        "【本轮待折叠对话】",
        "\n\n".join(lines),
        "",
        "请把【已有摘要】与【本轮待折叠对话】合并成一份压缩后的摘要，只输出该摘要文本，不要输出 JSON。",
    ]
    messages = [
        {"role": "system", "content": SUMMARIZE_SYSTEM},
        {"role": "user", "content": "\n".join(parts)},
    ]
    resp = await asyncio.wait_for(
        get_summary_llm().ainvoke(messages),
        timeout=_LLM_TIMEOUT,
    )
    content = resp.content
    if isinstance(content, list):  # 防御：个别 provider 返回 block 列表
        content = "".join(b.get("text", "") for b in content if isinstance(b, dict))
    text = str(content or "").strip()
    if not text:
        raise ValueError("摘要 LLM 返回空内容")
    try:  # token 使用量（拿得到就记，用于核对压缩收益，不影响主流程）
        um = getattr(resp, "usage_metadata", None) or {}
        print(
            f"[compress] 摘要生成 input={um.get('input_tokens')} output={um.get('output_tokens')} "
            f"total={um.get('total_tokens')}",
            flush=True,
        )
    except Exception:
        pass
    return text


def _compute_boundary(total: int, compressed: int) -> int | None:
    """按方案 4.3 算本次折叠边界；无新增可折叠时返回 None。"""
    candidate = total - settings.context_compress_keep_recent
    boundary = max(compressed, candidate)
    boundary = min(boundary, total)  # 防御：永不越过现有消息数
    if boundary - compressed < settings.context_compress_min_new:
        return None
    return boundary


async def maybe_compress(user_id: int, session_id: str) -> None:
    """后台入口：一轮回复落库后调用（fire-and-forget）。任何失败只记日志，绝不影响 SSE。"""
    if not settings.context_compress_enabled:
        return
    if session_id in _compressing:
        print(f"[compress] 跳过：会话 {session_id} 压缩进行中", flush=True)
        return
    t0 = time.perf_counter()
    _compressing.add(session_id)
    try:
        # 阶段一：读当前状态与消息（读完即关会话，不长时间持有 DB 事务）
        async with async_session() as db:
            session = (
                await db.execute(
                    select(ChatSession).where(ChatSession.session_id == session_id)
                )
            ).scalar_one_or_none()
            if session is None:
                print(f"[compress] 会话不存在 session={session_id}", flush=True)
                return
            if session.user_id != user_id:
                print(
                    f"[compress] user 不匹配跳过 session={session_id} "
                    f"owner={session.user_id} caller={user_id}",
                    flush=True,
                )
                return
            existing_summary = session.context_summary
            compressed = session.compressed_count or 0
            rows = list(
                (
                    await db.execute(
                        select(ChatMessage)
                        .where(ChatMessage.session_id == session_id)
                        .order_by(ChatMessage.created_at.asc())
                    )
                ).scalars().all()
            )

        total = len(rows)
        boundary = _compute_boundary(total, compressed)
        if boundary is None:
            print(
                f"[compress] 无需压缩 session={session_id} "
                f"total={total} compressed={compressed} "
                f"(需新增 ≥ {settings.context_compress_min_new})",
                flush=True,
            )
            return
        fold_rows = [m for m in rows[compressed:boundary] if m.role in ("user", "assistant")]
        if not fold_rows:
            return

        new_summary = await summarize_context(existing_summary, fold_rows)

        # 阶段二：重读会话，仅当边界未被其它任务推进时才回写（幂等、只前移）
        async with async_session() as db:
            session = (
                await db.execute(
                    select(ChatSession).where(ChatSession.session_id == session_id)
                )
            ).scalar_one_or_none()
            if session is None:
                print(f"[compress] 回写前会话消失 session={session_id}", flush=True)
                return
            cur = session.compressed_count or 0
            if boundary <= cur:
                print(
                    f"[compress] 边界已被推进到 {cur}，本次 {boundary} 放弃回写 "
                    f"session={session_id}",
                    flush=True,
                )
                return
            session.context_summary = new_summary
            session.compressed_count = boundary
            await db.commit()

        print(
            f"[compress] 完成 session={session_id} 折叠 {compressed}→{boundary} "
            f"（本次 {boundary - compressed} 条），耗时 {time.perf_counter() - t0:.2f}s",
            flush=True,
        )
    except asyncio.TimeoutError:
        print(
            f"[compress] 摘要 LLM 超时（>{_LLM_TIMEOUT}s）user={user_id} session={session_id}",
            flush=True,
        )
    except Exception as e:
        print(f"[compress] 压缩失败(忽略): {e}", flush=True)
    finally:
        _compressing.discard(session_id)
