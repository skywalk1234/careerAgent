# 除 ai-service（Python）外的接口文档

> 本文档整理 Java 微服务中**与「学生简历 / 学生画像」相关的接口**，主要覆盖 **profile-service（学生画像与简历存储服务）**。
> 其它模块（user-service、resume-parser-service 简历解析、career-service）相关的接口不在此文档范围，如有需要另行整理。

## 模块概览

| 项    | 说明                                                                                 |
| ---- | ---------------------------------------------------------------------------------- |
| 服务名  | `profile-service`（在 Nacos 中注册，经网关 `lb://profile-service` 访问）                       |
| 技术栈  | Java 17 + Spring Boot + MyBatis-Plus + RabbitMQ                                    |
| 数据库  | MySQL `user_profile` 库（`192.168.118.130`，JDBC 配置取自 Nacos `shared-jdbc.yaml`）       |
| 消息队列 | RabbitMQ `192.168.118.130:5672`（itheima/123），消费队列：`profile_storage`、`eval_storage` |
| 职责   | 学生画像 / 简历数据的**存储与查询**、GitHub 开源加分、评分数据的存取                                          |
| 对外访问 | 前端一律通过网关 `http://localhost:8080`（JWT 校验），网关按 Path 路由到本服务                           |

### 网关路由到本服务的路径

网关 `gateway-service/application.yaml` 中与本服务相关的路由（order 敏感，改路由需注意顺序）：

| order | 路由 id                | Path                                                                                   | 目标                    |
| ----- | -------------------- | -------------------------------------------------------------------------------------- | --------------------- |
| 0     | `resume-parser-user` | `/users/me/profile/parse-jobs`（精确）、`/users/me/profile/parse-image`、`/users/me/home/**` | resume-parser-service |
| 1     | `profile`            | `/users/me/profile/**`、`/users/me/profile/delete`                                      | **profile-service**   |

> ⚠️ **注意**：
> 
> 1. `GET /users/me/profile/parse-jobs/{parseJobId}`（查询解析结果）不命中 order 0 的**精确匹配** `/users/me/profile/parse-jobs`，会被 order 1 的 `/users/me/profile/**` 路由到 **profile-service**；而 `POST /users/me/profile/parse-jobs`（上传简历）会精确命中 order 0 路由到 **resume-parser-service**。两者是不同服务的接口。
> 2. `/users/me/open-source/**`（GitHub 加分接口）的 Controller 实现在本服务，但网关当前**没有**为它单独配置路由，`/users/me/**` 会命中 career-service 的 `career-user` 路由（order 3）。如需让这些接口正确到达 profile-service，需在网关补充 `/users/me/open-source/**` 路由。

### 认证方式

所有 `/users/me/**` 路径经网关 JWT 校验后放行；ProfileController 通过 `UserContext.getUser()`（ThreadLocal，由网关透传）取当前登录用户 id；GithubController 通过请求头 `X-User-Id` 取用户 id（缺省时回退为 `111`）。

### 统一响应格式

所有 HTTP 接口返回 `{code, msg, data}` 三层结构（`group.common.Result<T>`）：

```json
{ "code": 200, "msg": "success", "data": { ... } }
```

- `code=200` 表示成功；业务错误如 `400`（参数错误）、`500`（服务端错误）。
- 下文各接口中**只描述 `data` 字段的结构**。

---

## 一、接口总览

### A. 学生画像 / 简历（ProfileController）

| 方法  | 路径                                              | 说明                  |
| --- | ----------------------------------------------- | ------------------- |
| GET | `/users/me/profile`                             | 获取学生画像（含评分、证据、改进建议） |
| GET | `/users/me/profile/parse-jobs/{parseJobId}`     | 查询简历解析任务是否完成        |
| GET | `/users/me/profile/analyze-jobs/{analyzeJobId}` | 查询画像分析（评分）状态        |
| GET | `/users/me/profile/delete`                      | 删除旧简历和旧评分（重新上传前调用）  |

### B. GitHub 开源加分（GithubController，前缀 `/users/me/open-source`）

| 方法   | 路径                               | 说明               |
| ---- | -------------------------------- | ---------------- |
| GET  | `/users/me/open-source/auth-url` | 获取 GitHub 授权跳转地址 |
| GET  | `/users/me/open-source/callback` | 授权回调，换取统计结果并落库   |
| GET  | `/users/me/open-source/summary`  | 获取开源统计与加分摘要      |
| POST | `/users/me/open-source/unbind`   | 解绑 GitHub 授权     |

