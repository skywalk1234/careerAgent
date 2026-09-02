# 简历润色 RAG 方案（样例库 Few‑Shot 检索）

> 状态：总体规划稿（V1）　日期：2026-09-02
> 定位：为 `polish_resume` 的定向润色提供「同类岗位、同类项目」的历史专家点评作为 few‑shot 示范，提升润色质量，同时守住「不虚构」红线。

---

## 一、背景与目标

### 1.1 现状与痛点

当前 `polish_resume`（[app/tools.py](ai-service-py/app/tools.py)）的润色链路是：

```
JD(可选) + 简历原文 + 用户补充信息 → 专家提示词 → LLM 润色 → 另存新简历
```

润色效果依赖通用提示词规则（突出岗位匹配、量化成果等），缺少**同类岗位 / 同类项目「具体怎么改」的示范**。常见弱点是项目描述堆技术名词、套开源库模板、看不出设计思考——这类问题靠通用规则很难改好。

### 1.2 方案思路

小红书简历点评帖的天然结构是 **{简历，点评} 成对**：帖子给出简历，高赞评论是专家对该简历的批判性分析（技术深度、项目定位、设计思想、产品化……）。与其存「点评文字」让模型对着抽象观点发挥，不如**把成对的「简历片段 + 点评」存成样例**：

- 新来一份简历时，**用简历文本本身去向量检索**，召回目标岗位、项目类型相似的简历样例；
- 把样例里专家的点评作为 **few‑shot 示例**注入润色提示词，让模型看到「相似的简历 → 专家当时怎么评、往哪改」。

**关键点：向量匹配的是简历文本（输入侧），不是点评文字。** 这样点评再相似也不会互相干扰检索——匹配键是「简历像不像」，点评只是随样例带出的示范。同时模型学到的是**对具体简历的评判与改写迁移**，比抽象观点更有说服力。

> 不做缺陷（defectType）分类标签：缺陷类型难界定、易主观，且 few‑shot 的价值在迁移专家的评判标准（跨缺陷通用）。召回样例与当前简历缺陷不完全一致时，由模型自行判断并只借鉴适用部分。

### 1.3 目标

- 库内存「成对样例」（简历片段为检索键、点评为示范），不单独存点评文本。
- 检索按「简历相似 + 目标岗位一致」召回，取 3~5 条样例注入。
- 检索入口放在 `analyze_resume`（分析专家），`polish_resume` 纯消费 few‑shot 示例。

---

## 二、总体设计

```
                     ┌──────────────── 灌库（离线）────────────────┐
 小红书帖子{简历,点评} → 按「节」拆出简历片段 → 标 jobCategory/snippetType
                     → 人工筛选(curated) → embed 简历片段
                     └──────────────┬──────────────────────────────┘
                                    ▼
                          resume_example_vector
                          (content=简历片段, metadata.comment=点评)
                                    ▲
                     ┌──────────────┴──────────────── 检索（在线）─────────────┐
                     │  analyze_resume 诊断（判断当前简历问题，不产标签）          │
                     │  单路召回：embed 用户"待打磨片段" → 向量粗召回              │
                     │  → metadata 过滤 jobCategory 一致 → 3~5 条样例             │
                     └──────────────┬───────────────────────────────────────────┘
                                    ▼
  polish_resume ──→ 【历史案例 N】相似简历片段 + 专家点评(few-shot) → LLM 润色
                        （模型自行判断案例是否适用，不适用则忽略）
```

---

## 三、数据层设计

### 3.1 向量库与表

- **位置**：真实/线上向量库 `8.147.71.59:40086/ai-vector`（即 `config.py` 的 `vector_database_url`，与 `job_category_vector` / `job_detail_vector` 同库）。
- **嵌入**：DashScope `text-embedding-v1`（1536 维），与岗位表同维度。
- **表**：`resume_example_vector`，四列结构与 `job_detail_vector` 同构（Spring AI PgVectorStore 标准 schema），`metadata` 用 `JSON` 类型（对齐 `similarity_search` 的 `json.loads(r["metadata"])`）。

```sql
CREATE TABLE IF NOT EXISTS resume_example_vector (
    id UUID NOT NULL,
    content TEXT,                 -- 简历片段文本（唯一被 embedding 的部分）
    metadata JSON,                -- 见 3.3
    embedding VECTOR(1536),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS resume_example_vector_embedding_idx
    ON resume_example_vector
    USING HNSW (embedding vector_cosine_ops);
```

### 3.2 content：简历片段（检索键），点评不参与向量

- `content` 存**按节拆出的简历片段**（一个项目、一段经历、一段技能），可开头拼一行岗位方向增强区分度。
- **embedding 只打在这段简历文本上**——检索关心的是「简历像不像」。
- 粒度按「节」而非整份简历：整份 embed 太长语义糊；按节与 query（用户要打磨的片段）粒度对齐，命中更准。评估整体结构时再额外留少量「整简历」样例。

### 3.3 metadata 字段

| 字段 | 说明 | 示例 |
|---|---|---|
| `jobCategory` | 目标岗位大类（检索过滤维度，一帖/一例粗标，主观性低） | `Java后端`、`Agent开发`、`RAG/大模型应用` |
| `snippetType` | 片段类型（可选过滤） | `project` / `experience` / `skill` / `whole_resume` |
| `comment` | 该片段对应的大佬点评全文（**few‑shot 输出示范，不参与 embedding**） | — |
| `postId` / `postTitle` | 来源帖子 | — |
| `author` | 点评作者昵称 | — |
| `sourceUrl` | 来源链接 | — |
| `curated` | 是否人工筛选过的优质样例 | `true` |

---

