from functools import lru_cache

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

from app.config import settings


# DeepSeek 思考模式（OpenAI 兼容格式）：
#   开关  {"thinking": {"type": "enabled"}}
#   强度  {"reasoning_effort": "low"}   # low / high / max
# 注意：不能用 model_kwargs 传 "thinking"——它会被当作 create() 的具名参数而报错
# （unexpected keyword argument）。要用 extra_body 原样塞进 HTTP 请求体。
_THINKING_BODY = {
    "thinking": {"type": "enabled"},
    "reasoning_effort": "low",
}


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
        extra_body=_THINKING_BODY,
    )


def get_llm_with_tools(tools: list) -> BaseChatModel:
    """将工具绑定到 LLM，返回支持工具调用的模型实例"""
    return get_llm().bind_tools(tools)


@lru_cache(maxsize=1)
def get_json_llm() -> ChatOpenAI:
    """岗位重排专用 LLM：关闭思考模式（故意不传 extra_body），低温度保证严格 JSON 输出。

    与 get_llm 的区别：
    - 不启用 thinking：纯结构化 JSON 提取/排序任务，思考模式徒增延迟与 token，且可能泄漏 reasoning_content
    - 更低温度 + 更高 max_tokens：bestMatch + 最多 4 个 otherRecommendations，输出较长
    """
    if not settings.deepseek_api_key:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY，请在 .env 文件中填写")

    return ChatOpenAI(
        model=settings.deepseek_model,
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        temperature=0.2,
        max_tokens=4096,
        timeout=60,
        # 有意不传 extra_body → thinking 关闭
    )
