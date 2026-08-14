from functools import lru_cache

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

from app.config import settings


@lru_cache(maxsize=1)
def get_llm() -> ChatOpenAI:
    """懒加载 LLM 客户端：第一次流式调用时才创建。
    DeepSeek 提供 OpenAI 兼容接口，langchain-openai 直接可用。
    """
    if not settings.deepseek_api_key:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY，请在 .env 文件中填写")

    return ChatOpenAI(
        model=settings.deepseek_model,
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        temperature=0.7,
        max_tokens=2048,
        timeout=60,
    )


def get_llm_with_tools(tools: list) -> BaseChatModel:
    """将工具绑定到 LLM，返回支持工具调用的模型实例"""
    return get_llm().bind_tools(tools)
