# -*- coding: utf-8 -*-
"""把人工整理的简历样例（简历片段 + 专家点评 few-shot）写入 pgvector resume_example_vector。

- 向量库：与 ai-service-py config.py 一致（默认真实库 8.147.71.59:40086/ai-vector，
  可用环境变量 VECTOR_DATABASE_URL 覆盖）
- 表结构/字段规范见 fc2026/resume_example_vector写入规范.md；
  metadata 键对齐 app/services/resume_example.py 的 _to_reference。
- 每条样例 = content(仅简历片段) + metadata.comment(点评，绝不参与 embedding)。
- 幂等判重：按 (postId, content) 判重，已存在则跳过。
- 用法：
    python scripts/import_resume_examples.py --dry-run   # 只生成向量并预检，不写入
    python scripts/import_resume_examples.py             # 写入向量库
"""

import argparse
import asyncio
import json
import sys
import uuid
from pathlib import Path

import asyncpg
from pgvector.asyncpg import register_vector

# 保证从任意 cwd 都能 import 到 app 包
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import settings  # noqa: E402
from app.services.embedding import embed_text, truncate_for_embedding  # noqa: E402

TABLE = "resume_example_vector"

# ---------------------------------------------------------------- 样例数据
# 同一份简历（同一来源帖）可拆多条，各挂对应点评。
# 注意：postId/sourceUrl 目前为 demo 占位，拿到真实帖子后按 metadata->>'postId' UPDATE 补全。
ENTRIES = [
    {
        "content": (
            "【项目】XiaoBa-CLI | Local Coding Agent Runtime & Evaluation Harness（2026.01 - 至今）\n"
            "技术栈：TypeScript、Python、Electron、Node.js\n"
            "● Runtime 层：设计并实现多轮 Agent 状态机与 ToolManager，内置 read/write/edit/glob/grep/shell 等本地项目工具，"
            "并支持角色工具注入、会话隔离、模型调用、工具执行、异常恢复、产物记录和用户可见消息交付，"
            "支撑长任务、多工具调用与本地 coding / 企业 IM 等场景下的稳定交付；\n"
            "● 评测与回放层：构建本地 trace 证据与 Agent 回归评测体系，记录用户输入、工具调用、文件产物、异常恢复与最终交付；"
            "支持从历史 trace 抽取 replay case，重新驱动当前 runtime 生成 fresh trace，并通过 verifier / scorecard 做长期回归；\n"
            "● 协作层：实现 subagent 任务调度模块，支持父子会话隔离、异步执行、状态查询、执行日志、产物列表与完成后结果回注主会话，"
            "用于长任务拆解、多 Agent 协作与结果汇总；\n"
            "● Role/Skill 层：设计 Runtime / Role / Skill 三层扩展模型：Runtime 作为统一执行底座，负责状态、工具、观测与交付；"
            "Role 作为可运行的 Agent App，定义任务边界、系统提示词、可用 Skills 与产物规范；"
            "Skill 作为可复用能力包，通过 SKILL.md + formatter 完成注册、递归发现、动态加载与匹配；\n"
            "● 产品化落地：打通飞书、微信、CatsCompany 三类消息入口，并基于 Electron 封装桌面端与可视化 Dashboard，"
            "支持机器人服务管理、运行状态查看与本地化部署；已在 3 家合作企业小规模部署，覆盖约 30-60 名内部用户，"
            "并沉淀 3 类 Roles 和 20+ 垂直行业 Skills。"
        ),
        "metadata": {
            "jobCategory": "Agent开发",
            "snippetType": "project",
            "comment": (
                "这份 Coding Agent 项目仍有堆技术栈的老毛病：Runtime 层与协作层意义不大，属于行业基础操作，可略写，看不出水平高低。"
                "真正的亮点在「评测与回放层」：trace 证据系统能显著提升长程工作和强推理任务的质量（谷歌也发过论文佐证）；"
                "回放层类似案例设计，符合 AI 开发的最小改动原则，能保证任务驱动能力——这和套壳项目不是一个维度。"
                "Role/Skill 层稍显套路化。最亮眼的是产品化落地：不与 Codex 正面竞争，而是做垂类 Roles 与 Skills、"
                "走专家知识沉淀路线，巧妙避开了与主流 Coding 工具的正面竞争。"
            ),
            "postId": "xhs_demo_20260902_a1",
            "postTitle": "（占位）AI Agent 简历点评：Coding Agent 与长期记忆两个项目",
            "sourceUrl": "internal://demo/xhs_demo_20260902_a1",
            "curated": True,
        },
    },
    {
        "content": (
            "【项目】GauzMem | Long-term Memory & Hybrid Retrieval System（2025.11 - 2026.02）\n"
            "技术栈：Python、Neo4j、Qdrant、MySQL、DashScope\n"
            "● 设计面向长对话 Agent 的长期记忆系统，将对话事实异步沉淀为知识图谱与向量索引，"
            "支持人物、事件、时间线和因果关系的长期召回；\n"
            "● 设计 Facts / Topics 双层记忆模型，抽取原子事实并建模因果、时序等 8 类关系，"
            "通过主题聚合降低长对话事实分散带来的检索噪声；\n"
            "● 构建「向量召回 + 图谱多跳扩展 + 时序上下文补全」的混合检索链路，"
            "用于支持跨轮事实追踪、多跳问答与因果链推理。"
        ),
        "metadata": {
            "jobCategory": "RAG/大模型应用",
            "snippetType": "project",
            "comment": (
                "这个项目不是纯 RAG，但写 RAG 完全可以参考：长期记忆与 RAG 面临的都是同一个「召回」问题，手段高度一致——"
                "如果你的 RAG 能从静态检索升级成动态更新，就能包装成「长期记忆/自进化」。"
                "它采用的「图谱+向量」双路思路值得借鉴：一个建逻辑、一个建语义，虽然增加了搜索成本，但有明确的设计思考，"
                "包括后续的关系建立，比干巴巴套开源库强得多，而且实现复杂度并不算高。"
            ),
            "postId": "xhs_demo_20260902_a1",
            "postTitle": "（占位）AI Agent 简历点评：Coding Agent 与长期记忆两个项目",
            "sourceUrl": "internal://demo/xhs_demo_20260902_a1",
            "curated": True,
        },
    },
]


