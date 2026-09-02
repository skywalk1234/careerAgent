import json
import time
from typing import Annotated, Optional

import httpx
from fastapi import Request
from langchain_core.tools import InjectedToolArg, tool

from app.config import settings
from app.services import job_recommend, resume_example, resume_polish


def _strip_prefix(raw: str) -> str:
    """去掉 jobId: / profileId: 等前缀，只保留纯 ID"""
    return str(raw or "").strip().split(":", 1)[-1].strip()


async def _load_resume(profile_id: Optional[str], token: str) -> str:
    """按 profile_id 拉取简历 markdown；未指定则取最新一份"""
    if profile_id and str(profile_id).strip():
        profile = await resume_polish.fetch_profile(_strip_prefix(str(profile_id)), token)
        return profile.get("content") if isinstance(profile, dict) else str(profile)
    latest = await resume_polish.fetch_latest_profile(token)
    return latest.get("content") or ""


@tool
async def get_student_profile(
    user_id: Annotated[int, InjectedToolArg],
    token: Annotated[str, InjectedToolArg],
    profile_id: Optional[str] = None,
) -> str:
    """获取当前学生的简历画像信息（基本信息、教育背景、技能、工作经历等 markdown 简历原文），用于简历评估与岗位推荐。

    一个学生可有多份简历。当用户对话消息中涉及某个具体简历（形如 `profileId:xxx`、`profileID:xxx`，
    或用户明确指定了某份简历标题/编号）时，必须从对话中抽取简历id填入 profile_id 参数（可去掉
    profileId: 前缀只传纯ID），根据该 profile_id 查询对应简历的详情；
    若用户未指定简历，则默认获取最新一份简历。
    """
    # 转发用户 JWT：Java 网关 AuthGlobalFilter 会对 /users/me/profile 做登录校验，
    # 不带 token 会被网关直接 401 拦截，导致下游 profile-service 根本收不到请求。
    headers = {"Authorization": f"Bearer {token}"} if token else {}

    if profile_id and str(profile_id).strip():
        # 用户消息中可能携带 profileId: 前缀（形如 profileId:xxxx），去掉前缀只保留纯 ID
        raw_profile_id = str(profile_id).strip()
        normalized_profile_id = raw_profile_id.split(":", 1)[-1].strip() if ":" in raw_profile_id else raw_profile_id
        url = f"{settings.profile_service_base_url}/users/me/profile/{normalized_profile_id}"
    else:
        url = f"{settings.profile_service_base_url}/users/me/profile"

    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(url, params={"userId": user_id}, headers=headers)
            resp.raise_for_status()
            payload = resp.json()
    except Exception as e:
        return json.dumps({"error": f"获取简历失败: {e}"}, ensure_ascii=False)

    data = payload.get("data") or {}
    if not data.get("hasProfile") or data.get("profile") is None:
        return json.dumps({"error": f"用户 {user_id} 还没有简历画像"}, ensure_ascii=False)

    return json.dumps(data["profile"], ensure_ascii=False, default=str)


@tool
async def query_job_detail(
    job_id: str,
    token: Annotated[str, InjectedToolArg],
) -> str:
    """根据岗位ID查询岗位的详细信息（岗位名称、公司、城市、薪资、岗位描述、能力要求、核心技能等）。

    当用户发送的对话消息中涉及到某个具体的岗位ID（形如 `jobId:xxx`、`jobID:xxx` 或直接给出岗位编号如 JOB2024...）
    并希望了解该岗位的具体信息（工作内容、任职要求、薪资待遇等）时，必须调用本工具获取岗位详细信息，
    再基于查询结果回答用户。模型需要从对话中抽取岗位ID填入 job_id 参数（可去掉 jobId: 前缀，只传纯ID）。
    """
    raw_job_id = str(job_id or "").strip()
    if not raw_job_id:
        return json.dumps({"error": "缺少岗位ID，请从对话中抽取 jobId 后重试"}, ensure_ascii=False)

    # 用户消息中可能携带 jobId: 前缀（形如 jobId:123455），去掉前缀只保留纯 ID
    normalized_job_id = raw_job_id.split(":", 1)[-1].strip() if ":" in raw_job_id else raw_job_id
    if not normalized_job_id:
        return json.dumps({"error": "岗位ID无效，请从对话中抽取 jobId 后重试"}, ensure_ascii=False)

    url = f"{settings.career_service_base_url}/jobs/{normalized_job_id}"
    # 透传用户 JWT：网关对 /jobs/** 有登录校验，不带 token 会被 401 拦截
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(url, headers=headers)
            resp.raise_for_status()
            payload = resp.json()
    except Exception as e:
        return json.dumps({"error": f"获取岗位详情失败: {e}"}, ensure_ascii=False)

    data = payload.get("data")
    if not data:
        return json.dumps(
            {"error": payload.get("msg") or f"岗位 {normalized_job_id} 不存在或查询失败"},
            ensure_ascii=False,
        )

    return json.dumps(data, ensure_ascii=False, default=str)


