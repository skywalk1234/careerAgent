# -*- coding: utf-8 -*-
"""把人工整理的实习岗位写入 ES(jobs_index) 并向量化到 pgvector job_detail_vector。

- 向量库：与 ai-service-py config.py 一致（默认真实库 8.147.71.59:40086/ai-vector，
  可用环境变量 VECTOR_DATABASE_URL 覆盖）
- ES：默认 http://192.168.118.130:9200（与 career-service application.yml 一致，可用 ES_URL 覆盖）
- 内容/元数据模板对齐 Java JobSpecificInitializer.convertToSpringAiDocument；
  ES 文档字段对齐 JobDocument。
- 用法：
    python scripts/import_intern_jobs.py --dry-run   # 只构建数据与向量，不写入
    python scripts/import_intern_jobs.py             # 写入 ES + 向量库
"""

import argparse
import asyncio
import json
import os
import sys
import uuid
from pathlib import Path

import asyncpg
import httpx
from pgvector.asyncpg import register_vector

# 保证从任意 cwd 都能 import 到 app 包
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import settings  # noqa: E402
from app.services.embedding import embed_text, truncate_for_embedding  # noqa: E402

ES_BASE = os.getenv("ES_URL", "http://192.168.118.130:9200")
ES_INDEX = "jobs_index"
TABLE = "job_detail_vector"
ES_CLASS = "group.careerservice.domain.dto.JobDocument"

