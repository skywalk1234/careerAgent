# 简历润色专家 Subagent 方案

> 状态：方案设计（待实施）
> 更新：2026-08-28（v3：改为「服务端状态机 + 自由文本问答」，去掉结构化提问卡；问题/答案均为自由文本，前端零改动）
> 落点：`fc2026/ai-service-py`（Python AI 服务，前端主 agent 当前使用）

## 一、背景与目标

简历已由「结构化字段」改为「markdown 原文」存储（`StudentProfile = {id, content}`），恰好成为简历润色最理想的输入。本方案新增一个**简历润色专家流程**：

- 读取用户目标岗位的完整信息（JD）
- 依据岗位要求向用户提出少量关键澄清问题（**自由文本，用户手动输入回答**）
- 根据用户答复修改用户当前的简历（markdown 原文）
- 修改后执行 **reflect 自检**，确保不丢事实、不产生幻觉、对齐岗位关键词

**v2 → v3 演进说明**：v2 采用「subagent 出题 + 主 agent 传话」，问题以结构化卡片呈现、主 agent 负责转述与重组答案，存在 schema 漂移、进度难追踪的麻烦。v3 改为**服务端状态机 + 自由文本问答**：

- 流程路由由 FastAPI 服务端决定（进度存 Redis），**LLM 不再当调度员**，只做两件事：① 差距分析出题、② polish + reflect；
- 问题/答案均为自由文本，通过现有 SSE `delta` 事件普通文字呈现，**不新增事件、不新增前端组件**；
- 每轮只调 1 次 LLM，主 LLM 在流程进行中完全不参与。

## 二、数据基础（均已存在）

| 需求            | 来源                    | 接口                                                                                        |
| ------------- | --------------------- | ----------------------------------------------------------------------------------------- |
| 收藏岗位列表        | career-service        | `GET /users/me/favorite-jobs` → `FavoriteRes{total, list:[{jobId, jobName, city, ...}]}`  |
| 完整岗位 JD       | career-service        | `GET /jobs/{jobId}` → `JobDocument`（含 `jobDescription`、`abilityRequirements`）             |
| 当前简历 markdown | profile-service       | `GET /users/me/profile` → `{hasProfile, profile: {id, content}}`                          |
| 写回简历          | resume-parser-service | `POST /users/me/profile` body `{content: "markdown"}` → `profile_storage` 队列落库 → 自动触发重新评分 |

> ⚠️ 收藏列表只返回岗位摘要，**完整 JD 需按 `jobId` 再调一次 `GET /jobs/{jobId}`**。

## 三、总体架构：服务端状态机 + 自由文本问答

核心思路：**「接下来干嘛」由服务端决定，LLM 只负责吐内容。** 对话内容全部走现有 SSE 协议（`delta` 事件普通文字），进度存 Redis，不依赖 LLM 记忆（工具结果不落库，主 LLM 下一条消息对上一轮一无所知）。

### 会话状态机（Redis 存储）

```
       用户要求改简历               答完本批问题          用户确认
 idle ──────────────► gathering ──────────────► ready ──────────► polishing ──► done ──► idle
   ▲                     │ 岔开话题/放弃               │保存/放弃          │
   └─────────────────────┴───────────────────────────┴──────────────────┘
```

**状态存储**：Redis，key `resume_flow:{session_id}`，值 JSON，TTL 30 分钟（活动时刷新）：

```json
{
  "state": "gathering",              // idle | gathering | ready | polishing | done
  "jobId": 42,
  "profileId": "uuid-xxx",           // 目标简历 id，写回时定位用
  "history": [
    {"question": "① 实习里做过并发/性能优化相关的事吗？", "answer": "做过，帮公司接口压测优化"}
  ],
  "revised": null                    // polish 完成后暂存修订稿，等用户确认写库
}
```

