import json

from fastapi import APIRouter, Request
from fastapi.responses import PlainTextResponse

from app.services import job_recommend

# 服务间调用（career-service Feign 直连），不挂 JWT 校验（与 chat 路由不同）
router = APIRouter()


def decode_query_text(raw: str) -> str:
    """返回与 Java 端 @RequestBody String 等值的查询文本（用于嵌入 & LLM 用户提示）。

    Feign 把 Map<String,Object> 序列化成 JSON 字符串字面量（外层带引号转义），
    Java 端 Spring MVC 反引号后 @RequestBody String 拿到的是「内层未引号 JSON 对象文本」。
    这里：
    - raw 本身是 JSON 字符串字面量 → json.loads 后是 str，取该内层文本
    - raw 是普通 JSON 对象 → 直接用原始文本
    - raw 不是合法 JSON → 原样返回

    绝不能对带引号的原始文本做嵌入（会得到完全不同的向量）。
    """
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError:
        return raw
    return obj if isinstance(obj, str) else raw


def _pool(request: Request):
    """取 pgvector 连接池；库不可用时返回 None，由调用方给出清晰错误。"""
    return getattr(request.app.state, "pg_pool", None)


@router.post("/jobs/recommend")
async def recommend(request: Request) -> PlainTextResponse:
    if _pool(request) is None:
        return PlainTextResponse("[]", media_type="text/plain")
    raw = (await request.body()).decode("utf-8", errors="replace")
    query_text = decode_query_text(raw)
    result = await job_recommend.recommend_category(request.app.state.pg_pool, query_text)
    # text/plain + 裸 JSON 文本，完全匹配 Feign 对 Java String 返回的 StringDecoder 解码
    return PlainTextResponse(result, media_type="text/plain")


@router.post("/jobs/recommend/specific")
async def recommend_specific(request: Request) -> PlainTextResponse:
    if _pool(request) is None:
        return PlainTextResponse(
            json.dumps({"error": "向量库不可用"}, ensure_ascii=False), media_type="text/plain"
        )
    raw = (await request.body()).decode("utf-8", errors="replace")
    query_text = decode_query_text(raw)
    result = await job_recommend.recommend_specific_job(request.app.state.pg_pool, query_text)
    return PlainTextResponse(result, media_type="text/plain")
