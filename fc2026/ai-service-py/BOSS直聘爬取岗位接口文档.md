# BOSS直聘爬取岗位接口文档

> ai-service-py（FastAPI，端口 8086，前端 aiHttp **直连，不经过 Java 网关**）提供的一套「爬取 BOSS 直聘岗位」接口。
> 爬虫核心代码是独立脚本 `fc2026/boss_fetch` 的整包迁移，现放在服务顶层 `ai-service-py/crawler/`（两者逻辑需保持同步）。
> 接口统一前缀 `/users/me/crawl`，鉴权走 JWT，响应统一 `{code, msg, data}`。

- 采集**异步**执行：提交后立即返回任务状态，后台 daemon 线程里同步爬取（DrissionPage 驱动真实 Chrome）。
- **同一时刻只允许一个采集任务**（BOSS 对同一 Cookie 并发敏感），已有任务在跑时重复提交返回 `409`。
- 结果入**岗位向量库**（`8.147.71.59:40086/ai-vector` 的 `job_detail_vector` 表，DDL 见 `岗位向量库job_detail_vector.md`），
  全流程等价 `boss_fetch --merge`：`crawler.run() → process_batch()（清洗）→ upsert_jobs() → save_run()`；
  采集审计写同库的 `crawl_runs` 表。
- 本阶段**只落结构化数据、不触发向量化**：`embedding` 恒为 NULL、`vector_ready=false`，`content`/`metadata` 已按建表文档写好；
  待后续向量化脚本按 `WHERE vector_ready = false` 补嵌入后，新爬岗位才会进入 RAG 推荐检索。
- 注意：`crawler/db.py` 已被改为写 PG（不再写 SQLite），与独立脚本 `fc2026/boss_fetch` 的同名文件**不再保持一致**（那份仍写本地 SQLite）。

---

## 1. 通用约定

### 1.1 鉴权

所有接口需要登录态。请求头携带 JWT（Java 网关同款 RS256 签发体系）：

```
Authorization: Bearer <JWT>
```

未携带/失效返回 `401 {"code":401,"msg":"未登录或登录失效"/"token 无效或已过期","data":null}`。

### 1.2 响应外壳

```json
{ "code": 200, "msg": "success", "data": { ... } }
```

- `code != 200` 表示出错；业务错误（如城市不合法、无运行任务）走 HTTP 状态码 + 统一外壳：
  - `400` 参数错误（未知城市 / 无运行中任务）
  - `401` 未登录或 token 无效
  - `404` 暂无采集记录
  - `409` 已有采集任务在运行

### 1.3 数据来源说明

筛选/清洗规则取自 `crawler/config/keywords.json`：
- `keywords`：岗位搜索词（默认 `AI应用开发工程师`、`Agent应用开发工程师`）
- `cities`：意向城市 → 内置 20 城码表 ∪ 配置 cities 求并集
- `cat_rules`：岗位自动打分类标签；`scrape_limits`：数量默认
- 内置随机延迟/滚动节奏/人机模拟/反检测注入——**请勿去掉，勿多实例并发**

---

## 2. 城市码表（GET /users/me/crawl/cities）