> 进度不放在 LLM 对话里，放在 Redis 里——即使主 LLM 对之前对话无记忆，服务端也知道这个会话问到哪了。这是"不会丢进度/跑偏"的保证。

> **入口工具只被调用一次，不是嵌套循环。** `start_resume_polish` 是同步工具调用，在当前 SSE 流内执行完就返回（返回首批问题文本），**工具里没有"循环等用户回答"的通道**——工具调用期间拿不到用户下一条消息。v3 的"循环"是**跨用户消息**的：每一条新消息都是一次新的请求，服务端在 `stream_message` 入口按 Redis 状态路由到对应分支。整个流程里入口工具只出现一次，之后到 `done` 回 `idle`，主 LLM 工具循环才重新接管。

### LLM 仅两处被调用（均为一次性、结构化输入输出）

| 步骤                       | 输入                        | 输出                                              |
| ------------------------ | ------------------------- | ----------------------------------------------- |
| **差距分析（gather）**         | JD + 简历 markdown + 累积问答历史 | `{next, text}`                                  |
| **润色（polish + reflect）** | JD + 简历 markdown + 累积问答历史 | `{revisedContent, changes[], reflectChecklist}` |

### 控制信号（唯一的结构化字段）

差距分析返回 `{next, text}`：`text` 自由文本直接流给用户，`next` 决定状态跳转（用户永远看不到 JSON）。

| next       | 含义        | 状态动作                         |
| ---------- | --------- | ---------------------------- |
| `ask_more` | 还需要补充信息   | 保持 gathering，text 作为下一批问题流出  |
| `ready`    | 信息已够      | 转 ready，text 提示「信息够了，要开始改吗？」 |
| `abandon`  | 用户岔开话题/放弃 | 回 idle（本条消息不再走主循环，初版简单处理）    |

**内容和控制分离：内容自由文本，控制一个枚举字段。**

## 四、状态流详解

### 各状态下的消息路由

| 状态          | 含义      | 用户发一条消息，服务端做什么                                                                               |
| ----------- | ------- | -------------------------------------------------------------------------------------------- |
| `idle`      | 普通聊天    | 走现有 `for _ in range(5)` 主 LLM 工具循环，完全不变；仅新增 `start_resume_polish` 工具供主 LLM 调用来进入流程           |
| `gathering` | 正在问问题   | **不跑主 LLM**。本条消息视为对上一批问题的回答 → append 进 Redis `history` → 调一次差距分析 LLM → 按 `next` 跳转并流式输出 text |
| `ready`     | 信息够用    | 等用户明确确认。用户说「开始/改吧」→ 转 polishing；说「算了」→ 回 idle                                                |
| `polishing` | 正在生成修订稿 | 调一次 polish LLM → 流式展示修订稿 + changes → 状态 done                                                 |
| `done`      | 修订稿已生成  | 用户确认「保存」→ 服务端 `POST /users/me/profile {content}` 写库（自动触发重评分）→ 回 idle；「放弃」→ 回 idle            |

### 入口前置条件（双必填 + 缺参先问）

`start_resume_polish(job_id, profile_id)` **两个参数均为必填**，且必须从用户消息中确定（沿用 `query_job_detail` / `get_student_profile` 的抽取约定：消息中带 `jobId:xxx`、`profileId:xxx` 标记，或岗位名 / 简历标题明确指向）。**两者缺一时，主 LLM 不得调用该工具**，而是先追问一轮（普通对话，流程不进入 gathering），待用户给出后再进入。

> ⚠️ 此规则**比 `get_student_profile` 更严**：`get_student_profile` 允许"未指定则默认最新一份"，但 `start_resume_polish` **不采用该宽松约定**——简历必须被明确指认（`profileId:` 标记 / 简历标题 / 编号）。用户只泛泛说"我的简历"而不指明哪一份，即视为 `profile_id` 缺失，需追问让用户选择（可先调 `/users/me/profile/list` 列出简历供其确认）。`job_id` 同理，泛泛说"这个岗位"视为缺失。

