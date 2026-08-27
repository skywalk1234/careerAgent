# 简历润色专家 Subagent 方案

> 状态：方案设计（待实施）
> 日期：2026-08-27
> 落点：`fc2026/ai-service-py`（Python AI 服务，前端主 agent 当前使用）

## 一、背景与目标

简历已由「结构化字段」改为「markdown 原文」存储（`StudentProfile = {id, content}`），恰好成为简历润色最理想的输入。本方案新增一个**简历润色专家 subagent**：

- 读取用户当前收藏岗位的完整信息（JD）
- 依据岗位要求向用户提出少量关键澄清问题
- 根据用户答复修改用户当前的简历（markdown 原文）
- 修改后执行 **reflect 自检**，确保不丢事实、不产生幻觉、对齐岗位关键词

subagent 不作为独立入口，而是**作为工具被主 agent（`chat.py` 工具调用循环）调用**。

## 二、数据基础（均已存在）

| 需求 | 来源 | 接口 |
|---|---|---|
| 收藏岗位列表 | career-service | `GET /users/me/favorite-jobs` → `FavoriteRes{total, list:[{jobId, jobName, city, educationRequirement, salaryNormalized, favoritedAt}]}` |
| 完整岗位 JD | career-service | `GET /jobs/{jobId}` → `JobDocument`（含 `jobDescription`、`abilityRequirements`，存 ES，见 `SaveJobService.queryJobById`） |
| 当前简历 markdown | profile-service | `GET /users/me/profile` → `{hasProfile, profile: {id, content}}` |
| 写回简历 | resume-parser-service | `POST /users/me/profile` body `{content: "markdown"}` → `profile_storage` 队列落库 → 自动触发重新评分 |

> ⚠️ 收藏列表接口只返回岗位摘要，**完整 JD 需按 `jobId` 再调一次 `GET /jobs/{jobId}`**。

## 三、总体架构：无状态 Subagent + 主对话编排

核心思路：**「询问用户」环节由主 agent 在主对话里完成**，subagent 只负责「拿数据 → 分析 → 改稿 → reflect」。每次工具调用无状态，`phase` 由参数显式传入，避免维护子会话状态。

```
主 agent（chat.py 工具循环）
  │
  ├─→ 调用工具 resume_polish_expert
  │     { jobId, phase: "diagnose" }               ← 首次：只诊断 + 提问
  │     └─ 内部：get_favorite_jobs / get_job_detail / get_student_profile
  │             → 输出 { diagnosis, questions: [{id, question, options?}] }
  │
  ├─→ 主 agent 把 questions 转述给用户，用户回答（新 user 消息，进入会话历史）
  │
  ├─→ 再次调用 resume_polish_expert
  │     { jobId, phase: "polish", userAnswers: {q1: "...", q2: "..."} }
  │     └─ 内部：原简历 + 用户答复 + JD → 修订版 markdown
  │             + reflect 自检 → 输出 { revisedContent, changes[], reflectChecklist[] }
  │
  └─→ 主 agent 展示修订 diff，用户确认后
        → 调用现有 POST /users/me/profile {content} 写库 → 自动重新评分
```

## 四、状态流（含 Reflect）

1. **拉取数据**：`get_favorite_jobs` → 让用户选择或取最近收藏；`get_job_detail(jobId)` → 完整 JD；`get_student_profile` → 简历 markdown 原文。
2. **诊断 + 提问**（phase=diagnose）：LLM 对比「简历 vs JD」，输出差距诊断；**只问 2~3 个最关键澄清问题**，优先问「JD 要求但简历缺失/证据不足」的点，每个问题可附带选项，降低用户回答成本。
3. **修改简历**（phase=polish）：LLM 用「原简历 + 用户答复 + JD」产出修订版 markdown，结构化输出 `{revisedContent, changes[]}`（changes 记录每处变更点，供前端 diff 展示）。
4. **Reflect 自检**：对修订稿执行一次审查 pass，逐项核对：
   - 事实是否保留（不丢原有经历、数字、时间）
   - 是否编造经历/量化数字（幻觉检查）
   - 是否命中 JD 关键词与能力要求
   - 是否遗漏原简历内容
   输出 `reflectChecklist`；若自检不通过，模型自行再修一轮（prompt 强制）。
5. **用户确认落库**：展示 diff → 用户确认 → `POST /users/me/profile {content}` 写回 → 复用现有 `profile_storage → eval_storage` 队列自动重新评分。

## 五、实施清单（改动集中在 Python 端）

| 文件 | 改动 |
|---|---|
| `app/tools.py` | 新增 3 个工具：`get_favorite_jobs`、`get_job_detail`、`resume_polish_expert`（subagent 本体），注册进 `ALL_TOOLS` |
| 新增 `app/services/resume_polish.py` | 润色核心：diagnose / polish / reflect 三段 prompt + 结构化 JSON 解析（强制「不增删事实」红线） |
| `app/routers/chat.py` | 基本不用动（工具循环天然支持）；可选：在 `_summarize_tool_result` 增加润色结果摘要分支 |

工具内 HTTP 调用参考 `tools.py` 中 `get_student_profile` 的写法：透传用户 JWT（`Authorization: Bearer <token>`），经网关访问 Java 服务。

## 六、关键设计决策

1. **询问策略**：diagnose 一次只产出 2~3 个问题，给可选答案，主 agent 一次只转述这些问题，不铺开闲聊。
2. **Reflect 兜底幻觉**：polish 输出必须带 `reflectChecklist`；自检不通过则模型自行再修，这是对抗 LLM 重写简历产生幻觉的核心防线。
3. **防幻觉红线**：subagent system prompt 硬性要求「只能重组、扩写用户明确承认的内容，不得虚构公司、项目、量化数字」。
4. **覆盖保护**：修订稿先 diff 展示、用户确认后才写库，不直接覆盖原简历。
5. **评分联动**：写回后复用现有队列链路自动重新评分，无需新逻辑。

## 七、风险与取舍

1. **幻觉风险（最高优先级）**：LLM 重写简历易凭空补经历/数字。prompt 红线 + reflect 自检 + 用户确认三道防线。
2. **工具循环轮次上限**：`chat.py` 当前 `for _ in range(5)`。润色常规需「诊断 + 润色」2 次调用，若用户中途追问岗位信息可能触发 2~3 次；如需更从容可把上限提到 8~10。
3. **无状态 vs 有状态**：采用无状态（phase 显式传入），简单可靠；若后续需要跨轮记忆（如多岗位对比），再引入 Redis 状态。

## 八、验证路径

本地启动 ai-service-py 后走通：

```
主对话："帮我针对收藏的岗位优化简历"
  → 工具 diagnose 输出诊断 + 问题
  → 用户回答
  → 工具 polish 输出修订稿 + changes + reflectChecklist
  → 用户确认 → 写库 → 轮询评分完成
```