### C. 内部消息队列消费者（非 HTTP 接口）

| 队列                | 说明                                  |
| ----------------- | ----------------------------------- |
| `profile_storage` | 接收简历解析结果 JSON，保存/更新 `resume_full` 表 |
| `eval_storage`    | 接收评分结果 JSON，保存/更新 `ability_score` 表 |

---

## 二、数据模型定义

### StudentProfile（学生画像 / 简历）

`group.dto.StudentProfile`，存储于 `resume_full.resume_data`（JSON 列）。**2026-08-27 起由结构化字段改为 markdown 原文**，`content` 为带 markdown 语法的简历原始文本，完整保留简历内容，便于后续简历润色优化：

```json
{
  "id": 1001,
  "content": "## 张三\n\n**基本信息**\n- 电话：13800138000\n- 邮箱：zhangsan@example.com\n- 求职意向：后端开发\n\n### 教育背景\n- 武汉大学 | 软件工程 | 本科 | 2022-09 至 2026-06\n\n### 项目经历\n- 大学生职业规划平台：负责后端接口开发……"
}
```

> ⚠️ 原结构化字段（basicInfo/education/workExperience/skills/certificates/organizeExp/projects/selfEvaluation）已全部废弃。
> 由于画像不再有结构化字段，**career-service 的 ProfileSnapshot（职业路径报告中的画像快照）功能已暂停**。

### 评分结果 ResumeEvaluationResult

`group.dto.ResumeEvaluationResult`，存储于 `ability_score.scores_data`（JSON 列）：

```json
{
  "scores": {
    "completenessScore": 85,
    "competitivenessScore": 78,
    "abilityScores": { "professionalSkill": 70, "certificate": 60, "innovation": 55, "internalMotivation": 65, "learning": 80, "stressTolerance": 60, "communication": 72, "internship": 50, "language": 68, "leadership": 55, "adaptability": 62, "execution": 75 },
    "bonusByDimension": { "professionalSkill": 0, "certificate": 0, "innovation": 0, "internalMotivation": 0, "learning": 0, "stressTolerance": 0, "communication": 0, "internship": 0, "language": 0, "leadership": 0, "adaptability": 0, "execution": 0 }
  },
  "evidence": {
    "professionalSkill": ["熟练使用 Java 与 Spring Boot"],
    "learning": ["获得国家奖学金"]
  },
  "improvementSuggestions": [
    { "dimension": "internship", "priority": "high", "advice": "建议补充一段实习经历……" }
  ]
}
```

12 个能力维度字段（`AbilityScores`）：`professionalSkill`（专业技能）、`certificate`（证书）、`innovation`（创新）、`internalMotivation`（内驱动力）、`learning`（学习）、`stressTolerance`（抗压）、`communication`（沟通）、`internship`（实习）、`language`（语言）、`leadership`（领导）、`adaptability`（适应）、`execution`（执行）。

### GetProfileResponse（GET /users/me/profile 的 data）

```json
{
  "hasProfile": true,
  "profileId": "1001",
  "profile": { "id": 1001, "content": "## 张三\n\n**基本信息**\n- 电话：13800138000\n…（markdown 简历文本）" },
  "scores": { "completenessScore": 85, "competitivenessScore": 78, "abilityScores": {}, "bonusByDimension": {} },
  "evidence": { "learning": ["获得国家奖学金"] },
  "improvementSuggestions": [ { "dimension": "internship", "priority": "high", "advice": "……" } ],
  "openSourceBonus": { "provider": null, "totalBonus": null, "bonusDetails": null, "note": null },
  "updatedAt": "2026-08-27T10:30:00"
}
```

---

## 三、接口详情

### 1. 获取学生画像

- **接口**：`GET /users/me/profile`
- **功能**：查询当前登录用户的完整画像（简历）、评分结果、证据与改进建议。
- **请求参数**：

| 参数     | 位置    | 必填  | 说明                                                  |
| ------ | ----- | --- | --------------------------------------------------- |
| userId | query | 否   | 指定用户 id；缺省时取 `UserContext`（JWT）中的用户 id，仍为空则默认 `111` |

- **响应 data**（`GetProfileResponse`）：

