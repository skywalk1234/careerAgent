建表语句：
CREATE EXTENSION IF NOT EXISTS vector;

-- 重建前先确认：旧数据/旧引用已处理，此语句不可恢复
-- DROP TABLE IF EXISTS job_detail_vector;

-- ============ 1) 建表 ============
CREATE TABLE job_detail_vector (
    -- 自增主键 + 业务唯一键（承载 crawler 的 upsert / 去重）
    id           bigserial PRIMARY KEY,
    job_key      text NOT NULL UNIQUE,           -- 采集去重键（url 归一化 / title+company+city+security_id）

    -- ===== SQLite jobs 业务字段（对齐 crawler/db.py）=====
    security_id  text,                           -- BOSS 岗位 security_id
    title        text NOT NULL,
    company      text,
    city         text,
    salary       text,                           -- 原始薪资文本，如 20-35K·15薪
    avg          real,                           -- 月平均薪资 K（清洗后）
    tier         text,                           -- 薪资档：15-30K / 30-50K ...
    exp          text,                           -- 经验要求
    edu          text,                           -- 学历要求
    cats_json    jsonb DEFAULT '[]',             -- cat_rules 自动分类标签（大模型/LLM、AI Agent...）
    kw_json      jsonb DEFAULT '[]',             -- 命中的搜索关键词
    url          text,
    source       text NOT NULL DEFAULT 'boss',   -- boss / intern 等
    first_seen   timestamptz,                    -- 首次入库时间
    last_seen    timestamptz,                    -- 最近一次出现（重跑刷新用）
    crawled_at   timestamptz,                    -- 本次采集时间
    is_new       smallint NOT NULL DEFAULT 1,    -- 1=本次新增  0=已存在刷新
    raw_json     jsonb,                          -- BOSS 原始整包 JSON 留底

    -- ===== 检索三件套 =====
    content      text,                           -- 岗位 JD（仅 JD 文本，未来据此做 embedding/检索）
    metadata     json,                           -- 检索元数据 jsonb（city/salary/...，对齐现有消费方键）
    embedding    vector(1536),                   -- 向量；未向量化时 = NULL
    vector_ready boolean NOT NULL DEFAULT false  -- 是否已向量化、可被检索
);

-- ============ 2) 索引 ============
CREATE UNIQUE INDEX uq_job_detail_vector_job_key ON job_detail_vector (job_key);
CREATE INDEX idx_job_detail_vector_city  ON job_detail_vector (city);
CREATE INDEX idx_job_detail_vector_title ON job_detail_vector (title);
CREATE INDEX idx_job_detail_vector_ready ON job_detail_vector (vector_ready) WHERE vector_ready;


-- ============ 3) 采集审计表（crawler/db.py 的 save_run 写入；代码内也会自动建，这里留档） ============
CREATE TABLE IF NOT EXISTS crawl_runs (
    id            bigserial PRIMARY KEY,
    started_at    timestamptz,
    finished_at   timestamptz,
    keywords_json jsonb DEFAULT '[]'::jsonb,
    cities_json   jsonb DEFAULT '[]'::jsonb,
    mode          text,                          -- 如 crawl-api / scroll / process_partial
    raw_count     integer DEFAULT 0,
    cleaned_count integer DEFAULT 0,
    added_count   integer DEFAULT 0,
    db_file       text,                          -- 沿旧列名，落库值固定为 pgvector:job_detail_vector
    note          text
);



