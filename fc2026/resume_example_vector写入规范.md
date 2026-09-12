# resume_example_vector 写入规范与字段样例

> 目的：给「简历样例库」人工整理/批量灌入数据时的字段填写与写入规范。
> 关联文档：[简历RAG方案.md](简历RAG方案.md)（总体设计）｜代码消费端：[app/services/resume_example.py](ai-service-py/app/services/resume_example.py)
> 表已建在**真实/线上向量库** `8.147.71.59:40086/ai-vector`，当前为空，尚未灌数据。

---

## 一、表结构（已建，勿重复建）

与 `job_detail_vector` 同构（Spring AI PgVectorStore 标准 schema）：

```sql
CREATE TABLE IF NOT EXISTS resume_example_vector (
    id UUID NOT NULL,          -- 主键
    content TEXT,              -- 简历片段文本（唯一被 embedding 的对象）
    metadata JSON,             -- 点评 + 标签 + 来源（不参与 embedding）
    embedding VECTOR(1536),    -- 仅由 content 经 text-embedding-v1 生成
    PRIMARY KEY (id)
);
-- 索引已建：USING HNSW (embedding vector_cosine_ops)
```

> `metadata` 用 `JSON` 类型（非 JSONB），代码侧按 `json.loads(r["metadata"])` 解析。

## 二、字段填写规范

| 字段 | 内容 | 要求 |
|---|---|---|
| `id` | UUID | 灌库脚本生成，手工插入可写任意合法 UUID |
| `content` | **简历片段**（项目/经历段落原文） | 只放简历文本；**不放点评、不加分析评语、不打分** |
| `embedding` | content 的 1536 维向量 | 由 `embed_text`（DashScope `text-embedding-v1`）计算；**严禁手工构造或换嵌入模型** |
| `metadata` | 见下表 | 键名必须与代码读取端一致，否则样例无法被消费 |

### metadata 键（与 `_to_reference` 读取的键一一对应）

| 键 | 必填 | 取值说明 | 示例 |
|---|---|---|---|
| `jobCategory` | ✅ | 粗粒度岗位方向；**先查库内已有词再定**，避免同义不同词（Java后端 / Agent开发 / RAG·大模型应用 / AI应用 / 前端…） | `"RAG/大模型应用"` |
| `snippetType` | ✅ | 固定枚举 | `"project"` / `"experience"` / `"skill"` / `"whole_resume"` |
| `comment` | ✅ | 该片段对应的大佬点评（**few-shot 示范，绝不参与 embedding**） | 见样例 |
| `postId` | ✅ | 来源帖子 id（判重锚点之一） | `"xhs_2026xxxx"` |
| `postTitle` | 建议 | 帖子标题，供模型参考上下文 | `"AI 方向简历点评：两个项目深度对比"` |
| `sourceUrl` | ✅ | 来源链接，溯源用 | `"https://www.xiaohongshu.com/explore/xxx"` |
| `author` | 选填 | 点评作者昵称 | `"xxx"` |
| `curated` | ✅ | 是否人工筛过的优质样例 | `true` |

## 三、完整参考样例

### 样例 A：项目片段（RAG 方向）

```json
{
  "id": "9f0e3c2a-6d1b-4a8e-b7c2-1a2b3c4d5e6f",
  "content": "【项目】企业智能问答系统（RAG 方向，个人项目）\n技术栈：Python、FastAPI、LangChain、pgvector、DeepSeek API\n● 清洗并切片产品文档 2000+ 篇，构建 1536 维向量库，实现基于 RAG 的文档问答；\n● 设计会话式多轮问答流程并封装 REST API，完成内部 Demo 演示；\n● 线上召回准确率约 85%，尝试过 rerank，效果提升有限。",
  "metadata": {
    "jobCategory": "RAG/大模型应用",
    "snippetType": "project",
    "comment": "这个 RAG 项目属于标准的「套开源库」写法：切片+向量检索+生成，全是框架默认链路，看不出设计思考。RAG 和长期记忆本质都卡在「召回」这一件事上——如果能把静态检索升级成动态更新，包装成「长期记忆/自进化」就有说法了；再配上「图谱+向量」双路，一个建逻辑、一个建语义，就是有自己的设计决策了，比干巴巴套库强得多。",
    "postId": "xhs_2026xxxx",
    "postTitle": "AI 方向简历点评：两个项目深度对比",
    "author": "xx",
    "sourceUrl": "https://www.xiaohongshu.com/explore/xxxx",
    "curated": true
  },
  "embedding": "[1536 维向量，由灌库脚本调用 text-embedding-v1 对 content 生成，此处以占位符示意]"
}
```

### 样例 B：实习经历片段（Java 后端方向）