@tool
async def analyze_resume(
    profile_id: Optional[str] = None,
    job_id: Optional[str] = None,
    token: Annotated[str, InjectedToolArg] = "",
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """分析学生的某份简历：从语言表达、结构条理、内容完整性（以及可选的目标岗位 JD 契合度）给出专业诊断。

    当用户想「看/分析/评估/诊断」简历，或想了解简历针对某个岗位的差距时调用。只分析，不修改简历。
    - profile_id：要分析的简历 id（可选）。消息中可能带 `profileId:` 前缀，或用户明确指定了某份简历标题/编号；
      未指定则默认分析最新一份简历。
    - job_id：目标岗位 id（可选）。仅当对话中能确定具体岗位（消息中带 `jobId:` 前缀或岗位编号）时才传；
      用户只泛泛提到岗位名而无 id 时不传，本工具将做通用分析。
    分析结果可能附带少量追问（如目标岗位、项目细节），请原样转述给用户；用户回答后汇总这些补充信息，
    在后续需要润色时传给 polish_resume 的 extra_info 参数。
    """
    t0 = time.perf_counter()
    print(f"[resume-tool] analyze_resume 调用: profile_id={profile_id!r}, job_id={job_id!r}", flush=True)
    try:
        resume_text = await _load_resume(profile_id, token)
        if not resume_text:
            return json.dumps({"error": "该简历内容为空，无法分析"}, ensure_ascii=False)
        print(
            f"[resume-tool] analyze_resume 拉简历完成: {time.perf_counter() - t0:.2f}s, content_len={len(resume_text)}",
            flush=True,
        )
        job = None
        if job_id:
            job = await resume_polish.fetch_job_detail(_strip_prefix(job_id), token)
            print(
                f"[resume-tool] analyze_resume 拉JD完成: {time.perf_counter() - t0:.2f}s, job_id={_strip_prefix(job_id)!r}",
                flush=True,
            )
    except Exception as e:
        print(f"[resume-tool] analyze_resume 获取简历/岗位失败({time.perf_counter() - t0:.2f}s): {e}", flush=True)
        return json.dumps({"error": f"获取简历或岗位失败: {e}"}, ensure_ascii=False)

    # 样例库 RAG：先检索相似简历样例与点评，作为分析专家 LLM 的 few-shot 参考输入。
    # 只增强、不阻塞：无连接池 / 检索失败 / 无命中都继续走分析。
    refs: list = []
    try:
        pool = getattr(request.app.state, "pg_pool", None) if request is not None else None
        if pool is not None:
            refs = await resume_example.retrieve_resume_examples(pool, resume_text)
            print(
                f"[resume-tool] analyze_resume 样例检索完成({time.perf_counter() - t0:.2f}s): "
                f"命中 {len(refs)} 条，将作为历史点评参考输入分析专家",
                flush=True,
            )
        else:
            print("[resume-tool] analyze_resume 跳过样例检索（无向量库连接池）", flush=True)
    except Exception as e:
        print(f"[resume-tool] analyze_resume 样例检索失败(忽略): {e}", flush=True)

    try:
        result = await resume_polish.analyze(job, resume_text, references=refs or None)
        print(
            f"[resume-tool] analyze_resume LLM分析完成: {time.perf_counter() - t0:.2f}s, "
            f"analysis_len={len(result.get('analysis') or '')}, has_questions={bool(result.get('questions'))}",
            flush=True,
        )
    except Exception as e:
        print(f"[resume-tool] analyze_resume LLM分析失败({time.perf_counter() - t0:.2f}s): {e}", flush=True)
        return json.dumps({"error": f"分析简历失败: {e}"}, ensure_ascii=False)

    # 命中样例随结果一并返回，供后续润色阶段取用（polish 的消费方式待定）
    if refs:
        result["referenceChunks"] = refs

    print(f"[resume-tool] analyze_resume 总耗时: {time.perf_counter() - t0:.2f}s", flush=True)
    return json.dumps(result, ensure_ascii=False, default=str)


@tool
async def polish_resume(
    profile_id: Optional[str] = None,
    job_id: Optional[str] = None,
    extra_info: Optional[str] = None,
    token: Annotated[str, InjectedToolArg] = "",
) -> str:
    """润色学生的某份简历并另存为新简历：按目标岗位（可选）和用户补充信息生成修订稿，直接保存为一份新简历（原简历保留）。

    当用户要求「修改/润色/优化/改写」简历时调用，尤其是针对某个岗位做定向优化。
    - profile_id：要润色的简历 id（可选）。消息中可能带 `profileId:` 前缀；未指定则默认最新一份。
    - job_id：目标岗位 id（可选）。仅当对话中能确定具体岗位（消息中带 `jobId:` 前缀或岗位编号）时才传；
      没有确定岗位时可省略，此时按用户修改要求做通用改写。
    - extra_info：用户在对话中补充的相关信息（自由文本，可空）：分析阶段对追问的回答、本次具体的修改要求、
      目标岗位名称/方向、想强调的经历等。请把对话中用户提供的全部相关补充信息汇总成一段话传入。
    注意：本工具会直接另存为一份新简历并返回变更摘要，不再询问是否开始润色/是否保存。
    """
    raw_extra = str(extra_info or "").strip()
    t0 = time.perf_counter()
    print(
        f"[resume-tool] polish_resume 调用: profile_id={profile_id!r}, job_id={job_id!r}, extra_len={len(raw_extra)}",
        flush=True,
    )
    try:
        resume_text = await _load_resume(profile_id, token)
        if not resume_text:
            return json.dumps({"error": "该简历内容为空，无法润色"}, ensure_ascii=False)
        print(
            f"[resume-tool] polish_resume 拉简历完成: {time.perf_counter() - t0:.2f}s, content_len={len(resume_text)}",
            flush=True,
        )
        job = None
        if job_id:
            job = await resume_polish.fetch_job_detail(_strip_prefix(job_id), token)
            print(
                f"[resume-tool] polish_resume 拉JD完成: {time.perf_counter() - t0:.2f}s, job_id={_strip_prefix(job_id)!r}",
                flush=True,
            )
    except Exception as e:
        print(f"[resume-tool] polish_resume 获取简历/岗位失败({time.perf_counter() - t0:.2f}s): {e}", flush=True)
        return json.dumps({"error": f"获取简历或岗位失败: {e}"}, ensure_ascii=False)

    try:
        result = await resume_polish.polish(job, resume_text, raw_extra)
        print(
            f"[resume-tool] polish_resume LLM润色完成: {time.perf_counter() - t0:.2f}s, "
            f"revised_len={len(result.get('revisedContent') or '')}, changes={len(result.get('changes') or [])}",
            flush=True,
        )
        await resume_polish.save_profile_as_new(result["revisedContent"], token)
        print(f"[resume-tool] polish_resume 另存新简历完成: {time.perf_counter() - t0:.2f}s", flush=True)
    except Exception as e:
        print(f"[resume-tool] polish_resume 润色/保存失败({time.perf_counter() - t0:.2f}s): {e}", flush=True)
        return json.dumps({"error": f"润色简历失败: {e}"}, ensure_ascii=False)

    # changes 现在是简短字符串列表（POLISH_SYSTEM 精简输出 schema 后的约定），
    # 兼容旧 dict 结构：{"reason": ...} 取 reason，字符串直接用。
    changes = result.get("changes") or []
    lines = ["已按你的要求完成润色，并另存为一份新简历（原简历保留）。主要变更："]
    for i, c in enumerate(changes, 1):
        reason = c.get("reason") if isinstance(c, dict) else str(c)
        lines.append(f"{i}. {reason}")
    if not changes:
        lines.append("（无大幅改动）")
    print(f"[resume-tool] polish_resume 总耗时: {time.perf_counter() - t0:.2f}s", flush=True)
    return json.dumps(
        {
            "success": True,
            "changes": changes,
            "summary": "\n".join(lines),
        },
        ensure_ascii=False,
    )


@tool
async def recommend_specific_jobs(
    job_intention: str,
    city: Optional[str] = None,
    salary_expectation: Optional[str] = None,
    skills: Optional[str] = None,
    experience: Optional[str] = None,
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """根据学生的求职意愿推荐具体岗位（人岗匹配）。

    当学生表达了求职意向（想做什么岗位、目标城市、薪资期望、技能栈、经验水平等）时，
    调用本工具进行岗位推荐。模型需要从对话中抽取并填写以下参数：
    - job_intention：求职意向/目标岗位（必填），如「Java 后端开发工程师」
    - city：期望城市（选填），如「上海」
    - salary_expectation：薪资期望（选填），如「15k-25k」
    - skills：技能栈（选填），如「Java, Spring, MySQL, Redis」
    - experience：经验水平（选填），如「应届生 / 3 年经验」

    内部会把这些信息拼成用户画像文本，走「向量检索 + DeepSeek 精排」返回最佳匹配岗位及备选岗位。
    """
    # 拼装成与 /jobs/recommend/specific 接口一致的「用户画像」查询文本
    profile = {
        "jobIntention": job_intention,
        "city": city,
        "salaryExpectation": salary_expectation,
        "skills": skills,
        "experience": experience,
    }
    # 去掉空字段，避免无意义信息干扰向量检索
    profile = {k: v for k, v in profile.items() if v}
    query_text = json.dumps(profile, ensure_ascii=False)

    # 复用 job_recommend 的核心业务函数，从 app.state 拿 pgvector 连接池
    pool = getattr(request.app.state, "pg_pool", None)
    if pool is None:
        return json.dumps({"error": "岗位推荐服务暂不可用（向量库未连接）"}, ensure_ascii=False)

    return await job_recommend.recommend_specific_job(pool, query_text)


# 所有可注册给模型的工具（新增工具只需追加到这里）
ALL_TOOLS = [get_student_profile, recommend_specific_jobs, query_job_detail, analyze_resume, polish_resume]
