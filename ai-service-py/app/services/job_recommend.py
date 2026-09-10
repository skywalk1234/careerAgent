"""RAG 岗位推荐：对齐 Java AI_recommend.java 的两个业务方法。

- recommend_category：job_category_vector 向量检索 → nodeName 数组
- recommend_specific_job：job_detail_vector 向量检索 → DeepSeek 精排 → {bestMatch, otherRecommendations}

注意：system prompt 里含 JSON Schema 的大括号，不能对它调用 .format()。
"""

import json
import re
import time

from app.config import settings
from app.services.embedding import embed_job_query, embed_text, truncate_for_embedding
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
    _t0 = time.perf_counter()
    try:
        embedding = await embed_text(truncate_for_embedding(query_text))
        print(
            f"[job_recommend] 大类推荐 | 嵌入完成({settings.embedding_model}, "
            f"{len(embedding)}维) 耗时 {time.perf_counter() - _t0:.2f}s"
        )
        rows = await similarity_search(
            pool,
            TABLE_JOB_CATEGORY,
            embedding,
            top_k=settings.recommend_top_k,
            threshold=settings.recommend_category_threshold,
        )
        print(
            f"[job_recommend] 大类推荐 | 向量检索完成，命中 {len(rows)} 条大类"
            f"（{TABLE_JOB_CATEGORY}，topK={settings.recommend_top_k}，阈值={settings.recommend_category_threshold}）"
        )
        names = [r.metadata.get("nodeName") for r in rows if r.metadata.get("nodeName")]
        print(f"[job_recommend] 大类推荐 | 返回 {len(names)} 个岗位大类，总耗时 {time.perf_counter() - _t0:.2f}s")
        return json.dumps(names, ensure_ascii=False)
    except Exception as e:
        print(f"[job_recommend] 大类推荐 | 出错: {e}")
        return "[]"


async def recommend_specific_job(pool, query_text: str) -> str:
    """镜像 recommendSpecificJob：pgvector 检索 → DeepSeek 精排 → MatchJob JSON 文本。"""
    _t0 = time.perf_counter()
    try:
        # ---- 步骤 1/4：嵌入 ----
        # 必须与爬虫写库时（crawler/db.py 的 embed_job_content）同模型同空间，否则相似度失真
        embedding = await embed_job_query(query_text)
        print(
            f"[job_recommend] 具体推荐 | 1/4 嵌入完成({settings.job_embedding_model}, "
            f"{len(embedding)}维) 耗时 {time.perf_counter() - _t0:.2f}s"
        )

        # ---- 步骤 2/4：向量检索 ----
        rows = await similarity_search(
            pool,
            TABLE_JOB_DETAIL,
            embedding,
            top_k=settings.recommend_top_k,
            threshold=settings.recommend_specific_threshold,
        )
        top_sim = f"，最高相似度 {rows[0].similarity:.3f}" if rows else ""
        print(
            f"[job_recommend] 具体推荐 | 2/4 向量检索完成，命中 {len(rows)} 条候选岗位"
            f"（{TABLE_JOB_DETAIL}，topK={settings.recommend_top_k}，阈值={settings.recommend_specific_threshold}{top_sim}）"
        )
        if not rows:
            print("[job_recommend] 具体推荐 | 无匹配结果，返回 error: 未找到匹配的岗位信息")
            return json.dumps({"error": "未找到匹配的岗位信息"}, ensure_ascii=False)

        # ---- 步骤 3/4：DeepSeek 精排 ----
        candidates = [build_candidate(r.metadata) for r in rows]
        user_prompt = USER_PROMPT_TEMPLATE.format(
            user_json=query_text,
            candidates_json=json.dumps(candidates, ensure_ascii=False),
        )
        _t1 = time.perf_counter()
        print(f"[job_recommend] 具体推荐 | 3/4 调用 DeepSeek 精排（{len(candidates)} 条候选）...")

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
        print(
            f"[job_recommend] 具体推荐 | 3/4 DeepSeek 精排完成，耗时 {time.perf_counter() - _t1:.2f}s，"
            f"输出 {len(content)} 字符"
        )

        # ---- 步骤 4/4：解析并返回结果 ----
        data = _extract_json(content)
        best = data.get("bestMatch") or {}
        others = data.get("otherRecommendations") or []
        print(
            f"[job_recommend] 具体推荐 | 4/4 解析成功 → bestMatch={best.get('jobName')!r} "
            f"(overallScore={best.get('overallScore')})，otherRecommendations={len(others)} 条，"
            f"总耗时 {time.perf_counter() - _t0:.2f}s"
        )
        return json.dumps(data, ensure_ascii=False)

    except json.JSONDecodeError:
        print("[job_recommend] 具体推荐 | 模型返回解析失败（非合法 JSON）")
        return json.dumps({"error": "模型返回解析失败"}, ensure_ascii=False)
    except ValueError:
        print("[job_recommend] 具体推荐 | 模型返回格式不正确（缺 bestMatch 字段）")
        return json.dumps({"error": "模型返回格式不正确"}, ensure_ascii=False)
    except Exception as e:
        print(f"[job_recommend] 具体推荐 | 系统内部错误: {e}")
        return json.dumps({"error": f"系统内部错误: {e}"}, ensure_ascii=False)
