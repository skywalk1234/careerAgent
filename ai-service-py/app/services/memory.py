"""长期记忆 · 情景记忆抽取与写入（M1，设计见 fc2026/长期记忆方案.md）。

每轮问答落库后由 chat.py 后台触发（asyncio.create_task，不阻塞 SSE）：
  读会话最近若干条消息（MySQL chat_history）          → transcript
  读该用户近端活跃 episodes（pgvector user_episodic_memory） → 已有记忆，作去重/supersede 参照
  调抽取 LLM（get_json_llm：deepseek flash + thinking disabled）→ {episodes:[...]}
  逐条 embed_text(content) → INSERT；supersedeOf → 在旧行填 superseded_by_id

口径（方案 §四）：用户陈述是唯一事实源，助手回答只是消解指代的上下文；
模型单方建议、用户未认可的不存；episode 必须能溯源到用户「说过/确认过/应允过」。
本模块不持有状态；pg 连接池由调用方从 app.state 传入。
"""

import asyncio
import json
import time

from sqlalchemy import select

from app.config import settings
from app.database import async_session
from app.models import ChatMessage
from app.services.embedding import embed_text, truncate_for_embedding
from app.services.llm import get_json_llm

# 情景记忆表（与 fc2026/长期记忆方案.md 的建表 DDL 一致）
MEMORY_TABLE = "user_episodic_memory"

# category 枚举（表列 VARCHAR(32)，DDL 注释同此）
CATEGORIES = ("learning_progress", "project", "goal", "self_assessment", "general")

EXTRACT_SYSTEM = """你是一名「长期记忆抽取器」，服务于大学生求职规划助手。系统需要从对话中沉淀用户的关键信息，作为长期记忆。

【输入内容】
- 【本次会话消息】：最近一段对话（用户与助手消息，按时间顺序）。助手消息只是帮助理解上下文的参考，不是事实来源。
- 【用户已有记忆】：该用户此前已沉淀的记忆条目，每条有 id。用于两点：避免重复沉淀同一件事；判断新进展是否取代旧记忆。

【抽取规则】
1. 事实来源：只抽取能溯源到「用户说过 / 确认过 / 应允过」的信息。助手自己给出的建议、评价、推测，除非用户明确认可，否则一律不存。
2. 类别（category）只用以下枚举：
   - learning_progress：学习进展 / 技能掌握程度（如「正在学 Redis，卡在集群」「已学完 Spring Cloud」）
   - project：项目 / 实习 / 经历进展（如「电商项目做到订单模块」）
   - goal：用户确定的目标、或用户认可的下步计划（如「确定秋招投 Java 后端，先按计划学完 Kafka」）
   - self_assessment：自评短板 / 卡点 / 犹豫 / 偏好（如「Redis 只会缓存那块，集群不会」「在纠结考研还是工作」）
   - general：其他对长期了解用户有价值的事实
3. content 要求：1~3 句，自包含、无悬空指代（对话里的「那块 / 就是这个」要消解成具体所指）；保留状态与时间感（正在学 / 已学完 / 刚开始）；不含寒暄与无关闲聊。
4. 去重与更新：
   - 若某条事实在【用户已有记忆】中已存在且没有新进展，不要重复输出。
   - 若与某条已有记忆是「同一件事的新进展 / 更新」（例如旧记忆「刚开始学 Redis 集群」→ 现在「已学完 Redis 集群」），在该 episode 的 supersedeOf 填那条已有记忆的 id（取代它）。
   - 无法对应到具体已有记忆 id 的新事实，supersedeOf 填 null。
5. importance：0~1 的小数，表示「对未来职业规划 / 面试辅导的价值」。明确具体的学习阶段、目标、短板价值更高。
6. 绝不虚构、不推断用户没说的内容。若本段对话没有值得沉淀的信息，返回空数组。

只输出 JSON：{"episodes": [{"content": "...", "category": "...", "importance": 0.8, "supersedeOf": "已有记忆id或null"}]}"""

# 抽取 LLM 单次硬超时。get_json_llm 本身 timeout=150，这里再加 wait_for 兜底，
# 保证后台任务不会无限挂起（对齐 resume_polish._call_json_llm 的做法）。
_LLM_TIMEOUT = 90


def _extract_json(text: str) -> dict:
    """解析模型输出的 JSON，容忍 ```json ... ``` 围栏"""
    text = (text or "").strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return json.loads(text)


async def _load_recent_messages(session_id: str, limit: int) -> list[dict]:
    """读会话最近 limit 条 user/assistant 消息（MySQL chat_history），按时间升序返回"""
    async with async_session() as db:
        result = await db.execute(
            select(ChatMessage)
            .where(ChatMessage.session_id == session_id)
            .order_by(ChatMessage.created_at.desc())
            .limit(limit)
        )
        rows = list(result.scalars().all())
    rows.reverse()  # 倒序取回 → 再翻回升序，保证最新在前、输出仍按时间线
    return [
        {"role": m.role, "content": m.content or ""}
        for m in rows
        if m.role in ("user", "assistant")
    ]


