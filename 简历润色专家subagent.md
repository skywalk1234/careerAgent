# 简历分析专家 / 简历润色专家 Subagent 方案

> 状态：方案设计（待实施）
> 更新：2026-09-01（v4：**取消服务端状态机**，改为「两个同步工具暴露给主 LLM，由主 LLM 编排」）
> 落点：`fc2026/ai-service-py`（Python AI 服务，前端主 agent 当前使用）

## 一、背景与目标

简历已由「结构化字段」改为「markdown 原文」存储（`StudentProfile = {id, content}`），恰好成为简历分析与润色最理想的输入。本方案新增两个**同步专家工具**暴露给主 LLM：

- **简历分析专家 `analyze_resume`**：从语言表达、结构条理、内容完整性、（有目标岗位 JD 时）JD 契合度给出专业诊断，可附带对用户的追问（如目标岗位），追问**不是强制的**——没有就算了。
- **简历润色专家 `polish_resume`**：按目标岗位 JD / 用户修改要求润色简历，**一律另存为一份新简历**（原简历保留），不再询问「是否开始润色」「是否保存」。

**v3 → v4 演进说明**：v3 采用「服务端状态机 + 自由文本问答」（`start_resume_polish` 入口工具 + Redis 存进度 + `chat.py` 按状态路由），LLM 只吐内容、流程由服务端决定。该方案流程刻板、状态转换僵硬，且多轮问答期间主 LLM 完全不参与。v4 改为**取消状态机**，把两个专家做成**同步工具**直接暴露给主 LLM：

- 主 LLM 判断用户意图（分析 or 润色），自主决定调用哪个工具、带哪些参数；
- 多轮澄清问答由主 LLM 通过对话历史记忆（消息存 MySQL `chat_history`），在调用润色工具时以 `extra_info` 自由文本汇总传入；
- 工具是"一次调用、一次返回"，无跨消息的隐藏状态，服务端零状态（Redis 不再需要）。

## 二、数据基础（均已存在）

| 需求            | 来源                    | 接口                                                                                        |
| ------------- | --------------------- | ----------------------------------------------------------------------------------------- |
| 收藏岗位列表        | career-service        | `GET /users/me/favorite-jobs` → `FavoriteRes{total, list:[{jobId, jobName, city, ...}]}`  |
| 完整岗位 JD       | career-service        | `GET /jobs/{jobId}` → `JobDocument`（含 `jobDescription`、`abilityRequirements`）             |
| 当前简历 markdown | profile-service       | `GET /users/me/profile` → `{hasProfile, profile: {id, content}}`                          |
| 指定简历          | profile-service       | `GET /users/me/profile/{profileId}`（多简历场景定位用）                                        |
| 简历列表          | profile-service       | `GET /users/me/profile/list`（让用户选哪一份简历）                                              |
| 写回简历          | resume-parser-service | `POST /users/me/profile` body `{content, profileId}` → `profile_storage` 队列落库              |

> ⚠️ 收藏列表只返回岗位摘要，**完整 JD 需按 `jobId` 再调一次 `GET /jobs/{jobId}`**。
> ⚠️ `GET /users/me/profile` 顶层 `profileId` 是 userId，真实简历 id 在 `profile.profileId` 里。
> ⚠️ **写回后不会自动触发评分**（Java `FileController.saveProfile` 只发 `profile_storage` 不发 `eval_storage`），文案中不得声称「评分任务已触发」。

## 三、总体架构：两个同步工具 + 主 LLM 编排

核心思路：**「干什么、带什么参数、怎么跟用户多轮对话」全由主 LLM 决定**，服务端不做任何流程路由。两个专家工具都是「拉数据 → 一次 LLM 调用 → 返回结构化 JSON」，走现有通用工具路径（工具结果回填 `tool` 消息 → 主 LLM 组织语言流式输出），不新增 SSE 事件、不新增前端组件。

```
用户：「帮我看下我的简历」
  → 主 LLM 判断意图 = 分析 → 调 analyze_resume(profile_id?)
  → 工具拉简历(+可选 JD) → 一次 LLM 诊断 → 返回 {analysis, questions}
  → 主 LLM 把 analysis 转述给用户，questions 作为追问（有就附带）

用户：回答追问 / 直接说「针对这个岗位[jobId:xxx]帮我优化」
  → 主 LLM 判断意图 = 润色 → 调 polish_resume(profile_id?, job_id?, extra_info=汇总的追问答案+修改要求)
  → 工具拉简历(+可选 JD) → 一次 LLM 润色（含 reflect 自检）→ save_profile_as_new 落库
  → 返回 {success, changes[], summary}
  → 主 LLM 转述变更摘要 + 「已另存为新简历（原简历保留）」
```

### 两个工具签名（`app/tools.py`）

