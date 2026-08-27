# 简历润色专家 Subagent 方案

> 状态：方案设计（待实施）
> 更新：2026-08-27（v2：改为「subagent 出题 + 主 agent 传话」，RAG 优秀简历列为 V2 展望）
> 落点：`fc2026/ai-service-py`（Python AI 服务，前端主 agent 当前使用）

## 一、背景与目标

简历已由「结构化字段」改为「markdown 原文」存储（`StudentProfile = {id, content}`），恰好成为简历润色最理想的输入。本方案新增一个**简历润色专家 subagent**：

- 读取用户当前收藏岗位的完整信息（JD）
- 依据岗位要求向用户提出少量关键澄清问题（**由 subagent 出题，主 agent 转述**）
- 根据用户答复修改用户当前的简历（markdown 原文）
- 修改后执行 **reflect 自检**，确保不丢事实、不产生幻觉、对齐岗位关键词

subagent 不作为独立入口，而是**作为工具被主 agent（`chat.py` 工具调用循环）调用**，无状态。

## 二、数据基础（均已存在）

| 需求 | 来源 | 接口 |
|---|---|---|
| 收藏岗位列表 | career-service | `GET /users/me/favorite-jobs` → `FavoriteRes{total, list:[{jobId, jobName, city, ...}]}` |
| 完整岗位 JD | career-service | `GET /jobs/{jobId}` → `JobDocument`（含 `jobDescription`、`abilityRequirements`） |
| 当前简历 markdown | profile-service | `GET /users/me/profile` → `{hasProfile, profile: {id, content}}` |
| 写回简历 | resume-parser-service | `POST /users/me/profile` body `{content: "markdown"}` → `profile_storage` 队列落库 → 自动触发重新评分 |

> ⚠️ 收藏列表只返回岗位摘要，**完整 JD 需按 `jobId` 再调一次 `GET /jobs/{jobId}`**。

## 三、总体架构：Subagent 出题 + 主 Agent 传话

核心思路：**对话始终发生在主对话**（用户只和主 agent 说话），但「问什么」由 subagent 的专业 prompt 决定，主 agent 只做转述与编排。subagent 每次工具调用无状态，`stage` 由参数显式传入，问答历史通过参数累积传递。

```
主 agent
  ├─ 用户："我想去这个岗位"
  ├─ 调 resume_polish_expert { jobId, stage: "ask" }
  │     └─ 内部：get_favorite_jobs / get_job_detail / get_student_profile
  │     └─ 返回 { stage:"ask", diagnosis:"...", questions:[{id, question, options?}] }   // 最多 2~3 个
  ├─ 主 agent 转述问题 → 用户回答（新消息）
  ├─ 再调 resume_polish_expert { jobId, stage:"ask", answers:[{id, answer}] }
  │     └─ 返回下一批问题，或 { stage:"ready", summary:"信息已够用" }
  ├─ 用户说"开始修改" → 调 resume_polish_expert { jobId, stage:"polish", answers:[...] }
  │     └─ 内部：原简历 + 用户答复 + JD → 修订版 markdown
  │     └─ 返回 { revisedContent, changes[], reflectChecklist[] }
  └─ 主 agent 展示 diff → 用户确认 → 调现有 POST /users/me/profile {content} 写库 → 自动重新评分
```

关键技术点：

1. **工具循环上限不受影响**：`chat.py` 的 `for _ in range(5)` 是**每一条用户消息的回复内**独立计数。用户每回答一次就是一条新消息、一次新的流式回复，其中只调 1 次 subagent，5 轮预算绰绰有余。
2. **状态靠参数传递**：subagent 保持无状态，靠 `answers` 参数累积问答历史；每次被调用时内部重跑「JD vs 简历+已收集回答」的差距分析，保证下一个问题基于最新上下文、不重复不跑偏。
3. **结构化传参，不传原始对话**：主 agent 把问答消化成 `{id, answer}` 键值对传给 subagent，而非转储整段对话——省 token、抓重点、压低幻觉。

## 四、状态流（Subagent 两阶段）

### 阶段 1：ask（出题）
1. **拉取数据**：`get_favorite_jobs`（让用户选或取最近收藏）→ `get_job_detail(jobId)`（完整 JD）→ `get_student_profile`（简历 markdown 原文）。
2. **诊断 + 提问**：对比「简历 vs JD」，输出差距诊断 + **最多 2~3 个问题**，优先问「JD 要求但简历缺失/证据不足」的点，每个问题可附带选项，降低用户回答成本。
3. **判断收尾**：若信息已足够（`stage: "ready"`），提示主 agent 可进入修改；主 agent 应主动向用户确认一次（"补充这些后我可以开始改，要开始吗？"），防止用户信息没问全就催促修改。