# ---------------------------------------------------------------- 岗位数据
JOBS = [
    {
        "jobId": "job_agent_intern_0001",
        "jobCode": "JOB202609020001",
        "jobName": "智能体（Agent）开发实习生",
        "companyName": "深圳市元芯动力科技有限公司",
        "city": "深圳",
        "district": "南山区",
        "level": "junior",
        "educationRequirement": "硕士",
        "salaryMin": 300,
        "salaryMax": 400,
        "salaryUnit": "元/天",
        "salaryMonths": None,
        "salaryNegotiable": False,
        "salaryNormalized": "300-400元/天",
        "updatedAtRaw": "9月2日",
        "updatedAtNormalized": "2026-09-02T00:00:00+08:00",
        "sourceUrl": "",
        "sourceSite": "人工录入",
        "companySize": "20-99人",
        "companyType": "天使轮",
        "industryTags": ["半导体", "芯片", "人工智能"],
        "skillTags": ["C/C++", "Java", "Python", "图像算法", "大模型算法", "强化学习", "算法工程化", "RAG", "Prompt工程"],
        "companyBrief": "深圳市元芯动力科技有限公司：天使轮半导体/芯片创业公司（20-99人），提供高水平专家指导、先进深度学习服务器环境与定期AI算法培训。",
        "companyDescription": "深圳市元芯动力科技有限公司（天使轮，20-99人，半导体/芯片行业）。实习条件：高水平专家指导；提供先进的深度学习服务器环境；定期提供AI算法培训；实习环境宽松。招聘者：刘立宝（昨日活跃）；前期线上，后期按通知线下。",
        "jobDescription": (
            "职位描述：1.在专家指导下协助研究大模型推理、应用与部署技术（Agent / RAG / Fine-tune）。"
            "2.参与Harness、Prompt工程、向量数据库、Advanced RAG、Fine-tune、接口设计等相关工作。\n"
            "职位要求：1.本科及以上学历，计算机、自动化、电子等相关专业；"
            "2.具备大模型基础知识，熟悉Python / C / Java至少一种编程语言；"
            "3.熟悉大模型的预训练、微调，精通强化学习、深度学习等AI算法；"
            "4.对AI及大模型技术有浓厚兴趣；5.具备独立思考以及分析定位的能力，拥有良好的沟通表达能力。"
        ),
        "dimensionDetails": {
            "professionalSkill": "熟悉大模型预训练与微调，精通强化学习、深度学习等AI算法，具备算法工程化经验",
            "learning": "对AI及大模型技术有浓厚兴趣，具备独立思考与分析定位能力",
            "communication": "具备良好的沟通表达能力",
        },
    },
    {
        "jobId": "job_ai_tool_intern_0002",
        "jobCode": "JOB202609020002",
        "jobName": "计算机实习生（AI工具应用与全栈开发方向）",
        "companyName": "贝朗（中国）卫浴有限公司",
        "city": "广州",
        "district": "海珠区",
        "level": "junior",
        "educationRequirement": "本科",
        "salaryMin": 200,
        "salaryMax": 300,
        "salaryUnit": "元/天",
        "salaryMonths": None,
        "salaryNegotiable": False,
        "salaryNormalized": "200-300元/天",
        "updatedAtRaw": "9月2日",
        "updatedAtNormalized": "2026-09-02T00:00:00+08:00",
        "sourceUrl": "",
        "sourceSite": "人工录入",
        "companySize": "",
        "companyType": "",
        "industryTags": ["卫浴", "家居"],
        "skillTags": ["AI工具", "Claude", "ChatGPT", "GitHub Copilot", "Cursor", "Python", "FastAPI", "Java", "SQL", "全栈开发"],
        "companyBrief": "贝朗（中国）卫浴有限公司：寻找对前沿AI工具有深入了解、具备扎实编程能力的计算机专业实习生，参与真实业务项目开发，方向可选AI工程、全栈开发、数据分析、AI自动化。",
        "companyDescription": "贝朗（中国）卫浴有限公司。公司提供：接触前沿AI工具与场景、真实项目经验、成长空间、实习证明，优秀可转正。招聘者：梁女士HRBP（29分钟前回复）。",
        "jobDescription": (
            "岗位概述：寻找对前沿AI工具有深入了解，并具备扎实编程能力的计算机专业实习生，参与实际项目开发，"
            "利用市面主流AI工具提升开发效率，同时参与后端、前端及数据相关工作。\n"
            "工作职责：1.熟练使用主流AI工具辅助开发、调试与优化代码；2.参与公司内部系统或产品的功能开发与优化；"
            "3.协助后端接口开发、数据库设计与维护；4.参与前端页面开发与基础交互实现；5.编写技术文档与开发说明；"
            "6.探索AI自动化工作流，提高团队效率。\n"
            "技术与工具要求（AI工具必须熟悉其一或多个）：Claude、ChatGPT、GitHub Copilot、Cursor、Gemini、"
            "Perplexity、Poe、Notion AI；Midjourney/DALL·E为加分；能力要求AI生成/优化/重构代码、AI Debug、Prompt工程、搭建自动化工作流。\n"
            "编程能力（必须掌握）：Python（Flask / FastAPI / Pandas优先）、Java（面向对象，项目经验优先）、"
            "HTML、SQL（基础查询与数据库设计）；加分项JavaScript、Vue/React、Git、Linux基础、API开发、完整项目&GitHub仓库。\n"
            "任职要求：1.计算机、软件工程相关在读本科/研究生；2.较强逻辑思维，学习能力强；3.对AI应用、效率工具感兴趣；"
            "4.每周≥3天，3个月以上优先；5.自驱力、沟通、技术探索、独立解决问题。"
        ),
        "dimensionDetails": {
            "professionalSkill": "熟练使用主流AI工具辅助开发，掌握Python/Java/HTML/SQL，可参与全栈开发",
            "learning": "较强逻辑思维，学习能力强，热衷探索AI应用与自动化工作流",
            "execution": "自驱力强，能独立解决技术问题并落地实际项目",
        },
    },
    {
        "jobId": "job_ai_fullstack_intern_0003",
        "jobCode": "JOB202609020003",
        "jobName": "AI应用开发实习生（AI全栈方向）",
        "companyName": "影石创新科技股份有限公司",
        "city": "深圳",
        "district": "宝安区",
        "level": "junior",
        "educationRequirement": "本科",
        "salaryMin": 250,
        "salaryMax": 350,
        "salaryUnit": "元/天",
        "salaryMonths": None,
        "salaryNegotiable": False,
        "salaryNormalized": "250-350元/天",
        "updatedAtRaw": "9月2日",
        "updatedAtNormalized": "2026-09-02T00:00:00+08:00",
        "sourceUrl": "",
        "sourceSite": "人工录入",
        "companySize": "1000-9999人",
        "companyType": "已上市",
        "industryTags": ["智能硬件"],
        "skillTags": ["Docker", "MySQL", "Python", "TypeScript", "JavaScript", "全栈开发", "Prompt设计", "Function Calling", "Agent编排", "Cursor", "Claude Code"],
        "companyBrief": "影石创新科技股份有限公司（Insta360）：已上市智能硬件公司（1000-9999人），体验设计部AI赋能项目招募AI全栈方向实习生，参与内部AI提效平台与AI工作流搭建。",
        "companyDescription": "影石创新科技股份有限公司（Insta360）：已上市，1000-9999人，智能硬件行业。招聘者：渠女士（2月内活跃）。实习要求每周5天，可连续实习≥6个月。",
        "jobDescription": (
            "岗位职责：1.参与体验设计部AI赋能项目的工具与平台开发，覆盖前端页面、轻量后端服务和自动化脚本；"
            "2.参与内部AI提效平台搭建：数据看板、飞书开放平台API对接、多维表格数据读取聚合；"
            "3.大模型API实现AI工作流：Prompt设计、Function Calling、Agent编排、结构化数据提取自动校验；"
            "4.使用Claude Code / Cursor完成开发，沉淀可复用提示词、工程模板；"
            "5.面向设计团队痛点开发工具：批量物料生成、设计稿提取、Figma插件、桌面自动化脚本；"
            "6.配合产品设计研发验证AI场景，参与方案选型，迭代落地可用工具。\n"
            "能力要求：1.熟悉JS/TS或Python至少一门，理解HTTP、API、JSON；"
            "2.写过接口、脚本或后端服务，可独立完成小型全栈原型；"
            "3.调用过大模型API，使用过Coze/Dify，拥有个人AI Demo/项目；"
            "4.熟练Cursor/Claude Code/Copilot，能阐述AI提效开发流程；5.每周实习5天，可连续实习≥6个月。\n"
            "加分项：1.对接开放平台API（飞书/微信/Notion）、爬虫、自动化脚本、批量数据处理；"
            "2.了解RAG、LangChain Agent框架、MCP；3.Figma插件、浏览器插件、PS/AI脚本开发经验；"
            "4.开发过数据看板、交付过内部工具；5.对AI提效、设计工程感兴趣，具备数据量化意识。"
        ),
        "dimensionDetails": {
            "professionalSkill": "熟悉JS/TS或Python，理解HTTP/API/JSON，可独立完成小型全栈原型并落地可用工具",
            "innovation": "熟悉Prompt设计、Function Calling、Agent编排，使用过Coze/Dify，有大模型API开发经验",
            "execution": "面向设计团队开发批量物料生成、数据看板、Figma插件等工具，具备数据量化意识",
        },
    },
]


