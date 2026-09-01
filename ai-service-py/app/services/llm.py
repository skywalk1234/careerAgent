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
# 显式关闭思考模式。注意：不能只靠"不传 extra_body"来关闭——
# deepseek-v4-flash 默认行为仍会做隐藏推理（实测结构化输出延迟约翻倍），
# 必须显式传 {"thinking": {"type": "disabled"}} 才会真正关闭。
_THINKING_DISABLED_BODY = {
    "thinking": {"type": "disabled"},
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
    """结构化 JSON 专用 LLM：显式关闭思考模式，低温度保证严格 JSON 输出。

    与 get_llm 的区别：
    - 显式关闭 thinking（_THINKING_DISABLED_BODY）：纯结构化 JSON 提取/排序任务，思考模式徒增延迟
      与 token（实测约翻倍），且可能泄漏 reasoning_content。不能只靠"不传 extra_body"——模型默认
      仍会做隐藏推理，必须显式传 disabled。
    - 更低温度 + 更高 max_tokens：bestMatch + 最多 4 个 otherRecommendations、简历润色全文，输出较长
    - timeout=150：简历润色要生成整篇 markdown，允许较长耗时；真正的硬超时由 resume_polish 里的
      asyncio.wait_for 保证。
    """
    if not settings.deepseek_api_key:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY，请在 .env 文件中填写")

    return ChatOpenAI(
        model=settings.deepseek_model,
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        temperature=0.2,
        max_tokens=4096,
        timeout=150,
        extra_body=_THINKING_DISABLED_BODY,
    )
