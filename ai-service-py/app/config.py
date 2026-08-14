from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全局配置，自动读取项目根目录的 .env 文件"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "ai-service"

    # 服务监听端口（不经过 Java 网关，前端直接访问）
    server_port: int = 8086

    # ---------- DeepSeek API 配置 ----------
    # API Key 在 .env 文件的 DEEPSEEK_API_KEY 中填写
    # 申请地址: https://platform.deepseek.com/
    deepseek_api_key: str = "${DEEPSEEK_API_KEY}"
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-v4-flash"

    # ---------- 数据库配置 ----------
    # 会话/消息存 MySQL chat_history 库（表：sessions / messages）
    database_url: str = "mysql+aiomysql://root:123@192.168.118.130:3306/chat_history?charset=utf8mb4"

    # 会话/消息缓存过期时间(秒)，后续接入 Redis 时使用
    cache_ttl: int = 300

    # ---------- Java profile-service 地址 ----------
    # 工具获取学生简历时调用（网关默认 8080；若本地绕过网关可改直连 profile-service 端口）
    profile_service_base_url: str = "http://127.0.0.1:8080"


settings = Settings()