def build_content(job: dict) -> str:
    """对齐 Java JobSpecificInitializer.convertToSpringAiDocument 的 content 模板，
    额外追加一行「技能标签」增强检索（人工录入数据特有）。"""
    parts = [
        f"职位名称：{job['jobName']}",
        f"公司名称：{job['companyName']}",
        f"工作地点：{job['city']}{job['district']}",
        f"薪资范围：{job['salaryNormalized']}",
        f"学历要求：{job['educationRequirement']}",
        f"行业标签：{','.join(job['industryTags'])}",
        f"职位描述：{job['jobDescription']}",
        f"公司简介：{job['companyDescription']}",
    ]
    if job.get("dimensionDetails"):
        details = "; ".join(f"{k}:{v}" for k, v in job["dimensionDetails"].items())
        parts.append(f"核心能力要求：{details}")
    if job.get("skillTags"):
        parts.append(f"技能标签：{','.join(job['skillTags'])}")
    return "\n".join(parts) + "\n"


def build_metadata(job: dict) -> dict:
    """对齐 Java 写入 metadata 的键；另补 salaryNormalized / updatedAtRaw，
    使 Python 推荐器 build_candidate 能取到真实值（Java 传 null 由 LLM 推断）。"""
    return {
        "jobId": job["jobId"],
        "jobName": job["jobName"],
        "jobCode": job["jobCode"],
        "level": job["level"],
        "companyName": job["companyName"],
        "city": job["city"],
        "district": job["district"],
        "companySize": job.get("companySize", ""),
        "salaryMin": float(job["salaryMin"]),
        "salaryMax": float(job["salaryMax"]),
        "salaryUnit": job["salaryUnit"],
        "salaryNormalized": job["salaryNormalized"],
        "educationRequirement": job["educationRequirement"],
        "sourceUrl": job.get("sourceUrl", ""),
        "sourceSite": job.get("sourceSite", ""),
        "jobDescription": job["jobDescription"],
        "companyDescription": job["companyDescription"],
        "industryTags": job["industryTags"],
        "updatedAtRaw": job["updatedAtRaw"],
        "dimensionDetails": job["dimensionDetails"],
    }