### 全链路走一遍

**第 1 条消息**：「帮我把简历[profileId:abc]针对岗位[jobId:42]优化」

- `idle` → 主 LLM 工具循环（现有逻辑）。主 LLM 识别意图，从消息中抽取 `jobId` 与 `profile_id`，两者齐全才调 `start_resume_polish(job_id, profile_id)`；缺任一 → 先追问，不进入流程。
- 工具内部：按 profileId 拉简历（`GET /users/me/profile/{profileId}`）+ 按 jobId 拉 JD（`GET /jobs/{jobId}`）→ 设 Redis `gathering`、存 jobId/profileId → 调一次差距分析 LLM（历史为空）→ 返回 `{next:"ask_more", text:"我对比了你的简历和这个 JD，主要差距：①……②……补充几个问题：① ……② ……"}`。
- chat.py 识别到该工具返回 → 把 `text` 当普通 `delta` 流给用户 → **短路跳出主循环**，不让主 LLM 复述。

**第 2 条消息**（用户的自由回答，如「实习做过接口压测优化」）：

- `gathering` → 不跑主 LLM。把上一条问题文本 + 本条回答原文 append 进 `history` → 调差距分析 LLM（JD + 简历 + history）→
  - `next:"ask_more"` → 继续流文本再问一轮；
  - `next:"ready"` → 状态转 ready，流「信息够了，要开始改吗？」。

**第 3 条消息**：「开始改」→ `ready` → 转 `polishing` → 调 polish LLM（JD + 简历 + history）→ 流式展示修订稿 + 变更点 → `done`。

**第 4 条消息**：「保存」→ `done` → 服务端 `POST /users/me/profile {content: 修订稿}` → 回 `idle`。写库后现有 `profile_storage → eval_storage` 队列自动重新评分。

### gathering 轮次上限

服务端强制上限（如 4 轮）：超过后不再问，直接 `ready`，防止无限追问。每轮只有 1 次 LLM 调用，整条流程 3~5 次调用，成本可控。

## 五、实施清单（改动集中在 Python 端）