```python
@tool
async def analyze_resume(
    profile_id: Optional[str] = None,   # 未指定默认最新一份
    job_id: Optional[str] = None,       # 可选：仅在对话中能确定 jobId 时才带
    token: Annotated[str, InjectedToolArg] = "",
) -> str:                               # 返回 {"analysis", "questions"}

@tool
async def polish_resume(
    profile_id: Optional[str] = None,
    job_id: Optional[str] = None,
    extra_info: Optional[str] = None,   # 主 LLM 汇总的用户补充/修改要求（自由文本）
    token: Annotated[str, InjectedToolArg] = "",
) -> str:                               # 返回 {"success", "changes", "summary"}
```

### LLM 两处被调用（均为一次性、结构化输出，用 `get_json_llm`）

| 工具            | 输入                          | 输出                                             |
| ------------- | --------------------------- | ---------------------------------------------- |
| **analyze_resume** | 简历 markdown（+ 可选 JD）         | `{analysis, questions}`                         |
| **polish_resume**  | JD（可选）+ 简历 markdown + extra_info | `{revisedContent, changes[]}`（内部用，不回传完整稿） |

## 四、工具行为详解

### analyze_resume：简历分析专家

- 拉简历：指定 `profile_id` → `GET /users/me/profile/{profileId}`；未指定 → `GET /users/me/profile`（最新一份）。
- 有 `job_id` → 再拉 JD（`GET /jobs/{jobId}`），分析增加「JD 契合度」维度。
- 一次 LLM 诊断，从 4 个维度给文字评估：
  1. **语言表达**：用词是否准确专业，有无口语化/空话套话；
  2. **结构条理**：段落组织、信息层次是否清晰，重点是否突出；
  3. **内容完整性**：教育/实习/项目/技能是否完整，有无可量化成果；
  4. **JD 契合度**（有 JD 时）：与 JD 关键技能/经验的匹配与差距。
- 返回 `{"analysis": "...", "questions": "..."}`：`analysis` 为可直接展示的诊断文本（≤300 字）；`questions` 为 1~3 个对用户的追问（如「你希望针对什么岗位优化？」），**无需追问则为空字符串**。
- 触发时机由工具 docstring 约束：用户表达「看简历 / 分析简历 / 评估简历」等意图时调用；**不**修改简历。

### polish_resume：简历润色专家

- 拉简历（同上）；有 `job_id` → 拉 JD。
- 一次 LLM 润色：`POLISH_SYSTEM`（保留 v3 的防幻觉红线 + reflect 自检）+ 输入 `JD(可选) + 简历 + extra_info`。
- **直接 `save_profile_as_new(revisedContent)` 另存为新简历**（profileId 传空，Java 落库时生成新 UUID），不询问「是否开始 / 是否保存」。
- 返回 `{"success": true, "changes": ["简短改动说明1", "简短改动说明2"], "summary": "已另存为一份新简历（原简历保留），主要变更：1…2…3…"}`——`changes` 是**短字符串数组**（每条 ≤30 字，说明改了什么、为什么），**对话里只展示变更摘要 + 提示**，不回传完整 markdown（避免主 LLM 用 max_tokens=2048 流式输出超长简历被截断）。
- `extra_info`：主 LLM 汇总的「分析阶段用户对追问的回答 + 本次修改要求」自由文本，可为空（仅按 JD 润色）。
- ⚠️ `save_profile_as_new` 的响应拿不到新简历 UUID（Java 端回显 userId），工具响应中不声明返回新 profileId。

## 五、实施清单

