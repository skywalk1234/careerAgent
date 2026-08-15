import json
from dataclasses import dataclass

import asyncpg
from pgvector.asyncpg import register_vector

from app.config import settings


# 仅允许这两张内部常量表，杜绝 SQL 注入
TABLE_JOB_CATEGORY = "job_category_vector"
TABLE_JOB_DETAIL = "job_detail_vector"


@dataclass
class SimilarityRow:
    id: str
    content: str
    metadata: dict
    similarity: float


async def create_pg_pool() -> asyncpg.Pool:
    """创建 pgvector 连接池。register_vector 作为 init 钩子，
    为每条连接注册 vector 编解码（embedding 参数以 Python list 传入）。"""
    return await asyncpg.create_pool(
        settings.vector_database_url,
        min_size=1,
        max_size=5,
        init=register_vector,
    )


async def similarity_search(
    pool: asyncpg.Pool,
    table: str,
    embedding: list[float],
    top_k: int,
    threshold: float,
) -> list[SimilarityRow]:
    """cosine 相似度检索，语义对齐 Spring AI PgVectorStore
    （COSINE_DISTANCE: distance <= 1 - threshold，即 similarity >= threshold）。

    表名仅限内部常量 TABLE_JOB_CATEGORY / TABLE_JOB_DETAIL。
    """
    if table not in (TABLE_JOB_CATEGORY, TABLE_JOB_DETAIL):
        raise ValueError(f"非法向量表名: {table}")

    sql = (
        "SELECT id, content, metadata, "
        "1 - (embedding <=> $1::vector) AS similarity "
        f"FROM {table} "
        "WHERE 1 - (embedding <=> $1::vector) >= $2 "
        "ORDER BY embedding <=> $1::vector "
        "LIMIT $3"
    )
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, embedding, threshold, top_k)

    return [
        SimilarityRow(
            id=r["id"],
            content=r["content"],
            metadata=json.loads(r["metadata"]),  # metadata 列是 json 类型，asyncpg 返回 str
            similarity=r["similarity"],
        )
        for r in rows
    ]