### 阶段 2：polish（修改 + Reflect）
4. **修改简历**：用「原简历 + 用户答复 + JD」产出修订版 markdown，结构化输出 `{revisedContent, changes[]}`（changes 记录每处变更点，供前端 diff 展示）。
5. **Reflect 自检**：对修订稿执行审查 pass，逐项核对：
   - 事实是否保留（不丢原有经历、数字、时间）
   - 是否编造经历/量化数字（幻觉检查）
   - 是否命中 JD 关键词与能力要求
   - 是否遗漏原简历内容
   输出 `reflectChecklist`；若自检不通过，模型自行再修一轮（prompt 强制）。
6. **用户确认落库**：展示 diff → 用户确认 → `POST /users/me/profile {content}` 写回 → 复用现有 `profile_storage → eval_storage` 队列自动重新评分。

## 五、实施清单（改动集中在 Python 端）

| 文件 | 改动 |
|---|---|
| `app/tools.py` | 新增 3 个工具：`get_favorite_jobs`、`get_job_detail`、`resume_polish_expert`（subagent 本体），注册进 `ALL_TOOLS` |
| 新增 `app/services/resume_polish.py` | 润色核心：ask / polish / reflect 三段 prompt + 结构化 JSON 解析（强制「不增删事实」红线） |
| `app/routers/chat.py` | 基本不用动（工具循环天然支持）；可选：在 `_summarize_tool_result` 增加润色结果摘要分支 |

工具内 HTTP 调用参考 `tools.py` 中 `get_student_profile` 的写法：透传用户 JWT（`Authorization: Bearer <token>`），经网关访问 Java 服务。

## 六、关键设计决策

1. **提问由 subagent 产出**：问题质量由专用 prompt 保证（按 JD 能力要求逐项比对），主 agent 只转述，降低主 agent prompt 复杂度。
2. **一次最多 2~3 个问题**：避免审问式体验；每个问题尽量带可选答案。
3. **Reflect 兜底幻觉**：polish 输出必须带 `reflectChecklist`；自检不通过则模型自行再修，这是对抗 LLM 重写简历产生幻觉的核心防线。
4. **防幻觉红线**：subagent system prompt 硬性要求「只能重组、扩写用户明确承认的内容，不得虚构公司、项目、量化数字」。
5. **覆盖保护**：修订稿先 diff 展示、用户确认后才写库，不直接覆盖原简历。
6. **评分联动**：写回后复用现有队列链路自动重新评分，无需新逻辑。

## 七、风险与取舍

1. **幻觉风险（最高优先级）**：LLM 重写简历易凭空补经历/数字。prompt 红线 + reflect 自检 + 用户确认三道防线。
2. **工具循环轮次上限**：`chat.py` 当前 `for _ in range(5)` 按「每条消息的回复」计数，ask/polish 每轮只消耗 1 次调用，不构成瓶颈；若后续出现单条消息内多次调用（如同时对比多岗位），再考虑上调上限。
3. **无状态 + 参数累积**：问答历史靠 `answers` 参数传递，多轮后 token 成本线性增长，但单次会话仅 2~3 个问题，成本可控。
4. **主 agent 对话管理**：主 agent 需判断「用户是回答当前问题还是岔开话题」，通过主 agent system prompt 约束。

## 八、V2 展望：优秀简历 RAG 参考

当前向量库仅有 `job_category_vector` / `job_detail_vector` 两张岗位表，**无简历语料**。V2 可引入：

1. 采集优秀简历样本（脱敏的历史高分简历 / 人工整理样本）；
2. 向量化存入新增表（如 `resume_example_vector`）；
3. polish 阶段检索「相关优秀简历」作为**表达参考**。

> ⚠️ 定位必须为「表达参考」而非「内容来源」：只借鉴句式、结构、量化方式，**不得借用样本中的经历与数字**，否则突破防幻觉红线。

## 九、验证路径

本地启动 ai-service-py 后走通：

```
主对话："帮我针对收藏的岗位优化简历"
  → 工具 ask 输出诊断 + 2~3 个问题
  → 用户回答
  → 工具 ask 输出下一批问题 / ready
  → 用户说"开始修改"
  → 工具 polish 输出修订稿 + changes + reflectChecklist
  → 用户确认 → 写库 → 轮询评分完成
```