请求无需参数，返回支持的城市名 → 城市码映射，前端筛选项可直接渲染：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "北京": "101010100", "上海": "101020100", "广州": "101280100", "深圳": "101280600",
    "杭州": "101210100", "成都": "101270100", "南京": "101190100", "武汉": "101200100",
    "苏州": "101190400", "西安": "101110100", "长沙": "101250100", "合肥": "101220100",
    "郑州": "101180100", "重庆": "101040100", "厦门": "101230200", "天津": "101030100",
    "济南": "101120100", "青岛": "101120200", "大连": "101070200", "福州": "101230100"
  }
}
```

> 请求里只传**城市名**（如 `"北京"`），由后端解析成码；传了不在表中的城市名返回 `400` 并附支持列表。

---

## 3. 提交采集 POST /users/me/crawl/start

发起一次采集。参数全部可选，空值取 keywords.json 默认。

### 3.1 请求体

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | :-: | --- |
| `keywords` | string[] | 否 | 岗位搜索词；**标题需包含该词才会保留**，词越像完整岗位名越准。缺省取配置 keywords |
| `cities` | string[] | 否 | 意向**城市名**（后端解析成码）。缺省取配置 cities |
| `searchFilters` | object | 否 | BOSS 站内二级筛选，值为**数字 code**（见 §6），如 `{"salary":"406","degree":"203","jobType":"1901"}`。CLI 版本不支持、仅此接口可用 |
| `newJobTarget` | int | 否 | 每 关键词×城市 组合的新岗位目标数，达到即停（默认 20） |
| `maxJobs` | int | 否 | 每组合最多浏览岗位数上限（默认 100） |
| `headless` | bool | 否 | 是否无头采集；缺省取配置 `crawler_headless`（默认 true） |

示例：

```json
{
  "keywords": ["大模型工程师", "Agent应用开发工程师"],
  "cities": ["北京", "深圳"],
  "searchFilters": { "salary": "406", "degree": "203", "jobType": "1901" },
  "newJobTarget": 20,
  "maxJobs": 100
}
```

### 3.2 响应（立即返回，非阻塞）

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "runId": "a1b2c3d4e5f6",
    "status": "running",
    "comboIndex": 0,
    "totalCombos": 4,
    "currentKeyword": "",
    "currentCity": "",
    "jobsSoFar": 0,
    "startedAt": "2026-09-09T08:00:00Z",
    "finishedAt": null,
    "stats": null,
    "dbFile": "pgvector:job_detail_vector",
    "message": null
  }
}
```

- `totalCombos = len(keywords) × len(cities)`，用于前端画进度条（`comboIndex / totalCombos`）。
- 已有任务在跑时返回：`409 {"code":409,"msg":"已有采集任务在运行中，请先等待完成或停止","data":null}`。

---

## 4. 查询状态 GET /users/me/crawl/status

返回当前任务进度；无任务时为 `idle`。

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "runId": "a1b2c3d4e5f6",
    "status": "running",
    "comboIndex": 2,
    "totalCombos": 4,
    "currentKeyword": "大模型工程师",
    "currentCity": "北京",
    "jobsSoFar": 43,
    "startedAt": "2026-09-09T08:00:00Z",
    "finishedAt": null,
    "stats": null,
    "dbFile": "pgvector:job_detail_vector",
    "message": null
  }
}
```

`status` 取值：

| 值 | 含义 |
| --- | --- |
| `idle` | 无任务（或尚未发起过） |
| `running` | 爬取中 |
| `stopping` | 已收到停止请求，正在保存已采集数据 |
| `done` | 已完成（`stats` 有值） |
| `error` | 失败（`message` 为原因，常见：BOSS 登录态失效） |

---

## 5. 取结果 GET /users/me/crawl/result

取最近一次采集结果。无记录返回 `404 {"msg":"暂无采集记录，请先 POST /start 发起一次采集"}`。

任务完成后（`status: done`）示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "runId": "a1b2c3d4e5f6",
    "status": "done",
    "comboIndex": 4,
    "totalCombos": 4,
    "currentKeyword": "Agent应用开发工程师",
    "currentCity": "深圳",
    "jobsSoFar": 61,
    "startedAt": "2026-09-09T08:00:00Z",
    "finishedAt": "2026-09-09T08:12:30Z",
    "stats": {
      "raw": 61,
      "cleaned": 48,
      "input": 48,
      "inserted": 45,
      "updated": 2,
      "skipped": 1
    },
    "dbFile": "pgvector:job_detail_vector",
    "message": "采集完成：新增 45 条，刷新 2 条，跳过 1 条"
  }
}
```

`stats` 字段含义（对齐 `crawler.db.upsert_jobs` 返回 + 脚本统计）：
- `raw`：爬虫原始抓取条数
- `cleaned`：清洗后条数（标题命中关键词、未命中黑名单；自动打分类标签）
- `inserted`：**新增**入库条数（`job_detail_vector.is_new=1`）
- `updated`：已存在刷新条数
- `skipped`：清洗丢弃条数

完整岗位数据在向量库 `job_detail_vector` 表内（字段见 `岗位向量库job_detail_vector.md` 与 `crawler/db.py`），本接口只回统计，不回岗位明细。

---

## 6. 停止采集 POST /users/me/crawl/stop