## 四、语料建设

### 4.1 数据来源：帖子天然成对

以「帖子 = 一份简历样例 + 若干点评」为单位收集，比单收点评更贴合场景。每帖可产出多条样例（帖子简历拆成多个片段，各自挂对应点评）。

### 4.2 整理与筛选

- 只标粗粒度 `jobCategory` + `snippetType`，**不做缺陷分类**；`curated=true` 只灌筛选过的优质样例，点评质量决定示范质量。
- 每条例样控制篇幅（简历片段 + 点评精简），保证 few‑shot 注入 3~5 条不超 token。
- 同类岗位组合控制数量与来源多样性，避免同质样例堆砌。

### 4.3 灌库脚本（规划）

参照 [scripts/import_intern_jobs.py](ai-service-py/scripts/import_intern_jobs.py) 模式：结构化数据文件 → 分片 → `embed_text` 简历片段 → 写表；提供 `--dry-run` 与按来源幂等写入；复用 `config.py` 向量库与嵌入配置，保证与检索端同库同模型。

---

## 五、检索设计（单路召回）

### 5.1 检索入口：analyze 阶段

检索放在 `analyze_resume`（分析专家已读简历、已产出判断），不在 polish 阶段重复调用。诊断结果回答用户即可，**不用于产生任何标签**。

### 5.2 召回流程

```
analyze_resume 诊断 → {analysis, questions}

单路召回：
    embed 用户"要打磨的片段" → 向量粗召回 → metadata 过滤 jobCategory 一致
    （可选：snippetType 对齐片段类型）
    → top_k = 3~5 条样例
```

- **阈值放宽**：样例间相似度天然低于岗位匹配，靠 `jobCategory` 过滤兜底，不纯靠相似度卡。
- **适用性交给模型**：召回样例的点评不一定对应当前简历的缺陷，few‑shot 阶段由润色模型自行判断，只借鉴适用部分。
- **无命中降级**：命中不足或纯通用改写时跳过 RAG，保持现有行为（RAG 只增强、不阻塞）。
- **参数独立**：`config.py` 新增独立参数（如 `resume_example_top_k`、`resume_example_threshold`），与岗位推荐分离，便于调优。

### 5.3 返回结构

analyze 把精选样例作为 `referenceChunks` 追加进返回结果，供 polish 消费：

```
analyze 返回 = { analysis, questions, referenceChunks: [{content, metadata}, ...] }
```

---

## 六、业务接入

### 6.1 职责划分

- **`analyze_resume`**：诊断 + 单路召回精选 3~5 条样例，返回 `referenceChunks`。检索只在这里发生一次。
- **`polish_resume`**：消费 `referenceChunks`，把每条样例作为 few‑shot 注入润色 prompt。若被直接调用（analyze 未传 `referenceChunks`），内部兜底走一遍召回 → 注入，或直接跳过 RAG。

### 6.2 few‑shot 注入格式

```text
【历史案例 N】
  相似的简历片段：
    ……
  当时专家的点评：
    ……
```

### 6.3 提示词约束（红线）

注入时向润色专家明确：

1. **示范迁移**：参照专家「怎么评判这段简历、该往哪改」的思路，判断当前简历是否有同类问题；
2. **不适用即忽略**：若某案例点评针对的缺陷当前简历并不具备，忽略该案例，不生搬硬套；
3. **严禁照搬/安插**：点评中提到的具体项目与特性（如某 Agent 项目的"评测层""证据系统"）只能作为理解依据，**绝不虚构进用户简历**——沿用现有「保留原事实、不编造」红线；
4. **保留原简历事实**：只重组、改写、扩写用户已提供内容。

`POLISH_SYSTEM` 现有自检（reflect）继续作为不通过即修正的内部约束。

---

## 七、质量与风险

| 风险/注意点 | 对策 |
|---|---|
| 召回样例的缺陷与当前简历不符 | few‑shot 下模型自行判断适用性，提示词要求「不适用即忽略」（6.3） |
| 低质点评带歪结果 | `curated` 门禁，只灌筛选过的 |
| 模型挪用样例点评里的具体项目特性 | few‑shot 提示词红线 + reflect 自检 |
| 样例过大挤占上下文 | 片段/点评精简，3~5 条封顶 |
| 同质样例堆砌、多样性不足 | 每岗位组合限量并保证来源多样 |
| 检索命中不相关 | `jobCategory` 过滤 + 阈值放宽，宁缺毋滥 |
| 向量库建错位置 | 确认建在真实库 `8.147.71.59:40086`，与 `config.py` 一致 |
| `similarity_search` 表名白名单 | 扩展 `vector_store.py` 允许表常量，杜绝注入 |

---

## 八、实施计划

| 阶段 | 内容 | 产出 |
|---|---|---|
| P1 基建 | 建表（真实库）+ `vector_store.py` 白名单扩展 + config 参数 | 表就绪，检索函数可查新表 |
| P2 语料 | 首批帖子拆样/标 jobCategory/精选 + 灌库脚本（dry‑run 优先） | 首批 `curated=true` 样例入库 |
| P3 接入 | `analyze_resume` 召回 + 返回 `referenceChunks`；`polish_resume` few‑shot 注入 + 提示词红线 | 润色链路支持样例检索 |
| P4 验证 | 定向润色对比、无虚构校验 | 效果评估与阈值调优 |

## 九、验证方式

- 灌库：`--dry-run` 检查分片/嵌入后再写入；按来源幂等。
- 检索：抽查某岗位方向（如 Agent / RAG）能否召回对应样例；同一简历开/关样例库对比润色输出，重点看项目描述的技术深度与差异化，并人工核验无虚构。
