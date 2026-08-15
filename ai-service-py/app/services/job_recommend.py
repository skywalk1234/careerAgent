"""RAG 岗位推荐：对齐 Java AI_recommend.java 的两个业务方法。

- recommend_category：job_category_vector 向量检索 → nodeName 数组
- recommend_specific_job：job_detail_vector 向量检索 → DeepSeek 精排 → {bestMatch, otherRecommendations}

注意：system prompt 里含 JSON Schema 的大括号，不能对它调用 .format()。
"""

import json
import re

from app.config import settings
from app.services.embedding import embed_text, truncate_for_embedding
from app.services.llm import get_json_llm
from app.services.vector_store import (
    TABLE_JOB_CATEGORY,
    TABLE_JOB_DETAIL,
    similarity_search,
)

# ---------- 与 AI_recommend.recommendSpecificJob 的 systemPrompt 完全一致 ----------
SYSTEM_PROMPT = """你是一位资深的招聘专家和职业顾问。你的任务是根据【用户画像】和【候选岗位列表】，进行深度的匹配分析。

【任务要求】
1. 仔细分析用户的求职意愿、技能栈、经验水平和薪资期望。
2. 逐一评估【候选岗位列表】中的每个职位与用户的匹配度。
3. 选出 **1 个** 最佳匹配岗位 (bestMatch) 和 **最多 4 个** 其他推荐岗位 (otherRecommendations)。
4. 为每个推荐岗位生成一个 0-100 的综合匹配分 (overallScore)。
5. 为最佳匹配岗位生成详细的维度评分 (basicRequirement, professionalSkill, professionalLiteracy, developmentPotential)。
6. 生成简练的匹配标签 (matchTags)，如 "高匹配", "薪资略低但发展好", "技术栈重合" 等。

【输出约束】
- 必须严格且仅返回标准的 JSON 格式，不要包含任何 Markdown 标记（如 ```json ... ```），不要包含任何解释性文字。
- 返回的 JSON 结构必须完全符合以下 Schema：
  {
    "bestMatch": {
      "jobId": "string",
      "jobName": "string",
      "companyName": "string",
      "city": "string",
      "educationRequirement": "string",
      "salaryNegotiable": boolean,
      "salaryNormalized": "string",
      "updatedAtRaw": "string",
      "level": "string",
      "overallScore": number,
      "matchTags": ["string"],
      "dimensionScores": {
        "basicRequirement": number,
        "professionalSkill": number,
        "professionalLiteracy": number,
        "developmentPotential": number
      }
    },
    "otherRecommendations": [
      {
        "jobId": "string",
        "jobName": "string",
        "companyName": "string",
        "city": "string",
        "educationRequirement": "string",
        "salaryNegotiable": boolean,
        "salaryNormalized": "string",
        "updatedAtRaw": "string",
        "level": "string",
        "overallScore": number,
        "matchTags": ["string"]
      }
    ]
  }"""

# 模板本身不含花括号，可用 .format() 安全插值
USER_PROMPT_TEMPLATE = """【用户画像与意愿】
{user_json}

【候选岗位列表 (来自向量检索)】
{candidates_json}

请根据上述信息进行匹配分析，并返回严格的 JSON 结果。"""


def build_candidate(metadata: dict) -> dict:
    """逐字段对齐 AI_recommend.recommendSpecificJob 的 jobInfo。
    缺字段时保持 None（json.dumps 输出 null），与 Java HashMap 行为一致。

    注意：metadata 里本来就没有 salaryNormalized / updatedAtRaw（JobSpecificInitializer
    不写这两键），Java 传 null，这里同样返回 null，靠 LLM 推断响应值。
    """
    return {
        "jobId": metadata.get("jobId"),
        "jobName": metadata.get("jobName"),
        "companyName": metadata.get("companyName"),
        "city": metadata.get("city"),
        "district": metadata.get("district"),
        "level": metadata.get("level"),
        "educationRequirement": metadata.get("educationRequirement"),
        "salaryNormalized": metadata.get("salaryNormalized"),
        "salaryMin": metadata.get("salaryMin"),
        "salaryMax": metadata.get("salaryMax"),
        "updatedAtRaw": metadata.get("updatedAtRaw"),
    }


def _extract_json(content: str) -> dict:
    """剥离可能存在的 ```json 围栏后解析；校验是 dict 且含 bestMatch。
    解析失败 → json.JSONDecodeError；缺 bestMatch → ValueError。
    """
    text = content.strip()
    text = re.sub(r"^```(?:json)?\s*", "", text).strip()
    text = re.sub(r"\s*```$", "", text).strip()
    data = json.loads(text)
    if not isinstance(data, dict) or "bestMatch" not in data:
        raise ValueError("missing bestMatch")
    return data


async def recommend_category(pool, query_text: str) -> str:
    """镜像 recommendCategory：pgvector 检索 → 返回 nodeName 数组的 JSON 文本。"""
    try:
        embedding = await embed_text(truncate_for_embedding(query_text))
        rows = await similarity_search(
            pool,
            TABLE_JOB_CATEGORY,
            embedding,
            top_k=settings.recommend_top_k,
            threshold=settings.recommend_category_threshold,
        )
        names = [r.metadata.get("nodeName") for r in rows if r.metadata.get("nodeName")]
        return json.dumps(names, ensure_ascii=False)
    except Exception as e:
        print(f"[job_recommend] recommend_category 出错: {e}")
        return "[]"


async def recommend_specific_job(pool, query_text: str) -> str:
    """镜像 recommendSpecificJob：pgvector 检索 → DeepSeek 精排 → MatchJob JSON 文本。"""
    try:
        embedding = await embed_text(truncate_for_embedding(query_text))
        rows = await similarity_search(
            pool,
            TABLE_JOB_DETAIL,
            embedding,
            top_k=settings.recommend_top_k,
            threshold=settings.recommend_specific_threshold,
        )
        if not rows:
            return json.dumps({"error": "未找到匹配的岗位信息"}, ensure_ascii=False)

        candidates = [build_candidate(r.metadata) for r in rows]
        user_prompt = USER_PROMPT_TEMPLATE.format(
            user_json=query_text,
            candidates_json=json.dumps(candidates, ensure_ascii=False),
        )

        llm = get_json_llm()
        resp = await llm.ainvoke(
            [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ]
        )
        content = resp.content
        if isinstance(content, list):  # 防御：个别 provider 返回 block 列表
            content = "".join(b.get("text", "") for b in content if isinstance(b, dict))

        data = _extract_json(content)
        return json.dumps(data, ensure_ascii=False)

    except json.JSONDecodeError:
        return json.dumps({"error": "模型返回解析失败"}, ensure_ascii=False)
    except ValueError:
        return json.dumps({"error": "模型返回格式不正确"}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": f"系统内部错误: {e}"}, ensure_ascii=False)