async def _load_active_episodes(pool, user_id: int, limit: int) -> list[dict]:
    """取该用户最近的活跃 episodes（superseded_by_id IS NULL），供去重/supersede 参照。
    表不存在时抛异常，由 extract_session_memory_async 统一兜底日志。"""
    sql = (
        f"SELECT id, category, content, created_at FROM {MEMORY_TABLE} "
        "WHERE user_id = $1 AND superseded_by_id IS NULL "
        "ORDER BY created_at DESC LIMIT $2"
    )
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, user_id, limit)
    return [
        {
            "id": str(r["id"]),
            "category": r["category"],
            "content": r["content"],
            "created_at": r["created_at"].isoformat() if r["created_at"] else "",
        }
        for r in rows
    ]


async def _call_extract_llm(transcript: list[dict], existing: list[dict]) -> dict:
    """一次抽取 LLM 调用 → {episodes:[...]}。解析失败抛异常由上层兜底。"""
    lines = [f"{'用户' if m['role'] == 'user' else '助手'}: {m['content']}" for m in transcript]
    parts = [
        "【用户已有记忆】",
        json.dumps(existing, ensure_ascii=False) if existing else "（暂无）",
        "",
        "【本次会话消息】",
        "\n\n".join(lines),
        "",
        "请按系统指令输出 JSON。",
    ]
    messages = [
        {"role": "system", "content": EXTRACT_SYSTEM},
        {"role": "user", "content": "\n".join(parts)},
    ]
    resp = await asyncio.wait_for(get_json_llm().ainvoke(messages), timeout=_LLM_TIMEOUT)
    content = resp.content
    if isinstance(content, list):  # 防御：个别 provider 返回 block 列表
        content = "".join(b.get("text", "") for b in content if isinstance(b, dict))
    return _extract_json(content or "")


async def _write_episodes(pool, user_id: int, session_id: str, episodes: list[dict]) -> int:
    """逐条 embed + INSERT；supersedeOf → 在该旧行填 superseded_by_id（仅当该行仍活跃）。
    单条失败只记日志不中断整批。返回成功写入条数。"""
    if not episodes:
        return 0
    insert_sql = (
        f"INSERT INTO {MEMORY_TABLE} "
        "(user_id, session_id, category, content, importance, embedding) "
        "VALUES ($1, $2, $3, $4, $5, $6::vector) RETURNING id"
    )
    update_sql = (
        f"UPDATE {MEMORY_TABLE} SET superseded_by_id = $1 "
        "WHERE id = $2 AND user_id = $3 AND superseded_by_id IS NULL"
    )
    written = 0
    async with pool.acquire() as conn:
        for ep in episodes:
            try:
                content = str(ep.get("content") or "").strip()
                if not content:
                    continue
                category = str(ep.get("category") or "general")
                if category not in CATEGORIES:
                    category = "general"
                try:
                    importance = float(ep.get("importance") or 0.5)
                except (TypeError, ValueError):
                    importance = 0.5
                importance = max(0.0, min(1.0, importance))

                vector = await embed_text(truncate_for_embedding(content))
                new_id = await conn.fetchval(
                    insert_sql, user_id, session_id, category, content, importance, vector
                )
                written += 1

                supersede = str(ep.get("supersedeOf") or "").strip()
                if supersede:
                    # 仅当目标行仍活跃才取代；已被别人取代/不存在的行则忽略，防并发双写竞态
                    await conn.execute(update_sql, new_id, supersede, user_id)
            except Exception as e:
                print(f"[memory] 单条 episode 写入失败(忽略): {e}", flush=True)
    return written


async def extract_session_memory_async(user_id: int, session_id: str, pool) -> None:
    """后台入口：一轮问答落库后调用。任何失败只记日志，绝不影响主对话 / SSE。"""
    t0 = time.perf_counter()
    if pool is None:
        print(f"[memory] 跳过记忆抽取：pgvector 池不可用 (user={user_id}, session={session_id})", flush=True)
        return
    try:
        transcript = await _load_recent_messages(session_id, settings.memory_max_transcript_messages)
        if not transcript:
            return
        existing = await _load_active_episodes(pool, user_id, settings.memory_max_reference_episodes)
        result = await _call_extract_llm(transcript, existing)
        episodes = result.get("episodes") or []
        if not isinstance(episodes, list):
            episodes = []
        written = await _write_episodes(pool, user_id, session_id, episodes)
        print(
            f"[memory] 抽取完成 user={user_id} session={session_id} "
            f"新写 {written}/{len(episodes)} 条，已有参照 {len(existing)} 条，"
            f"耗时 {time.perf_counter() - t0:.2f}s",
            flush=True,
        )
    except asyncio.TimeoutError:
        print(f"[memory] 抽取 LLM 超时（>{_LLM_TIMEOUT}s）user={user_id}, session={session_id}", flush=True)
    except Exception as e:
        print(f"[memory] 抽取失败(忽略): {e}", flush=True)