| 文件                                     | 改动                                                                                                                                                                                                                                      |
| -------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app/tools.py`                         | 删除 `start_resume_polish`、`save_resume_edit`；新增 `analyze_resume`、`polish_resume`（docstring 即工具描述，写明触发时机与 `profileId:` 抽取约定）；`ALL_TOOLS = [get_student_profile, recommend_specific_jobs, query_job_detail, analyze_resume, polish_resume]`；移除 flow 导入 |
| `app/services/resume_polish.py`        | 删除 `gather()`/`rewrite_resume()` 及 `GATHER_SYSTEM`/`REWRITE_SYSTEM`/`GATHER_MAX_ROUNDS`；新增 `ANALYZE_SYSTEM` + `analyze(job, resume)`；`polish(job, resume, history)` → `polish(job, resume, extra_info)`（JD 段可选）；保留全部 HTTP helper |
| `app/routers/chat.py`                  | 删除 `_run_polish_flow`、`_FLOW_OPTIONS`、关键词判定、`_format_polish_result`、`_sse_chunks`；删除 `stream_message` 内 Redis 状态分支、`start_resume_polish` 特殊分支、`resumeFlow` 字段；工具循环回归通用路径 |
| `app/services/flow.py`                 | **整文件删除**（状态机作废）                                                                                                                                                                        |
| `app/main.py` / `config.py` / `requirements.txt` | 移除 Redis 连接与 `redis_url` 配置、`redis>=5` 依赖（ai-service-py 中 Redis 仅服务于状态机）                                                                                                                                         |
| 前端 `GlobalAssistantWidget.vue` / `home.ts` | 删除 `resumeFlow` 选项卡卡片、`handleResumeFlowOption`/`submitResumeFlowSupplement`/`cancelResumeFlowSupplement` 及相关 CSS、`resumeFlow` 类型定义 |
| `简历润色专家subagent.md`                | 本文档（v4）                                                                                                                                                                                                                                   |

## 六、关键设计决策

1. **主 LLM 是唯一的调度员**：判断意图、抽取参数、跨多轮记忆澄清答案、决定调用哪个工具。服务端不存任何流程状态——比 v3 简单，代价是可靠性依赖主 LLM（详见风险 1）。
2. **同步工具、一次调用一次返回**：不新增 SSE 事件、不改前端、无跨消息隐藏状态。
3. **分析可附带追问、但不强制**：`questions` 为空也正常。主 LLM 可以自己决定要不要追问（无 JD 时建议追一句目标岗位）。
4. **润色一律另存为新简历**：原简历永不覆盖；不再问「开始吗 / 保存吗」。
5. **对话只展示变更摘要**：润色工具的返回不含完整 markdown，规避主 LLM 流式输出超长简历被 `max_tokens=2048` 截断。
6. **Reflect 兜底幻觉**：润色 prompt 沿用 v3 的防幻觉红线 + reflect 自检（只能重组、扩写用户明确提供的内容，不虚构公司/项目/量化数字）。
7. **jobId 仅从对话抽取**：不加岗位解析工具（MVP）。用户只说岗位名而没有 `jobId:xxx` 时，分析/润色做通用处理（无 JD 段）。
8. **润色延迟控制（实测驱动的三管齐下）**：polish 曾出现 LLM 调用 40~75s「卡住」。实测定位两个因素——(a) `get_json_llm` 不传 `extra_body` 时 deepseek-v4-flash 默认仍做隐藏推理（结构化输出延迟约翻倍）；(b) 原 `POLISH_SYSTEM` 要求输出 `changes:[{before,after,reason}]` + `reflectChecklist`，等于让模型把改动全文在 JSON 里再生成一遍（实测 1314 字符输入要 34.9s）。对策：① `get_json_llm` 显式传 `extra_body={"thinking":{"type":"disabled"}}` 并放宽 timeout 到 150s；② `POLISH_SYSTEM` 精简输出 schema——`changes` 改短字符串数组、`reflectChecklist` 移出输出（reflect 仍作系统指令约束行为，只不自检不自证）；③ `_call_json_llm` 用 `asyncio.wait_for(150s)` 做硬超时兜底（langchain-openai 的 timeout 参数在 async 下可能失效），保证工具永不无限挂起。精简后同一输入从 34.9s 降到 **5.8s**。

## 七、风险与取舍

1. **上下文传递依赖主 LLM（相比 v3 的主要退让）**：分析阶段用户回答的澄清问题，需要主 LLM 在调润色工具时汇总进 `extra_info`。若主 LLM 漏带，润色退化为「仅按简历+JD 改写」——这是可接受的降级，不会坏数据。缓解：工具 docstring 明确要求「汇总对话中用户提供的全部相关补充信息」。
2. **幻觉风险**：LLM 重写简历易凭空补经历/数字。prompt 红线 + reflect 自检两道防线（v3 的「用户确认后写库」防线取消，改为另存新简历隔离风险——即使有误，原简历仍在）。
3. **超长简历被截断**：润色 LLM 用 `get_json_llm`（max_tokens=4096），极端超长简历可能输出不完整；工具校验 `revisedContent` 非空，落库前对长度做防御性检查（可选）。
4. **「另存新简历」产生副本堆积**：每次润色都新增一份简历，长期可能产生多份副本；用户可手动清理，或后续加「覆盖原简历」的显式选项。

## 八、V2 展望：优秀简历 RAG 参考

（沿用 v3，未变）当前向量库仅有 `job_category_vector` / `job_detail_vector` 两张岗位表，无简历语料。后续可引入优秀简历样本向量化，润色阶段作为**表达参考**——只借鉴句式/结构/量化方式，不借用样本经历与数字（防幻觉红线）。

## 九、验证路径

本地启动 ai-service-py 后走通：

```
主对话：「帮我看下我的简历」
  → 主 LLM 调 analyze_resume → 流式转述分析文本（可能附带 1~3 个追问）

用户：「目标岗位是 Java 后端开发，实习做过接口压测优化，帮我针对这个岗位优化简历[jobId:42]」
  → 主 LLM 调 polish_resume(profile_id?, job_id="42", extra_info="目标岗位Java后端；实习做过接口压测优化")
  → 工具润色并另存为新简历 → 返回变更摘要 + 「已另存为一份新简历（原简历保留）」
  → GET /users/me/profile/list 确认新简历已落库、原简历保留

另验证：
- 只分析不改：用户只说「分析一下简历」，主 LLM 不调 polish_resume、简历不变；
- 无 jobId：用户说「帮我优化简历」但无岗位，润色做通用改写（无 JD 段）；
- 回归：普通问答、「推荐岗位」仍走通；对话中不再出现 resumeFlow 选项卡卡片。
```