请求**优雅停止**当前任务（停止后续组合 → 已抓到的**完整详情**岗位正常清洗入库 → 关闭浏览器），停止请求发出后 `status` 变 `stopping`，随后任务收敛为 `done`。

- 无运行中任务返回 `400 {"msg":"当前没有运行中的任务"}`。
- 中断语义与 `boss_fetch` 一致：未抓完详情的岗位会留在 `crawl_partial.json`，可后续续采。

---

## 7. BOSS 站内筛选 code 码表（searchFilters）

值必须是**数字字符串**，非数字或 `"0"` 会被忽略：

| 键 | 含义 | 常用 code |
| --- | --- | --- |
| `salary` | 薪资档 | `403`=3-5K、`404`=5-10K、`405`=10-20K、**`406`=20-50K**、`407`=50K+ |
| `experience` | 经验 | `103`=1年以内、`104`=1-3年、**`105`=3-5年**、`106`=5-10年、`107`=10年+ |
| `degree` | 学历 | **`203`=本科**、`204`=硕士、`205`=博士 |
| `jobType` | 求职类型 | **`1901`=全职**、`1903`=兼职 |
| `scale` | 公司规模 | `301`=0-20人 … `306`=10000人+ |
| `stage` | 融资阶段 | `801`=未融资 … `807`=已上市 |

> `position`/`industry` 等动态 code 清单来自 BOSS 前端，未内置于本项目，需要时另配。

---

## 8. 配置项（app/config.py，可用 .env 覆盖）

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `CRAWLER_PROFILE_DIR` | `.chrome_profile` | 已登录 BOSS 直聘的 Chrome 用户目录（Cookie 持久化），相对 ai-service-py 根目录 |
| `VECTOR_DATABASE_URL` | `postgresql://postgres:***@8.147.71.59:40086/ai-vector` | 岗位向量库 DSN（与 RAG 推荐同一套配置），采集结果写入其 `job_detail_vector` 表 |
| `CRAWLER_DB_FILE` | `jobs_data.db` | **已废弃**：原 SQLite 路径，现不再使用（仅为兼容旧 .env 保留） |
| （代码内 `crawler_headless`） | `true` | 采集默认无头 |

其它行为受 `crawler/config/keywords.json` 控制：`keywords` / `cities` / `cat_rules` / `scrape_limits`。

---

## 9. 前置条件与限制

1. **Chrome + 已登录 profile**：需本机装有 Chrome，且 `CRAWLER_PROFILE_DIR` 指向一个**登录过 BOSS 直聘**的 Chrome 用户目录。首次准备：跑 `cd fc2026/boss_fetch && .venv/Scripts/python run.py --login`（profile 指到同一目录）登录一次，此后 Cookie 自动复用。
2. **登录态失效的表现**：任务 `status=error`，`message` 提示先重新登录 profile。
3. **单实例**：crawl 任务在**单进程**（uvicorn 单 worker）内互斥；`main.py` 已关闭 reload。多 worker 部署需另加进程级互斥（本期未做）。
4. **防封**：爬虫内置随机延迟/滚动节奏/UA/反检测，不要并行开多个采集；频繁触发 BOSS 安全验证时调大两个组合间的等待或减小 `maxJobs`。
5. 服务端运行时会**短暂弹一次可见 Chrome**做登录校验再切 headless（crawler 原逻辑），属正常现象。

---

## 10. curl 冒烟示例

```bash
# 提交一个小组合
curl -X POST http://127.0.0.1:8086/users/me/crawl/start \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"keywords":["大模型工程师"],"cities":["北京"],"maxJobs":10,"newJobTarget":5}'

# 轮询进度
curl http://127.0.0.1:8086/users/me/crawl/status \
  -H "Authorization: Bearer <JWT>"

# 取结果
curl http://127.0.0.1:8086/users/me/crawl/result \
  -H "Authorization: Bearer <JWT>"

# 中途停止
curl -X POST http://127.0.0.1:8086/users/me/crawl/stop \
  -H "Authorization: Bearer <JWT>"

# 支持的城市码表
curl http://127.0.0.1:8086/users/me/crawl/cities \
  -H "Authorization: Bearer <JWT>"
```
