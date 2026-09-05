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

    # ---------- Java career-service 地址 ----------
    # 工具查询岗位详情时调用（网关默认 8080；若本地绕过网关可改直连 career-service 端口）
    career_service_base_url: str = "http://127.0.0.1:8080"

    # ---------- pgvector 向量库配置 ----------
    # job_category_vector / job_detail_vector 为岗位表（Spring AI PgVectorStore），
    # resume_example_vector 为简历样例库表（简历片段+点评 few-shot，见 fc2026/简历RAG方案.md）。
    # 真实库在 8.147.71.59:40086（见 a_fuchuang_2026/test_script/testing 的 application.yml），
    # 密码中的 @ 已 URL 编码为 %40
    vector_database_url: str = "postgresql://postgres:Mm85619562%40@8.147.71.59:40086/ai-vector"

    # ---------- DashScope 文本嵌入配置 ----------
    # 必须用 text-embedding-v1（1536 维）才能与库内已有向量比对；DeepSeek 无 embedding API
    dashscope_api_key: str = "sk-2c1e9d35337d4deb8c7ff28b4f6c4193"
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_model: str = "text-embedding-v1"
    embedding_dimensions: int = 1536
    # 嵌入输入超长时防御性截断（text-embedding-v1 单次输入有上限），可调大以完全对齐 Java 不截断
    embedding_max_chars: int = 2000

    # ---------- RAG 岗位推荐参数（对齐 AI_recommend.java）----------
    recommend_top_k: int = 5
    recommend_category_threshold: float = 0.3
    recommend_specific_threshold: float = 0.2

    # ---------- 简历样例库 RAG 参数（resume_example_vector，独立调优）----------
    resume_example_top_k: int = 5
    # 样例片段间的相似度天然低于岗位匹配，阈值放得更宽（低于岗位推荐 0.2），
    # 靠 metadata 的 jobCategory 过滤兜底，不纯靠相似度卡
    resume_example_threshold: float = 0.15

    # ---------- 长期记忆参数（app/services/memory.py）----------
    memory_max_transcript_messages: int = 5  # 每次抽取读会话最近 N 条消息（滑动窗口）
    memory_max_reference_episodes: int = 5   # 注入作去重/supersede 参照的活跃 episode 上限
    memory_recall_top_k: int = 5             # recall_memory 语义召回条数
    memory_recall_threshold: float = 0.2     # 语义相关阈值；命中不足时工具回退到最近活跃

    # ---------- 简历润色 Reflection 参数（app/services/resume_polish.py）----------
    polish_max_refine_rounds: int = 1  # 「生成→评审→修正」循环中最多修正轮数（0=只生成不评审修正）

    # ---------- 职业规划专家参数（app/services/plan.py，设计见 fc2026/职业规划专家方案.md）----------
    plan_recent_messages: int = 8    # 补读本会话最近 N 条原文作「即时速览」（刚说完可能还没沉淀进记忆）
    plan_timeline_rows: int = 12     # timeline-view 预算条数（≈ 均分到各核心类别取最近，含被取代的旧进展）
    plan_recall_queries: int = 3     # 「先思考」阶段最多产出多少条召回主题（信息缺口分析）
    plan_recall_top_k: int = 3       # 每条召回主题取回多少条补充记忆（去重后并入，总量≈queries×topk）


settings = Settings()
