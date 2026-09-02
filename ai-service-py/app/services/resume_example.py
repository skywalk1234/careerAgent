"""简历样例库 RAG 检索：简历按节拆片 → 逐段检索 resume_example_vector → 并集取 top_k。

样例库一条记录 = {content: 简历片段, metadata: {jobCategory / snippetType / comment / 来源...}}。
检索键是简历文本（输入侧）而非点评文字：向量匹配「简历像不像」，专家点评 comment
存在 metadata 中不参与 embedding，命中后随样例一起返回，供润色阶段作 few-shot 历史案例。
设计见 fc2026/简历RAG方案.md。本模块不持有任何状态。
"""

import re
import time
from typing import Optional

from app.config import settings
from app.services.embedding import embed_text, truncate_for_embedding
from app.services.vector_store import TABLE_RESUME_EXAMPLE, similarity_search

# 标题命中这些词的节视作「项目/经历」类待打磨片段（教育/技能/自我评价等节不参与检索）
_SECTION_HINTS = ("项目", "经历", "实习", "工作", "实践", "project", "experience")
# 过短的片段没有检索意义，直接丢弃
_MIN_CHUNK_LEN = 30


def _split_paragraphs(body: str) -> list[str]:
    """按空行把一节正文切成段落（与样例库「一条样例一段」的粒度对齐）"""
    return [p.strip() for p in re.split(r"\n\s*\n", body) if len(p.strip()) >= _MIN_CHUNK_LEN]


def split_resume_sections(resume: str) -> list[str]:
    """把简历 markdown 拆成检索片段列表。

    规则：
    1. 按 # 开头的标题切节，只保留标题命中 项目/经历/实习/工作/实践 的节；
    2. 命中节内部再按空行切成段落，段落即检索片段；
    3. 找不到命中节时退化：取全部节；仍为空则整份简历兜底，保证检索不空转。
    """
    # 按标题切节：(节标题, 节正文)，标题行本身不入正文
    raw: list[tuple[Optional[str], str]] = []
    title: Optional[str] = None
    buf: list[str] = []
    for line in resume.splitlines():
        if line.startswith("#"):
            if buf:
                raw.append((title, "\n".join(buf).strip()))
                buf = []
            title = line.lstrip("#").strip()
        else:
            buf.append(line)
    if buf:
        raw.append((title, "\n".join(buf).strip()))

    wanted = [
        body for t, body in raw
        if t and any(hint in t.lower() for hint in _SECTION_HINTS)
    ]
    if not wanted:
        # 整份简历没有项目/经历类节（如纯教育/技能简历）：退化到全部节
        wanted = [body for _, body in raw if body]

    chunks: list[str] = []
    for body in wanted:
        chunks.extend(_split_paragraphs(body))
    if not chunks:
        chunks = [resume.strip()]

    # 段落可能重复，去重后返回
    seen: set[str] = set()
    result: list[str] = []
    for c in chunks:
        if c not in seen:
            seen.add(c)
            result.append(c)
    return result


def _to_reference(row) -> dict:
    """把检索结果精简成供 LLM 参考的样例结构（点评随样例返回，但绝不参与向量匹配）"""
    md = row.metadata or {}
    return {
        "resumeSnippet": row.content,
        "comment": md.get("comment", ""),
        "jobCategory": md.get("jobCategory"),
        "snippetType": md.get("snippetType"),
        "similarity": round(row.similarity, 3),
        "postTitle": md.get("postTitle"),
        "sourceUrl": md.get("sourceUrl"),
    }


async def retrieve_resume_examples(pool, resume: str) -> list[dict]:
    """逐段检索样例库并取并集，返回精简样例列表。

    每段独立检索（top_k/阈值取 config 的 resume_example_*），合并且按相似度排序后
    整体截断到 resume_example_top_k。无命中返回 []，由上层跳过 RAG ——
    样例库只增强、不阻塞润色。
    """
    _t0 = time.perf_counter()
    chunks = split_resume_sections(resume)
    print(f"[resume-example] 简历拆分出 {len(chunks)} 个检索片段", flush=True)
    if not chunks:
        return []

    all_rows = []
    for i, chunk in enumerate(chunks, 1):
        embedding = await embed_text(truncate_for_embedding(chunk))
        rows = await similarity_search(
            pool,
            TABLE_RESUME_EXAMPLE,
            embedding,
            top_k=settings.resume_example_top_k,
            threshold=settings.resume_example_threshold,
        )
        print(f"[resume-example] 片段 {i}/{len(chunks)} 检索命中 {len(rows)} 条", flush=True)
        all_rows.extend(rows)

    # 并集去重（按样例 id），按相似度降序后整体截断
    seen: set[str] = set()
    uniq = []
    for r in sorted(all_rows, key=lambda r: r.similarity, reverse=True):
        if r.id not in seen:
            seen.add(r.id)
            uniq.append(r)
    top = uniq[: settings.resume_example_top_k]
    print(
        f"[resume-example] 并集去重 {len(uniq)} 条 → 取 top {len(top)}，"
        f"总耗时 {time.perf_counter() - _t0:.2f}s",
        flush=True,
    )
    return [_to_reference(r) for r in top]