| 文件                                     | 改动                                                                                                                                                                                                                                           |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app/tools.py`                         | 新增 `start_resume_polish(job_id, profile_id)`（**双必填**，均从用户消息中抽取，缺参不调用；内部按 profileId 拉简历 + 按 jobId 拉 JD + 首次差距分析 + 设 Redis 状态），注册进 `ALL_TOOLS`。若用户按岗位名/简历标题而非 id 指定，另补 `get_favorite_jobs`（收藏列表）/ `get_profiles`（`/users/me/profile/list`）解析工具 |
| 新增 `app/services/resume_polish.py`     | 核心：`gather(job, resume, history)` 与 `polish(job, resume, history)` 两个一次性 LLM 调用 + 结构化 JSON 解析；内部 HTTP 拉 JD/简历参考 `tools.py` 中 `get_student_profile` 的写法（透传 JWT）                                                                               |
| 新增 `app/services/flow.py`（或并入 chat.py） | Redis 状态读写：`get_flow / set_flow / clear_flow`，key `resume_flow:{session_id}`，TTL 30 分钟                                                                                                                                                       |
| `app/routers/chat.py`                  | `stream_message` 开头按状态分支：非 `idle` 走润色流程（gathering 接管/ready/polishing/done），`idle` 走现有主循环；主循环内识别 `start_resume_polish` 返回 → 流 text + 短路跳出                                                                                                     |
| `app/config.py` / `requirements.txt`   | 增加 Redis 连接配置与依赖（当前 ai-service-py 未连 Redis，Java Agent 在用）                                                                                                                                                                                    |
| 前端                                     | **零改动**                                                                                                                                                                                                                                      |

## 六、关键设计决策

1. **流程控制归服务端**：LLM 只吐内容，状态跳转由 Redis 状态机决定——规避"主 LLM 每轮必须记得再调工具并带上历史"的脆弱性，这是 v2 方案最麻烦的部分。
2. **入口交给 LLM，流程交给服务端**：主 LLM 只做三件事——判断是否润色请求、从用户消息确定 `job_id` + `profile_id`、调用入口工具，做完交棒。参数缺一时先追问（普通对话）而不是硬调工具。它理解自然语言灵活，服务端流程可靠。
3. **自由文本问答，无结构化卡片**：问题/答案均自由文本，前端零改动、用户自由度最高。唯一结构化的是控制信号 `next`（`ask_more | ready | abandon`）。
4. **一次最多 2~3 个问题**：避免审问式体验；gathering 设轮次上限（4 轮）防止无限追问。
5. **Reflect 兜底幻觉**：polish 输出必须带 `reflectChecklist`；自检不通过则模型自行再修，是对抗 LLM 重写简历产生幻觉的核心防线。
6. **防幻觉红线**：polish prompt 硬性要求「只能重组、扩写用户明确承认的内容，不得虚构公司、项目、量化数字」。
7. **覆盖保护**：修订稿先展示、用户确认后才写库，不直接覆盖原简历。
8. **评分联动**：写回后复用现有队列链路自动重新评分，无需新逻辑。

## 七、风险与取舍

1. **幻觉风险（最高优先级）**：LLM 重写简历易凭空补经历/数字。prompt 红线 + reflect 自检 + 用户确认三道防线。
2. **gathering 阶段用户岔开话题**：差距分析返回 `abandon` 直接回 idle。初版不重放该条消息（简单）；后续可优化为「abandon 时将该条消息重新投递到主循环」。
3. **Redis 引入**：ai-service-py 当前未连 Redis，需新增依赖与连接；可用同一套 `192.168.118.130:6379` 实例。若不想引入 Redis，也可退化为内存 dict（进程重启丢失，仅适合单机调试）。
4. **历史累积成本**：`history` 线性增长，但单次流程仅 2~3 轮、每轮一问一答，token 可控。
5. **入口参数必须齐全**：`job_id` 与 `profile_id` 都需从用户消息确定，缺任一，主 LLM 追问一轮而非调用工具（避免带半截参数进入流程）。若用户按岗位名/简历标题而非 id 指定，需补 `get_favorite_jobs` / `get_profiles` 解析工具让主 LLM 把名称解析成 id。

## 八、V2 展望：优秀简历 RAG 参考

当前向量库仅有 `job_category_vector` / `job_detail_vector` 两张岗位表，**无简历语料**。后续可引入：

1. 采集优秀简历样本（脱敏的历史高分简历 / 人工整理样本）；
2. 向量化存入新增表（如 `resume_example_vector`）；
3. polish 阶段检索「相关优秀简历」作为**表达参考**。

> ⚠️ 定位必须为「表达参考」而非「内容来源」：只借鉴句式、结构、量化方式，**不得借用样本中的经历与数字**，否则突破防幻觉红线。

## 九、验证路径

本地启动 ai-service-py 后走通：

```
主对话："帮我把简历[profileId:abc]针对岗位[jobId:42]优化"
  → 主 LLM 从消息抽 jobId + profileId → 调 start_resume_polish → 流式输出首轮问题（普通文字）
  → 用户自由回答 → 服务端调差距分析 → 再问一轮 / 「信息够了，要开始改吗？」
  → 用户说"开始改" → polish 输出修订稿 + 变更点 → 用户确认
  → 保存 → 写库 → 轮询评分完成 → 回普通聊天
```

另验证入口缺参：消息只带 jobId 没带 profileId（或反之）→ 主 LLM 追问一轮，**不**调用工具、不进入流程。

另验证：gathering 阶段用户岔开话题（abandon → 回 idle）、连续回答多轮不重复提问、修订稿不丢原有经历/数字。