| 字段                     | 类型                                  | 说明                                                        |
| ---------------------- | ----------------------------------- | --------------------------------------------------------- |
| hasProfile             | boolean                             | 是否已存在画像                                                   |
| profileId              | string                              | 用户 id                                                     |
| profile                | StudentProfile \| null              | 画像数据（`{id, content}`，content 为 markdown 简历文本）；无画像时为 null  |
| scores                 | Scores \| null                      | 评分（完整度、竞争力、12 维能力、各维度加分）；无评分为 null                        |
| evidence               | map<string, list\<string\>> \| null | 评分证据                                                      |
| improvementSuggestions | list\<Suggestion\> \| null          | 改进建议（dimension / priority / advice）                       |
| openSourceBonus        | object \| null                      | 开源加分（当前实现恒为 `new OpenSourceBonus()`，各字段为 null，**该功能待开发**） |
| updatedAt              | string                              | 更新时间（ISO 格式，`LocalDateTime.now().toString()`）             |

- **示例**：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "hasProfile": true,
    "profileId": "1001",
    "profile": { "id": 1001, "content": "## 张三\n\n### 教育背景\n- …（markdown 简历文本）" },
    "scores": { "completenessScore": 85, "competitivenessScore": 78 },
    "evidence": { "learning": ["获得国家奖学金"] },
    "improvementSuggestions": [],
    "openSourceBonus": null,
    "updatedAt": "2026-08-27T10:30:00.123"
  }
}
```

- **实现说明**：数据源为 `resume_full` 表（画像）与 `ability_score` 表（评分），均按 `user_id` 查询。

---

### 2. 查询简历解析任务是否完成

- **接口**：`GET /users/me/profile/parse-jobs/{parseJobId}`
- **功能**：简历上传后前端轮询本接口，判断解析是否完成。`parseJobId` 语义上即**用户 id**（本服务按 `user_id` 查询 `resume_full` 表）。
- **请求参数**：

| 参数         | 位置   | 必填  | 说明              |
| ---------- | ---- | --- | --------------- |
| parseJobId | path | 是   | 解析任务 id（即用户 id） |

- **响应 data**（两种形态）：

**① 解析未完成**（`resume_full` 中无该用户记录），返回 `ResumeNullRes`：

```json
{ "parseJobId": "1234567890", "status": "processing", "progress": 99, "pollAfterMs": 2000 }
```

**② 解析完成**，返回 `QueryResumeRes`：

```json
{
  "parseJobId": "1234567890",
  "status": "success",
  "result": {
    "parsedProfile": { "id": 1234567890, "content": "## 张三\n\n### 教育背景\n- …（markdown 简历文本）" },
    "missingFields": [],
    "sourceMeta": { "fileName": "张三-简历.pdf", "fileType": "application/pdf" }
  }
}
```

`result` 字段说明：

| 字段            | 类型             | 说明                                                                   |
| ------------- | -------------- | -------------------------------------------------------------------- |
| parsedProfile | StudentProfile | 解析出的画像（`{id, content}`，content 为 markdown 简历文本）                      |
| missingFields | list\<string\> | 缺失检测：简历内容（content）为空时返回 `["content"]`，否则为空数组 `[]`（原字段级缺失检测已随结构化字段废弃） |
| sourceMeta    | map            | 来源文件信息：`fileName`、`fileType`                                         |

---

### 3. 查询画像分析（评分）状态

- **接口**：`GET /users/me/profile/analyze-jobs/{analyzeJobId}`
- **功能**：保存简历后前端轮询本接口，判断 AI 评分是否完成。`analyzeJobId` 语义上即**用户 id**（按 `user_id` 查 `ability_score` 表最新记录）。
- **请求参数**：

| 参数           | 位置   | 必填  | 说明              |
| ------------ | ---- | --- | --------------- |
| analyzeJobId | path | 是   | 分析任务 id（即用户 id） |

- **响应 data**（两种形态）：

**① 分析中**（暂无评分记录）：

```json
{ "analyzeJobId": "1001", "status": "processing", "progress": 65, "pollAfterMs": 1200 }
```

**② 分析完成**（已存在评分记录）：

```json
{ "analyzeJobId": "1001", "status": "succeeded" }
```

> 注意：本接口**只返回状态**，不返回评分明细；评分明细通过 `GET /users/me/profile` 获取。

---

### 4. 删除旧简历和旧评分

- **接口**：`GET /users/me/profile/delete`
- **功能**：删除指定用户的画像（`resume_full`）和评分（`ability_score`）记录。在**重新上传/保存简历之前**先调用本接口，避免旧数据残留。
- **请求参数**：

| 参数     | 位置    | 必填  | 说明    |
| ------ | ----- | --- | ----- |
| userId | query | 是   | 用户 id |

- **响应 data**：`null`

```json
{ "code": 200, "msg": "success", "data": null }
```

- **实现说明**：同时调用 `saveEvalService.deleteEval(userId)` 与 `saveProfileService.deleteProfile(userId)`；即使某张表无记录也返回成功。

---

### 5. 获取 GitHub 授权跳转地址

- **接口**：`GET /users/me/open-source/auth-url`
- **功能**：生成 GitHub OAuth 授权跳转链接（scope=`user,repo`），返回给前端引导用户跳转。
- **请求头/参数**：

| 参数        | 位置     | 必填  | 说明                                     |
| --------- | ------ | --- | -------------------------------------- |
| provider  | query  | 是   | 提供商，目前仅支持 `github`（`gitee` 不支持，返回 400） |
| X-User-Id | header | 否   | 用户 id，缺省默认 `111`                       |

- **响应 data**（`GithubAuthUrlResponse`）：

```json
{
  "provider": "github",
  "state": "4f3a9c1d2b8e6f0a",
  "authUrl": "https://github.com/login/oauth/authorize?client_id=Ov23liEZ0WMlH9I6mXqb&state=4f3a9c1d2b8e6f0a&scope=user,repo"
}
```

- **异常**：`provider` 非 `github` → `{code: 400, msg: "不支持的提供商: xxx"}`。

---

### 6. GitHub 授权回调

- **接口**：`GET /users/me/open-source/callback`
- **功能**：GitHub 授权完成后跳回本接口，用 `code` 换 `access_token`，拉取用户信息/仓库/语言统计，计算开源加分，并：
  1. 保存授权信息到 `github_auth` 表（删除该用户旧授权记录后插入）；
  2. 将各维度加分累加写入 `ability_score.scores_data.bonusByDimension`。
- **请求头/参数**：

| 参数        | 位置     | 必填  | 说明                        |
| --------- | ------ | --- | ------------------------- |
| provider  | query  | 是   | 提供商，仅支持 `github`          |
| state     | query  | 是   | 授权态参数（与 `auth-url` 返回的一致） |
| code      | query  | 是   | 平台回调授权码                   |
| X-User-Id | header | 否   | 用户 id，缺省默认 `111`          |

- **响应 data**（`GithubCallbackResponse`）：

```json
{
  "authorized": true,
  "provider": "github",
  "accountName": "octocat",
  "contributionHeatmap": { "days": [ { "date": "2026-08-21", "count": 3 } ] },
  "languageStats": [ { "name": "Java", "value": 40 }, { "name": "JavaScript", "value": 30 } ],
  "bonusDetails": [
    { "dimension": "execution", "delta": 3, "reason": "近一年持续提交，项目推进节奏稳定" },
    { "dimension": "professionalSkill", "delta": 1, "reason": "掌握多种编程语言" }
  ],
  "totalBonus": 4,
  "note": "仅用于补充验证与可选加分，不授权不扣分"
}
```

加分维度映射：`execution`（执行力）、`internalMotivation`（内驱动力）、`professionalSkill`（专业技能）、`innovation`（创新）、`learning`（学习）。

---

### 7. 获取开源统计与加分摘要

- **接口**：`GET /users/me/open-source/summary`
- **功能**：查询当前用户 GitHub 授权与加分的摘要信息。
- **请求头**：`X-User-Id`（缺省默认 `111`）。
- **响应 data**（`GithubSummaryResponse`）：

**① 未授权**：

```json
{ "authorized": false, "note": "可选加分项：不授权不扣分" }
```

**② 已授权**：

```json
{
  "authorized": true,
  "provider": "github",
  "accountName": "octocat",
  "profileUrl": "https://github.com/octocat",
  "authorizedAt": "2026-08-27T10:30:00",
  "contributionHeatmap": { "days": [] },
  "languageStats": [],
  "bonusDetails": [],
  "totalBonus": 4,
  "note": "仅用于补充验证与可选加分，不授权不扣分"
}
```

---

### 8. 解绑 GitHub 授权

- **接口**：`POST /users/me/open-source/unbind`
- **功能**：删除该用户的 `github_auth` 授权记录，并将 `ability_score.scores_data.bonusByDimension` 的 12 个维度全部重置为 0（撤销加分）。
- **请求头**：`X-User-Id`（缺省默认 `111`）。
- **响应 data**：`null`

```json
{ "code": 200, "msg": "解绑成功", "data": null }
```

---

## 四、内部消息队列消费者（异步存储）

以下两个 `@RabbitListener` 为内部消费者，由 **resume-parser-service** 解析/评分完成后投递消息，前端不直接调用。

### 4.1 保存简历 — 队列 `profile_storage`

- **入参消息**（Map，Jackson 序列化）：

```json
{
  "userId": 1001,
  "profileData": "## 张三\n\n**基本信息**\n- 电话：13800138000\n…（markdown 简历文本）",
  "fileName": "张三-简历.pdf",
  "fileType": "application/pdf",
  "timestamp": 1780000000000
}
```

- **逻辑**：`profileData` 为 **markdown 简历文本字符串**（不再传结构化 JSON），直接组装为 `StudentProfile`（`id` 置为用户 id，`content` 置为 markdown 文本）；若 `resume_full` 中已存在该 `user_id` 则更新，否则插入（`created_at` 只在插入时设置）。

### 4.2 保存评分 — 队列 `eval_storage`

- **入参消息**（Map，复用 `profile_storage` 的 userId，`profileData` 替换为评分 JSON 字符串）：

```json
{
  "userId": 1001,
  "profileData": "{ \"scores\": {...}, \"evidence\": {...}, \"improvementSuggestions\": [...] }",
  "fileName": null,
  "fileType": null,
  "timestamp": 1780000000000
}
```

- **逻辑**：反序列化为 `ResumeEvaluationResult` 后写入 `ability_score`；若该用户已有评分记录则更新最新一条，否则插入。

---

## 五、端到端异步流程（简历上传 → 画像查询）

```
前端
  │  POST /users/me/profile/parse-jobs  (multipart, parseMode)
  ▼