metadata字段内容：
{
  "jobKey": "9c1f0a...",              // 与 job_key 列同值（采集去重键）
  "jobId": "boss:8a3f1c...",          // BOSS 招聘帖 id（encryptJobId）
  "jobName": "AI应用开发工程师",
  "companyName": "××科技有限公司",
  "companySize": "100-999人",
  "companyStage": "B轮",

  "city": "北京",
  "district": "海淀区",

  "salaryText": "20-35K·15薪",
  "salaryMin": 20.0,
  "salaryMax": 35.0,
  "salaryUnit": "K",
  "salaryAvgK": 27.5,
  "salaryTier": "15-30K",

  "experience": "3-5年",
  "education": "本科",

  "source": "boss",
  "sourceSite": "BOSS直聘",
  "sourceUrl": "https://www.zhipin.com/job_detail/xxxx.html",
  "updatedAtRaw": "2026-09-09",

  "contentHash": "sha1:9c1f...",
  "embeddingModel": "qwen3.7-text-embedding"
}
```

## 向量化口径（2026-09-10 起）

这张表的 `embedding` 由**采集入库后内联写入**（`crawler/db.py` 的 `_embed_pending`），不再是空的：

| 项 | 值 |
|---|---|
| 模型 | `qwen3.7-text-embedding`（`settings.job_embedding_model`） |
| 维度 | 1536（模型默认 1024，必须显式传 `dimensions`） |
| 输入 | `content` 列（即岗位 JD 文本），超 `settings.job_embedding_max_chars`（8000）才截断 |
| text_type | 写库 `document` / 检索 `query` |

**该表的向量空间是 Python 与 Java 共用的，换模型必须两侧一起换**，否则相似度分数失真：

| 角色 | 位置 |
|---|---|
| 写（文档侧，text_type=document） | `ai-service-py/crawler/db.py`（爬虫）、`scripts/import_intern_jobs.py`（人工录入） |
| 读（查询侧，text_type=query） | `ai-service-py/app/services/job_recommend.py` 的 `recommend_specific_job` |
| 读（查询侧） | `resume-parser-service/.../config/SpringAIConfig.java` 的 `jobDetailEmbeddingModel` bean（qwen3.7 / 1536 / 库默认 text_type=document） |

其余三张表（`job_category_vector`、`resume_example_vector`、`user_episodic_memory`）**仍是
`text-embedding-v1`**，所以 `job_recommend.recommend_category`、长期记忆、简历样例检索都不受影响，
别顺手把 `settings.embedding_model` 也改了。

`vector_ready` 语义：`content` 变更时 upsert 会把 `embedding` 置 NULL 并把该列打回 `false`，
由 `_embed_pending` 重嵌后置回 `true`；嵌入失败的行保持 `false`，下次重跑自动补。

## 谁在读写这张表

**写**：`fc2026/ai-service-py/crawler/db.py`（BOSS 直聘爬虫，upsert 到 `job_detail_vector`，事务提交后按上节口径内联向量化）。

**读**：

| 链路 | 位置 | 说明 |
|---|---|---|
| 岗位探索页 | `career-service` `JobExploreController` → `JobVectorQueryService` → `JobVectorRepository` | `POST /jobs/search`、`GET /jobs/{jobId}`、`GET /jobs/filters`、收藏列表 |
| RAG 岗位推荐 | `ai-service-py` `app/routers/job_recommend.py` | pgvector 余弦检索 + DeepSeek 精排 |
| Java 版 RAG / 岗位分析 / 职业路径 / 生涯报告 | resume-parser-service、career-service | **仍读 ES 的 `jobs_index`**，本次未动 |

### career-service 侧的接入方式（2026-09-10）

前台岗位探索链路的数据源已从 ES `jobs_index` 切到本表，前端契约同步改过。

- 配置在 `career-service/src/main/resources/application.yml` 的 `vector.datasource.*`（指向线上向量库）。
- `config/VectorJdbcConfig.java` **只暴露 `JdbcTemplate` Bean，绝不暴露 `DataSource` Bean**——`MybatisPlusAutoConfiguration` 上有 `@ConditionalOnSingleCandidate(DataSource.class)`，多一个 DataSource 候选会让全部 MyBatis-Plus Mapper 静默失效（`favorite_jobs` 等直接报错）；加 `@Primary` 也救不了，那会把 MyBatis 指到 PG。
- `repository/JobVectorRepository.java` 是纯 JdbcTemplate SQL：关键词 `ILIKE`、`city`、`avg` 区间、`tier/exp/edu` 多值 `IN`，排序走白名单（`salary→avg`、`updatedAt→last_seen`、`createdAt→first_seen`）。
- 接口里返回的 `jobId` 用的是 **`job_key`**（表上 UNIQUE、必然非空）；`metadata->>'jobId'`（`boss:{encryptJobId}`）只作为 `bossJobId` 附带返回。
- `metadata` 是 `json` 类型，取值统一走 `->>'xxx'` 拿 text，避免处理 `PGobject`。
- `GET /jobs/{jobId}` 查 PG 未命中时会**回退查 ES**，保证老的 ES jobId（以及基于它存的收藏记录）仍能打开详情。
- `JobFilterRes` 的 `cities / educationRequirements / exps / salaryTiers` 都是 `SELECT DISTINCT` 出来的真实取值；`industryTags / levels / companySizes / companyTypes` 在本表里没有对应列，已从接口契约里删掉，前端 `jobGraph.ts`、`JobGraphView.vue`、`MatchAnalysisView.vue`、`MockInterviewView.vue` 同步改过。
