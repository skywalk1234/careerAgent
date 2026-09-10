import httpx

from app.config import settings


def truncate_for_embedding(text: str, max_chars: int | None = None) -> str:
    """text-embedding-v1 单次输入有上限，超长时防御性截断查询文本。
    Java 侧不截断直接嵌，这里仅作兜底，不改变业务逻辑。"""
    limit = max_chars or settings.embedding_max_chars
    return text if len(text) <= limit else text[:limit]


async def embed_text(
    text: str,
    *,
    model: str | None = None,
    dimensions: int | None = None,
    text_type: str | None = None,
) -> list[float]:
    """DashScope OpenAI 兼容 embedding 接口。
    默认用 text-embedding-v1（1536 维）才能与库内既有向量比对；DeepSeek 无 embedding API。

    model / dimensions / text_type 仅在显式传入时覆盖默认值（现有调用点行为不变）。
    text_type 取 "document"（写库的文档侧）或 "query"（检索的查询侧）；不传等价于 query。
    """
    url = f"{settings.dashscope_base_url.rstrip('/')}/embeddings"
    headers = {
        "Authorization": f"Bearer {settings.dashscope_api_key}",
        "Content-Type": "application/json",
    }
    body = {"model": model or settings.embedding_model, "input": text}
    if dimensions:
        body["dimensions"] = dimensions
    if text_type:
        body["text_type"] = text_type
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(url, json=body, headers=headers)
        resp.raise_for_status()  # 400/401 → 抛错，由业务函数兜底
        data = resp.json()
    try:
        return data["data"][0]["embedding"]
    except (KeyError, IndexError, TypeError) as e:
        raise RuntimeError(f"DashScope embedding 响应格式异常: {data}") from e


# ==================== job_detail_vector 专用（qwen3.7-text-embedding / 1536 维）====================
# 文档侧（爬虫写 content）与查询侧（RAG 岗位推荐）必须同模型同空间，两边都走下面两个封装。

async def embed_job_content(text: str) -> list[float]:
    """岗位 JD 入库向量化（文档侧）→ text_type=document。"""
    return await embed_text(
        truncate_for_embedding(text, settings.job_embedding_max_chars),
        model=settings.job_embedding_model,
        dimensions=settings.job_embedding_dimensions,
        text_type="document",
    )


async def embed_job_query(text: str) -> list[float]:
    """岗位向量检索的查询向量（查询侧）→ text_type=query。"""
    return await embed_text(
        truncate_for_embedding(text, settings.job_embedding_max_chars),
        model=settings.job_embedding_model,
        dimensions=settings.job_embedding_dimensions,
        text_type="query",
    )