def build_es_doc(job: dict) -> dict:
    """对齐 JobDocument 字段。abilityRequirements 无 AI 评分时不写入。"""
    doc = {
        "_class": ES_CLASS,
        "jobId": job["jobId"],
        "jobName": job["jobName"],
        "jobCode": job["jobCode"],
        "companyName": job["companyName"],
        "city": job["city"],
        "district": job["district"],
        "industryTags": job["industryTags"],
        "educationRequirement": job["educationRequirement"],
        "salaryMin": job["salaryMin"],
        "salaryMax": job["salaryMax"],
        "salaryUnit": job["salaryUnit"],
        "salaryMonths": job.get("salaryMonths"),
        "salaryNegotiable": job["salaryNegotiable"],
        "salaryNormalized": job["salaryNormalized"],
        "updatedAtRaw": job["updatedAtRaw"],
        "updatedAtNormalized": job["updatedAtNormalized"],
        "sourceUrl": job.get("sourceUrl", ""),
        "sourceSite": job.get("sourceSite", ""),
        "companySize": job.get("companySize", ""),
        "companyType": job.get("companyType", ""),
        "level": job["level"],
        "jobDescription": job["jobDescription"],
        "companyBrief": job.get("companyBrief", ""),
        "companyDescription": job.get("companyDescription", ""),
        "dimensionDetails": job["dimensionDetails"],
    }
    return doc


async def vector_exists(conn, job_id: str) -> bool:
    return bool(await conn.fetchval(
        "SELECT 1 FROM job_detail_vector WHERE metadata->>'jobId' = $1 LIMIT 1", job_id
    ))


async def write_vector(conn, content: str, metadata: dict, embedding: list[float]) -> None:
    await conn.execute(
        "INSERT INTO job_detail_vector (id, content, metadata, embedding) VALUES ($1, $2, $3, $4)",
        str(uuid.uuid4()),
        content,
        json.dumps(metadata, ensure_ascii=False),
        embedding,
    )


async def es_doc_exists(client: httpx.AsyncClient, job_id: str) -> bool:
    resp = await client.head(f"{ES_BASE}/{ES_INDEX}/_doc/{job_id}")
    return resp.status_code == 200


async def write_es(client: httpx.AsyncClient, job: dict) -> None:
    doc = build_es_doc(job)
    action = json.dumps({"index": {"_index": ES_INDEX, "_id": job["jobId"]}}, ensure_ascii=False)
    body = f"{action}\n{json.dumps(doc, ensure_ascii=False)}\n"
    resp = await client.post(
        f"{ES_BASE}/{ES_INDEX}/_bulk?refresh=true",
        content=body,
        headers={"Content-Type": "application/x-ndjson"},
    )
    resp.raise_for_status()
    result = resp.json()
    item = result["items"][0]["index"]
    if item.get("status", 0) >= 400:
        raise RuntimeError(f"ES 写入失败: {item.get('error')}")


async def run(dry_run: bool) -> None:
    pool = await asyncpg.create_pool(settings.vector_database_url, min_size=1, max_size=2, init=register_vector)
    async with httpx.AsyncClient(timeout=30) as client:
        try:
            for job in JOBS:
                content = build_content(job)
                metadata = build_metadata(job)
                truncated = len(content) > settings.embedding_max_chars
                embed_text_ = truncate_for_embedding(content)

                print(f"\n=== {job['jobName']} ({job['jobId']}) ===")
                print(f"content 长度: {len(content)}" + ("（已截断到2000）" if truncated else ""))

                embedding = await embed_text(embed_text_)
                print(f"嵌入完成: {len(embedding)} 维")

                if dry_run:
                    print("[dry-run] 向量元数据:",
                          json.dumps(metadata, ensure_ascii=False)[:400])
                    print("[dry-run] ES 文档字段:",
                          ", ".join(build_es_doc(job).keys()))
                    print("[dry-run] content 前120字:", content[:120].replace("\n", " | "))
                    continue

                async with pool.acquire() as conn:
                    if await vector_exists(conn, job["jobId"]):
                        print(f"[skip] job_detail_vector 已存在 {job['jobId']}，跳过向量写入")
                    else:
                        await write_vector(conn, content, metadata, embedding)
                        print(f"[ok] 已写入 job_detail_vector: {job['jobId']}")

                if await es_doc_exists(client, job["jobId"]):
                    print(f"[skip] jobs_index 已存在 {job['jobId']}，跳过ES写入")
                else:
                    await write_es(client, job)
                    print(f"[ok] 已写入 jobs_index: {job['jobId']}")

            if dry_run:
                print("\n[dry-run] 未执行任何写入，确认无误后去掉 --dry-run 再运行")
        finally:
            await pool.close()


def main():
    parser = argparse.ArgumentParser(description="导入实习岗位到 ES jobs_index + job_detail_vector")
    parser.add_argument("--dry-run", action="store_true", help="只构建数据并生成嵌入，不写入")
    args = parser.parse_args()
    asyncio.run(run(args.dry_run))


if __name__ == "__main__":
    main()