async def entry_exists(conn, content: str, metadata: dict) -> bool:
    """幂等判重：同帖同片段不重复入库。"""
    post_id = metadata.get("postId")
    if not post_id:
        return False
    return bool(await conn.fetchval(
        f"SELECT 1 FROM {TABLE} WHERE metadata->>'postId' = $1 AND content = $2 LIMIT 1",
        post_id, content,
    ))


async def run(dry_run: bool) -> None:
    pool = await asyncpg.create_pool(
        settings.vector_database_url, min_size=1, max_size=2, init=register_vector
    )
    try:
        for i, entry in enumerate(ENTRIES, 1):
            content = entry["content"]
            metadata = entry["metadata"]
            truncated = len(content) > settings.embedding_max_chars
            embed_text_ = truncate_for_embedding(content)

            print(f"\n=== [{i}/{len(ENTRIES)}] {metadata['snippetType']} / {metadata['jobCategory']} ===")
            print(f"content 长度: {len(content)}" + ("（已截断到2000）" if truncated else ""))
            print(f"content 前120字: {content[:120].replace(chr(10), ' | ')}")

            embedding = await embed_text(embed_text_)
            print(f"嵌入完成: {len(embedding)} 维")

            if dry_run:
                print("[dry-run] metadata:", json.dumps(metadata, ensure_ascii=False)[:400])
                continue

            async with pool.acquire() as conn:
                if await entry_exists(conn, content, metadata):
                    print(f"[skip] {TABLE} 已存在同帖同片段（postId={metadata.get('postId')}），跳过")
                else:
                    await conn.execute(
                        f"INSERT INTO {TABLE} (id, content, metadata, embedding) VALUES ($1, $2, $3, $4)",
                        str(uuid.uuid4()),
                        content,
                        json.dumps(metadata, ensure_ascii=False),
                        embedding,
                    )
                    print(f"[ok] 已写入 {TABLE}: postId={metadata.get('postId')}")

        async with pool.acquire() as conn:
            total = await conn.fetchval(f"SELECT count(*) FROM {TABLE}")
            print(f"\n{TABLE} 当前总数: {total}")

        if dry_run:
            print("[dry-run] 未执行任何写入，确认无误后去掉 --dry-run 再运行")
    finally:
        await pool.close()


def main():
    parser = argparse.ArgumentParser(description="导入简历样例到 resume_example_vector")
    parser.add_argument("--dry-run", action="store_true", help="只构建数据并生成嵌入，不写入")
    args = parser.parse_args()
    asyncio.run(run(args.dry_run))


if __name__ == "__main__":
    main()
