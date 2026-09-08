import json
import time
from typing import Annotated, Optional

import httpx
from fastapi import Request
from langchain_core.tools import InjectedToolArg, tool

from app.config import settings
from app.services import interview, job_recommend, memory, plan, resume_example, resume_polish
from app.services.embedding import embed_text, truncate_for_embedding


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
    """润色学生的某份简历并另存为新简历：按目标岗位（可选）、分析专家建议与用户补充信息生成修订稿，直接另存为一份新简历（原简历保留）。

    触发时机——要**从整段对话综合判断主意图**，不要只依据当前这一条消息的措辞：
    - 当前消息明确要求「修改/润色/优化/改写」简历：立即调用；
    - **用户在前面对话中已表达过润色/修改简历的意图**（如说"想润色简历"，或 analyze_resume 给出诊断后表示要按建议改），
      而本条消息只是把润色所需的材料补上来——例如重新发来一份简历（带 `profileId:` 前缀或直接粘贴简历内容）、
      回答了之前的追问等。这类消息即便没有"润色"字样，也**应继续调用本工具**完成之前挂起的润色请求；
    - profile_id：要润色的简历 id（可选）。本条或前文消息带 `profileId:` 前缀时填入（可去前缀只传纯ID），
      或用户明确指定某份简历标题/编号；均未指定则默认润色最新一份。
    - job_id：目标岗位 id（可选）。对话中能确定具体岗位（带 `jobId:` 前缀或岗位编号，或用户表达过想投的方向）时才传；
      无法确定时可省略，此时按用户修改要求做通用改写。
    - extra_info：本次润色的完整依据（自由文本，可空但建议充实）。请**回溯整段对话**，把以下内容筛选、合并成一段清晰的指示传入：
        1. 前文 analyze_resume 分析结果中给出的改进建议（语言表达/结构条理/内容完整性/JD 契合度等维度的诊断要点）；
        2. 分析阶段抛出的追问以及用户随后给出的回答/补充；
        3. 用户明确的修改指令、想保留或想强调的经历、目标岗位名称/方向等。
      汇总原则：宁多勿漏，前文提到过的修改方向都要带进来，但不要把无关闲聊塞入。若本条消息只是补材料、没有新的修改要求，
      把前文已有的建议汇总传入即可，不要因此再向用户反复追问。
      注意：本工具会直接另存为一份新简历并返回变更摘要，不再询问是否开始润色/是否保存。
      本工具内部会做「生成 → 评审 → 修正」自检，自动剔除简历中用户只学过却未实际使用、却可能被写进项目的技术。
      extra_info 只需如实汇总对话内容即可，无需额外逐条交代「哪些技术没用过」。
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

    # changes 是简短字符串列表（resume_polish 三阶段润色的输出约定），
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


@tool
async def recall_memory(
    query: str,
    user_id: Annotated[int, InjectedToolArg],
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """检索该学生沉淀的长期记忆（学习进展/项目进展/目标/自评短板等，跨会话保存）。

    长期记忆用于回答「需要回顾用户历史/跨会话信息」的问题。典型调用时机：
    - 用户问及过去做过/说过的事，且本会话对话历史中已找不到（如「上次你说在做 xx，进展如何」、
      「我之前提到过哪些和数据库/存储相关的」、「我之前定过什么目标」）；
    - 当前对话语境不足，需要结合用户的长期学习轨迹/目标来给出贴合的判断或规划。
    query：一句话描述想回顾的内容（用什么措辞都可以，内部做语义检索）。
    检索会命中「最相关的若干条记忆」；若语义无命中，回退返回最近沉淀的几条。
    """
    t0 = time.perf_counter()
    q = str(query or "").strip()
    if not q:
        return json.dumps({"error": "缺少检索内容，请描述想回顾什么后重试"}, ensure_ascii=False)
    pool = getattr(request.app.state, "pg_pool", None) if request is not None else None
    if pool is None:
        return json.dumps({"error": "长期记忆服务暂不可用（向量库未连接）"}, ensure_ascii=False)
    print(f"[memory-tool] recall_memory 调用: user_id={user_id}, query={q[:50]!r}", flush=True)

    try:
        embedding = await embed_text(truncate_for_embedding(q))
        episodes = await memory.search_episodes(
            pool,
            user_id,
            embedding,
            settings.memory_recall_top_k,
            settings.memory_recall_threshold,
        )
    except Exception as e:
        print(f"[memory-tool] recall_memory 语义检索失败(忽略): {e}", flush=True)
        episodes = []

    if not episodes:
        # 语义无命中（如泛化的「上次我们聊到哪」）：按时间回退最近活跃，避免空手而归
        try:
            episodes = await memory.load_recent_active(pool, user_id, settings.memory_recall_top_k)
        except Exception as e:
            print(f"[memory-tool] recall_memory 回退检索失败(忽略): {e}", flush=True)
            episodes = []

    print(f"[memory-tool] recall_memory 完成: {time.perf_counter() - t0:.2f}s, 命中 {len(episodes)} 条", flush=True)
    return json.dumps({"episodes": episodes, "query": q}, ensure_ascii=False, default=str)


@tool
async def create_career_plan(
    target: Optional[str] = None,
    profile_id: Optional[str] = None,
    focus: Optional[str] = None,
    token: Annotated[str, InjectedToolArg] = "",
    session_id: Annotated[str, InjectedToolArg] = "",
    user_id: Annotated[int, InjectedToolArg] = 0,
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """生成一份「职业规划行动方案」并保存：结合学生简历画像、长期记忆（当前状态 + 近期成长轨迹）、目标岗位 JD，产出分阶段、带目标与检查点的行动方案，自动落库供「计划与行动方案」页面回看，再次规划会覆盖旧的激活方案。

    触发时机——用户需要**结合长期轨迹做中长期、分阶段安排**时调用（要**从整段对话综合判断主意图**，不只依据措辞）：
    - 「给我做个规划 / 接下来几个月怎么安排 / 想朝 XX 岗位怎么一步步准备 / 秋招前时间怎么分配 / 最近感觉没方向，帮我理一理」；
    - 上一轮分析（如 analyze_resume 诊断 / 岗位推荐）后用户表示「那就按这个给我排个计划」；
    - 用户再次规划（执行一段时间后回来要新一版计划）：会直接产出新方案并归档旧版。
    单个具体知识问题（如某语法怎么用、某岗位薪资）不需要规划，不要调用。
    - target：目标岗位 / 方向（可选）。对话消息里带 `jobId:`/`jobID:` 前缀的岗位编号时填进去（可带前缀），
      或用户明确的目标方向（如「Java 后端开发」「算法工程师」「考研还是就业想清楚了，冲后端」）。
    - profile_id：用哪份简历（可选）。消息带 `profileId:` 前缀或用户指定了某份简历时填入（可去前缀只传纯ID）；
      未指定则用最新一份简历。
    - focus：可选约束（如「重点补算法」「秋招前要完成」「每阶段不超过两周」），没有则省略。
    本工具内部自动取简历、长期记忆与 JD，一次生成方案并保存，无需主模型先取材料。
    调用成功会返回方案标题/目标/摘要与回复指引（guide），**不含正文全文**——完整内容已落库，
    用户可去「计划与行动方案」页面查看。最终回复只需告知用户方案已生成并用 summary 简要概括要点，
    引导去该页看全文，不要复述或展开整份方案正文。
    """
    t0 = time.perf_counter()
    print(
        f"[plan-tool] create_career_plan 调用: user_id={user_id}, target={target!r}, "
        f"profile_id={profile_id!r}, focus={focus!r}",
        flush=True,
    )
    pool = getattr(request.app.state, "pg_pool", None) if request is not None else None
    if pool is None:
        print("[plan-tool] 向量库未连接，跳过长期记忆（仅凭简历+对话生成）", flush=True)
    try:
        result = await plan.create_plan(
            user_id=user_id,
            token=token,
            session_id=session_id,
            target=target,
            profile_id=profile_id,
            focus=focus,
            pool=pool,
        )
        print(f"[plan-tool] create_career_plan 成功: {time.perf_counter() - t0:.2f}s, planId={result.get('planId')}", flush=True)
        return json.dumps(result, ensure_ascii=False, default=str)
    except Exception as e:
        print(f"[plan-tool] create_career_plan 失败({time.perf_counter() - t0:.2f}s): {e}", flush=True)
        return json.dumps({"error": f"生成行动方案失败: {e}"}, ensure_ascii=False)


@tool
async def get_career_plan(
    plan_id: Optional[str] = None,
    token: Annotated[str, InjectedToolArg] = "",
) -> str:
    """查询「职业规划行动方案」的完整正文（markdown 全文：目标、现状诊断、当前差距、分阶段行动、风险、复盘检查点、说明等），用于回看或展开讲解某份方案。

    触发时机——用户想**看/回顾某份方案的完整内容、或展开其中某阶段/某动作的细节**时调用：
    - create_career_plan 刚生成后用户想立刻看方案全文、追问具体每一步做什么（create_career_plan 只返回摘要与引导，完整正文已落库，
      需要本工具按 planId 再取回）；
    - 用户提到某份方案要求展示完整内容（如「把我那份方案完整发我看」「上次方案的阶段二具体怎么安排」「最新方案里风险部分写了啥」）。

    plan_id（可选）：要查询的方案ID。对话中带 `planId:`/`planID:`/`plan_id:` 前缀或纯数字 id 时填进去（可去前缀只传纯ID）；
    想回看某份特定（含历史/已归档）版本时必须传。**未传时默认取「当前方案」（该用户最新一条 active，等价于前端『计划与行动方案』页
    展示的那份）**，不必为了看当前方案去猜测 ID。若该用户从未生成过方案，按返回提示引导其先 create_career_plan 生成。
    """
    raw_plan_id = str(plan_id or "").strip()
    normalized_plan_id = raw_plan_id.split(":", 1)[-1].strip() if ":" in raw_plan_id else raw_plan_id
    if not normalized_plan_id:
        normalized_plan_id = None

    # 透传用户 JWT：网关对 /users/me/plans/** 有登录校验，不带 token 会被 401 拦截
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    base_url = f"{settings.career_service_base_url}/users/me/plans"
    try:
        async with httpx.AsyncClient(timeout=20) as client:
            if normalized_plan_id:
                url = f"{base_url}/{normalized_plan_id}"
                resp = await client.get(url, headers=headers)
                resp.raise_for_status()
                payload = resp.json()
            else:
                # 未指定 ID：先拉列表（按时间倒序、含 status）定位「当前方案」，
                # 再取详情（列表不含 content 正文）。
                list_resp = await client.get(base_url, headers=headers)
                list_resp.raise_for_status()
                list_payload = list_resp.json()
                items = (list_payload.get("data") or {}).get("list") or []
                target = next((i for i in items if i.get("status") == "active"), None) or (items[0] if items else None)
                if target is None or target.get("id") is None:
                    return json.dumps(
                        {"error": "你还没有生成过职业规划行动方案，可先让我帮你规划一份（create_career_plan）"}, ensure_ascii=False
                    )
                resp = await client.get(f"{base_url}/{target['id']}", headers=headers)
                resp.raise_for_status()
                payload = resp.json()
    except Exception as e:
        return json.dumps({"error": f"获取行动方案失败: {e}"}, ensure_ascii=False)

    data = payload.get("data")
    if not data:
        return json.dumps(
            {"error": payload.get("msg") or f"方案 {normalized_plan_id or ''} 不存在或无权访问"},
            ensure_ascii=False,
        )

    return json.dumps(data, ensure_ascii=False, default=str)


@tool
async def submit_mock_interview_report(
    user_id: Annotated[int, InjectedToolArg] = 0,
    token: Annotated[str, InjectedToolArg] = "",
    session_id: Annotated[str, InjectedToolArg] = "",
    request: Annotated[Request, InjectedToolArg] = None,
) -> str:
    """结束当前模拟面试并生成「面试总结报告」保存（本工具不需要任何参数）。

    你是模拟面试官时使用本工具结束面试。触发时机（用户明确结束 / 已问满提问上限时**必须**调用）：
    - 用户表示「结束面试 / 出报告 / 就到这里 / 不面了 / 帮我总结」等；
    - 本场提问已达上限（材料里提示的提问上限）。

    工具内部会读取本场全部问答原文与开场时拉取的简历/JD 材料，调用独立报告模型生成
    {title, content}（含总体评价 / 亮点 / 薄弱项 / 逐题简评 / 补强建议），并自动保存到
    用户的面试记录（careers 库），本场面试随之结束。调用成功后请向用户转述报告标题与核心结论，
    并提示已保存，可在「面试记录」中回看；之后不要再提问。
    """
    t0 = time.perf_counter()
    print(
        f"[interview-tool] submit_mock_interview_report 调用: user_id={user_id}, session_id={session_id!r}",
        flush=True,
    )
    if not session_id:
        return json.dumps({"error": "缺少面试会话 id，无法生成报告"}, ensure_ascii=False)
    try:
        result = await interview.generate_and_save_report(
            session_id=session_id, user_id=int(user_id), token=token or ""
        )
        print(
            f"[interview-tool] submit_mock_interview_report 成功: "
            f"{time.perf_counter() - t0:.2f}s, reportId={result.get('reportId')}",
            flush=True,
        )
        return json.dumps(result, ensure_ascii=False, default=str)
    except Exception as e:
        print(f"[interview-tool] submit_mock_interview_report 失败({time.perf_counter() - t0:.2f}s): {e}", flush=True)
        return json.dumps({"error": f"生成面试报告失败: {e}"}, ensure_ascii=False)


# 所有可注册给模型的工具（新增工具只需追加到这里）
ALL_TOOLS = [
    get_student_profile,
    recommend_specific_jobs,
    query_job_detail,
    analyze_resume,
    polish_resume,
    recall_memory,
    create_career_plan,
    get_career_plan,
]

# 模拟面试专家 · 面试官主 LLM 专属工具集（不进 ALL_TOOLS，避免混入主对话助理；见 fc2026/模拟面试专家方案.md）
INTERVIEW_TOOLS = [
    get_student_profile,
    query_job_detail,
    submit_mock_interview_report,
]