```json
{
  "id": "7b1e9d42-1c5f-4f20-93a8-5d6e7f8a9b0c",
  "content": "【实习经历】XX科技 Java 后端实习生（2025.06 - 2025.09）\n● 负责订单模块 3 个接口开发，Spring Boot + MyBatis，日均请求约 10 万；\n● 参与一次慢查询优化，通过加索引与拆分 SQL 将接口 P99 从 800ms 降至 200ms；\n● 编写接口文档并配合前端联调。",
  "metadata": {
    "jobCategory": "Java后端",
    "snippetType": "experience",
    "comment": "这段实习最大的问题是没写出「为什么是你」：「参与」这类词把主动性写没了。慢查询优化那一段应该展开成「定位问题 → 分析原因 → 方案对比 → 结果量化」的完整过程，才像有解决问题的能力；接口开发是行业基础操作，不用展开太多。",
    "postId": "xhs_2026yyyy",
    "postTitle": "后端简历点评：实习经历这样写才有区分度",
    "author": "xx",
    "sourceUrl": "https://www.xiaohongshu.com/explore/yyyy",
    "curated": true
  },
  "embedding": "[同上，由脚本生成]"
}
```

## 四、写入规范

1. **一条样例 = 一个简历片段 + 针对它的点评**。片段粒度对齐检索端拆分：段落级、≥30 字、通常几百字以内；一个帖子（一份简历）可拆成多条样例，各自挂对应点评。
2. **content 只放简历片段**。不从「岗位/他人简历」拼凑，不加点评、不加打分、不加"这段写得很好"之类分析——点评一律进 `metadata.comment`。
3. **embedding 只由 content 生成**，严禁把 comment 拼进嵌入文本——这是本方案「向量匹配简历、不匹配点评」的根基。
4. **来源配对**：片段与点评应来自同一帖子（简历截图/描述 ↔ 评论区点评），保证点评确实针对该片段。
5. **事实红线**：片段来自真实（可脱敏）简历或发帖人公开内容；点评如实收录，可精简语气但不得歪曲其判断。不虚构样例。
6. **标签收敛**：`jobCategory` 用粗粒度词且先查库再定；`snippetType` 严格用枚举；同一方向控制样例数量与来源多样性，避免同质堆砌。
7. **curated 门禁**：只灌人工筛选过、观点具体可迁移的优质样例（`true`）。低质点评不带偏润色。
8. **来源必填**：`postId` + `sourceUrl` 必填（溯源 + 幂等判重）；同帖同片段不重复入库。
9. **长度控制**：content 不超过 2000 字符（嵌入截断上限）；comment 精简到表意完整即可——few-shot 注入的上下文很宝贵。
10. **写入方式**：优先走灌库脚本（`--dry-run` 校验分片/嵌入后再写入，按来源幂等判重）；不鼓励手工 SQL（1536 维向量无法手写）。确需手工插入，先查重再插，模板见下。

### 手工校验 / 插入参考 SQL

```sql
-- 抽样看一条完整记录
SELECT id, content, metadata FROM resume_example_vector LIMIT 1;

-- 按岗位方向统计（检查标签是否收敛）
SELECT metadata->>'jobCategory' AS jobCategory, count(*)
FROM resume_example_vector GROUP BY 1 ORDER BY 2 DESC;

-- 按来源查重
SELECT count(*) FROM resume_example_vector WHERE metadata->>'postId' = 'xhs_2026xxxx';

-- 手测检索（$1 需替换为 text-embedding-v1 生成的 1536 维向量）
SELECT id, content, metadata->>'comment' AS comment,
       1 - (embedding <=> $1::vector) AS similarity
FROM resume_example_vector
WHERE 1 - (embedding <=> $1::vector) >= 0.15
ORDER BY embedding <=> $1
LIMIT 5;
```

---

## 五、与代码的约定（改动时留意）

- 消费端读取键：`metadata.comment / jobCategory / snippetType / postTitle / sourceUrl`（见 [resume_example.py](ai-service-py/app/services/resume_example.py) `_to_reference`）。**改字段先改两边**。
- 检索端把用户简历按「`#` 标题命中 项目/经历/实习/工作/实践 → 空行切段落」拆成 query 片段（[split_resume_sections](ai-service-py/app/services/resume_example.py)）。库内样例若低于 30 字或语义过于空泛，即使命中参考价值也低。
- 检索参数：`resume_example_top_k=5`、`resume_example_threshold=0.15`（config.py）。当前运行时侧未做 `jobCategory` 过滤、检索靠纯向量相似度；标签收敛主要为将来加回 metadata 过滤做准备，也会让按方向抽样/评估更干净。