resume-parser-service (gateway order 0)
  │  投递 queue=file_tran
  ▼
FileListener 监听 file_tran
  │  抽取文本 → AI 整理 Markdown 简历文本 → AI 评分 JSON
  ├─→ queue=profile_storage ──→ profile-service 存 resume_full 表
  └─→ queue=eval_storage   ──→ profile-service 存 ability_score 表
  │
前端轮询 GET /users/me/profile/parse-jobs/{userId}   （→ profile-service，见接口 2）
前端轮询 GET /users/me/profile/analyze-jobs/{userId} （→ profile-service，见接口 3）
前端获取 GET /users/me/profile                       （→ profile-service，见接口 1）
```

> 手动保存画像（`POST /users/me/profile`，实现在 resume-parser-service，请求体已改为 `{"content": "markdown 简历文本"}`，废弃原结构化表单）与图片解析（`POST /users/me/profile/parse-image`）同样最终走上述两个队列落库。

---

## 六、存储表说明

| 表名              | 关键字段                                                                                                                                                                       | 说明          |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| `resume_full`   | `id`、`user_id`（唯一）、`resume_data`(JSON，存 `{id, content}`，content 为 markdown 简历文本)、`file_name`、`file_type`、`created_at`、`updated_at`                                         | 学生画像/简历     |
| `ability_score` | `id`、`user_id`、`scores_data`(JSON)、`created_at`、`updated_at`                                                                                                               | 评分结果        |
| `github_auth`   | `user_id`、`account_name`、`profile_url`、`access_token`、`contribution_heatmap`(JSON)、`language_stats`(JSON)、`bonus_details`(JSON)、`total_bonus`、`authorized_at`、`created_at` | GitHub 授权记录 |

---

## 七、相关文件索引

- [ProfileController.java](profile-service/src/main/java/group/profileservice/controller/ProfileController.java) — 学生画像/简历 4 个 HTTP 接口 + 2 个 MQ 消费者
- [GithubController.java](profile-service/src/main/java/group/profileservice/controller/GithubController.java) — GitHub 开源加分 4 个接口
- [GithubService.java](profile-service/src/main/java/group/profileservice/service/GithubService.java) — OAuth 回调、加分计算与落库逻辑
- [QueryResumeService.java](profile-service/src/main/java/group/profileservice/service/QueryResumeService.java) / [QueryProfileService.java](profile-service/src/main/java/group/profileservice/service/QueryProfileService.java) / [SaveProfileService.java](profile-service/src/main/java/group/profileservice/service/SaveProfileService.java) / [SaveEvalService.java](profile-service/src/main/java/group/profileservice/service/SaveEvalService.java) — 查询与存储逻辑
- 数据模型：`group.dto.StudentProfile`、`group.dto.ResumeEvaluationResult`、`group.dto.GetProfileResponse`（common-service）
- 网关路由：gateway-service `application.yaml`（order 0/1 路由）
