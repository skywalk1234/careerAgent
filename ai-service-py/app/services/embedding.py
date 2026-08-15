import httpx

from app.config import settings


def truncate_for_embedding(text: str, max_chars: int | None = None) -> str:
    """text-embedding-v1 单次输入有上限，超长时防御性截断查询文本。
    Java 侧不截断直接嵌，这里仅作兜底，不改变业务逻辑。"""
    limit = max_chars or settings.embedding_max_chars
    return text if len(text) <= limit else text[:limit]


async def embed_text(text: str) -> list[float]:
    """DashScope OpenAI 兼容 embedding 接口。
    必须用 text-embedding-v1（1536 维）才能与库内向量比对；DeepSeek 无 embedding API。
    """
    url = f"{settings.dashscope_base_url.rstrip('/')}/embeddings"
    headers = {
        "Authorization": f"Bearer {settings.dashscope_api_key}",
        "Content-Type": "application/json",
    }
    body = {"model": settings.embedding_model, "input": text}
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(url, json=body, headers=headers)
        resp.raise_for_status()  # 400/401 → 抛错，由业务函数兜底
        data = resp.json()
    try:
        return data["data"][0]["embedding"]
    except (KeyError, IndexError, TypeError) as e:
        raise RuntimeError(f"DashScope embedding 响应格式异常: {data}") from e
