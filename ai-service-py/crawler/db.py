"""PostgreSQL(pgvector) 存储：采集结果直接写入向量库 job_detail_vector（不再写 SQLite）。

与 SQLite 版（boss_fetch/crawler/db.py）的区别：

- 落库目标换成 8.147.71.59:40086/ai-vector 的 `job_detail_vector`（见 岗位向量库job_detail_vector.md），
  采集审计写同库的 `crawl_runs` 表（本模块首次运行自动 CREATE TABLE IF NOT EXISTS）。
- 本阶段**不触发向量化**：`embedding` 恒为 NULL、`vector_ready` 恒为 false；`content`/`metadata`
  先按文档结构写好，后续向量化脚本按 `WHERE vector_ready = false` 补嵌入即可。
- 对外仍保持**同步函数签名**（爬虫在 worker 线程里同步调用，见 app/services/crawl_service.py），
  内部用 asyncio.run 驱动 asyncpg；连接池不常驻，每次调用开一条连接（一次采集只调用个位数次数）。
- 函数签名保留 `db_file` 形参以兼容调用方（boss.py CLI 的 --db、crawl_service 的 db_file 路径），
  现在该参数已无意义，仅作兼容 / 日志用途。

metadata 严格按 岗位向量库job_detail_vector.md 给出的结构写（jobKey/jobId/jobName/companyName/
companySize/companyStage/city/district/salaryText/salaryMin/salaryMax/salaryUnit/salaryAvgK/
salaryTier/experience/education/source/sourceSite/sourceUrl/updatedAtRaw/contentHash/embeddingModel）。
注意：RAG 消费方 app/services/job_recommend.py 的 build_candidate() 读的是 educationRequirement/
level/salaryNormalized 等键（人工录入脚本 scripts/import_intern_jobs.py 走的是那套），
本模块按文档结构写，那几个键取不到值（null），如需对齐消费方再补键。
"""
import asyncio
import hashlib
import json
import os
import re
import threading
import datetime as dt
from typing import Iterable, Optional

import asyncpg
from pgvector.asyncpg import register_vector

# 仅内部常量，杜绝 SQL 注入
TABLE_JOB_DETAIL = 'job_detail_vector'
TABLE_CRAWL_RUNS = 'crawl_runs'

SOURCE_BOSS = 'boss'
SOURCE_SITE_BOSS = 'BOSS直聘'
# 仅用于 metadata.embeddingModel 标注（本阶段不真的嵌入）
EMBEDDING_MODEL = 'text-embedding-v1'

JOB_DETAIL_ID_RE = re.compile(r'/job_detail/([^/?#]+)\.html')

# 薪资区间解析：20-35K / 300-400元/天 / 15000-25000元/月
_SALARY_PATTERNS = (
    (re.compile(r'(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*K\b', re.IGNORECASE), 'K'),
    (re.compile(r'(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*元\s*/\s*天'), '元/天'),
    (re.compile(r'(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*元\s*/\s*月'), '元/月'),
)

# crawl_runs：采集审计（对齐旧 SQLite 表列名，db_file 列沿用旧名存目标标识）
DDL_CRAWL_RUNS = f'''
CREATE TABLE IF NOT EXISTS {TABLE_CRAWL_RUNS} (
    id            bigserial PRIMARY KEY,
    started_at    timestamptz,
    finished_at   timestamptz,
    keywords_json jsonb DEFAULT '[]'::jsonb,
    cities_json   jsonb DEFAULT '[]'::jsonb,
    mode          text,
    raw_count     integer DEFAULT 0,
    cleaned_count integer DEFAULT 0,
    added_count   integer DEFAULT 0,
    db_file       text,
    note          text
)
'''


# ==================== 连接 / DSN ====================

def get_dsn() -> str:
    """向量库 DSN：优先 app.config.settings（其本身已支持 VECTOR_DATABASE_URL 环境变量覆盖），
    脱离服务单独跑时退回读同名环境变量。"""
    try:
        from app.config import settings  # 延迟 import：不作为包依赖
        if settings.vector_database_url:
            return settings.vector_database_url
    except Exception:
        pass
    dsn = os.environ.get('VECTOR_DATABASE_URL')
    if dsn:
        return dsn
    raise RuntimeError(
        '未配置向量库 DSN：请设置环境变量 VECTOR_DATABASE_URL，'
        '或在 app.config.settings.vector_database_url 中配置'
    )


def _run(coro):
    """在同步上下文里执行协程。当前线程已有事件循环时（被 async 端点直接调用），
    放到临时线程里跑，避免 "asyncio.run() cannot be called from a running event loop"。"""
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)

    box: dict = {}

    def _worker():
        try:
            box['value'] = asyncio.run(coro)
        except BaseException as exc:  # noqa: BLE001 —— 跨线程回传异常
            box['error'] = exc

    thread = threading.Thread(target=_worker, daemon=True)
    thread.start()
    thread.join()
    if 'error' in box:
        raise box['error']
    return box.get('value')


async def _connect() -> asyncpg.Connection:
    """开一条连接并注册 pgvector 编解码（embedding 列以 Python list 读写）。
    注意 asyncpg.connect 没有 init 钩子（只有 create_pool 有），需手动注册。"""
    conn = await asyncpg.connect(get_dsn())
    await register_vector(conn)
    return conn


async def _ensure_schema(conn: asyncpg.Connection) -> None:
    """建采集审计表；job_detail_vector 由人工按 DDL 建，不在此自动创建。"""
    await conn.execute(DDL_CRAWL_RUNS)


async def _require_job_table(conn: asyncpg.Connection) -> None:
    exists = await conn.fetchval('SELECT to_regclass($1)', TABLE_JOB_DETAIL)
    if exists is None:
        raise RuntimeError(
            f'向量库缺少 {TABLE_JOB_DETAIL} 表，请先执行 岗位向量库job_detail_vector.md 中的建表语句'
        )


# ==================== 字段解析 ====================

def _job_key(job: dict) -> str:
    return str(job.get('_key') or '').strip()


def _json_dumps(value, default=None) -> str:
    return json.dumps(value if value is not None else default if default is not None else [],
                      ensure_ascii=False, default=str)


def _json_loads(value):
    if not value:
        return []
    if isinstance(value, (list, dict)):
        return value
    try:
        return json.loads(value)
    except Exception:
        return []


def _encrypt_job_id(url: str) -> str:
    match = JOB_DETAIL_ID_RE.search(str(url or '').strip())
    return match.group(1) if match else ''


def _parse_salary_range(salary_text: str) -> tuple:
    """解析薪资文本 → (min, max, unit)；解不出返回 (None, None, None)。"""
    text = str(salary_text or '')
    for pattern, unit in _SALARY_PATTERNS:
        match = pattern.search(text)
        if match:
            return float(match.group(1)), float(match.group(2)), unit
    return None, None, None


def _content_hash(content: str) -> str:
    return 'sha1:' + hashlib.sha1(str(content or '').encode('utf-8')).hexdigest()


def build_metadata(job: dict) -> dict:
    """严格按 岗位向量库job_detail_vector.md 的 metadata 结构构建。

    companySize / companyStage / district 当前爬虫未采集，保留键、值为 None。
    jobKey / jobId 取 BOSS 招聘帖 id（encryptJobId，来自 url），取不到时退回采集去重键。
    """
    title = str(job.get('title') or '').strip()
    company = str(job.get('company') or '').strip()
    city = str(job.get('city') or '').strip()
    salary_text = str(job.get('salary') or '').strip()
    url = str(job.get('url') or '').strip()
    content = str(job.get('desc') or '').strip()
    salary_min, salary_max, salary_unit = _parse_salary_range(salary_text)

    encrypt_id = _encrypt_job_id(url) or str(job.get('security_id') or '').strip() or _job_key(job)
    boss_identity = f'boss:{encrypt_id}' if encrypt_id else ''

    return {
        'jobKey': boss_identity,
        'jobId': boss_identity,
        'jobName': title,
        'companyName': company,
        'companySize': None,
        'companyStage': None,
        'city': city,
        'district': None,
        'salaryText': salary_text,
        'salaryMin': salary_min,
        'salaryMax': salary_max,
        'salaryUnit': salary_unit,
        'salaryAvgK': float(job.get('avg') or 0),
        'salaryTier': str(job.get('tier') or '').strip(),
        'experience': str(job.get('exp') or '').strip(),
        'education': str(job.get('edu') or '').strip(),
        'source': str(job.get('_source') or SOURCE_BOSS),
        'sourceSite': SOURCE_SITE_BOSS,
        'sourceUrl': url,
        'updatedAtRaw': str(job.get('_date') or dt.date.today().isoformat()),
        'contentHash': _content_hash(content),
        'embeddingModel': EMBEDDING_MODEL,
    }


# ==================== 写入 ====================

_UPSERT_SQL = f'''
INSERT INTO {TABLE_JOB_DETAIL} (
    job_key, security_id, title, company, city, salary, avg, tier, exp, edu,
    cats_json, kw_json, url, source, first_seen, last_seen, crawled_at, is_new,
    raw_json, content, metadata, vector_ready
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10,
    $11::jsonb, $12::jsonb, $13, $14, $15, $15, $15, 1,
    $16::jsonb, $17, $18::json, false
)
ON CONFLICT (job_key) DO UPDATE SET
    security_id = EXCLUDED.security_id,
    title       = EXCLUDED.title,
    company     = EXCLUDED.company,
    city        = EXCLUDED.city,
    salary      = EXCLUDED.salary,
    avg         = EXCLUDED.avg,
    tier        = EXCLUDED.tier,
    exp         = EXCLUDED.exp,
    edu         = EXCLUDED.edu,
    cats_json   = EXCLUDED.cats_json,
    kw_json     = EXCLUDED.kw_json,
    url         = EXCLUDED.url,
    source      = EXCLUDED.source,
    -- first_seen 保留首次入库时间，不覆盖
    last_seen   = EXCLUDED.last_seen,
    crawled_at  = EXCLUDED.crawled_at,
    is_new      = 0,
    raw_json    = EXCLUDED.raw_json,
    content     = EXCLUDED.content,
    metadata    = EXCLUDED.metadata,
    -- JD 变了则旧向量失效；本阶段 embedding 恒为 NULL，这里留好后续向量化的钩子
    embedding    = CASE WHEN {TABLE_JOB_DETAIL}.content IS DISTINCT FROM EXCLUDED.content
                        THEN NULL ELSE {TABLE_JOB_DETAIL}.embedding END,
    vector_ready = CASE WHEN {TABLE_JOB_DETAIL}.content IS DISTINCT FROM EXCLUDED.content
                        THEN false ELSE {TABLE_JOB_DETAIL}.vector_ready END
RETURNING (xmax = 0) AS inserted
'''


async def _upsert_async(jobs: list) -> dict:
    now = dt.datetime.now(dt.timezone.utc)
    inserted = updated = skipped = 0

    conn = await _connect()
    try:
        await _require_job_table(conn)
        async with conn.transaction():
            for job in jobs:
                if not isinstance(job, dict):
                    skipped += 1
                    continue
                key = _job_key(job)
                title = str(job.get('title') or '').strip()
                if not key or not title:
                    skipped += 1
                    continue

                payload = (
                    key,
                    str(job.get('security_id') or '').strip(),
                    title,
                    str(job.get('company') or '').strip(),
                    str(job.get('city') or '').strip(),
                    str(job.get('salary') or '').strip(),
                    float(job.get('avg') or 0),
                    str(job.get('tier') or '').strip(),
                    str(job.get('exp') or '').strip(),
                    str(job.get('edu') or '').strip(),
                    _json_dumps(job.get('cats')),
                    _json_dumps(job.get('kw')),
                    str(job.get('url') or '').strip(),
                    str(job.get('_source') or SOURCE_BOSS),
                    now,
                    _json_dumps(job, default={}),
                    str(job.get('desc') or '').strip(),
                    _json_dumps(build_metadata(job), default={}),
                )
                row = await conn.fetchrow(_UPSERT_SQL, *payload)
                if row and row['inserted']:
                    inserted += 1
                else:
                    updated += 1
    finally:
        await conn.close()

    return {
        'input': inserted + updated + skipped,
        'inserted': inserted,
        'updated': updated,
        'skipped': skipped,
    }


def upsert_jobs(jobs: Iterable[dict], db_file: Optional[str] = None) -> dict:
    """写入/刷新 job_detail_vector。返回 {input, inserted, updated, skipped}。

    db_file 参数仅为兼容旧调用方保留，现已无意义。
    """
    return _run(_upsert_async(list(jobs or [])))


# ==================== 读取（跳过详情抓取 / 兼容旧接口） ====================

async def _load_existing_job_index_async() -> dict:
    conn = await _connect()
    try:
        await _require_job_table(conn)
        rows = await conn.fetch(
            f'SELECT id, job_key, url, raw_json FROM {TABLE_JOB_DETAIL}'
        )
    finally:
        await conn.close()

    by_encrypt_id: dict = {}
    by_job_key: dict = {}
    for row in rows:
        row_id = int(row['id'])
        job_key = str(row['job_key'] or '').strip()
        if job_key:
            by_job_key[job_key] = row_id

        encrypt_id = _encrypt_job_id(row['url'])
        if not encrypt_id and row['raw_json']:
            raw = _json_loads(row['raw_json'])
            raw = raw if isinstance(raw, dict) else {}
            encrypt_id = _encrypt_job_id(raw.get('url'))
        if encrypt_id:
            by_encrypt_id[encrypt_id] = row_id

    return {
        'by_encrypt_id': by_encrypt_id,
        'by_job_key': by_job_key,
        'job_count': len(rows),
    }


def load_existing_job_index(db_file: Optional[str] = None) -> dict:
    """加载已入库岗位标识，供爬虫跳过详情抓取（db_file 参数已无意义）。"""
    return _run(_load_existing_job_index_async())


async def _touch_existing_jobs_async(job_ids: list) -> int:
    if not job_ids:
        return 0
    conn = await _connect()
    try:
        await _require_job_table(conn)
        result = await conn.execute(
            f'UPDATE {TABLE_JOB_DETAIL} SET last_seen = $1, is_new = 0 '
            'WHERE id = ANY($2::bigint[])',
            dt.datetime.now(dt.timezone.utc),
            job_ids,
        )
    finally:
        await conn.close()
    try:
        return int(str(result).rsplit(' ', 1)[-1])
    except (ValueError, IndexError):
        return 0


def touch_existing_jobs(job_ids: Iterable[int], db_file: Optional[str] = None) -> int:
    """只刷新已入库岗位的 last_seen / is_new，不重抓详情。"""
    normalized = sorted({int(x) for x in (job_ids or []) if int(x) > 0})
    return _run(_touch_existing_jobs_async(normalized))


async def _load_jobs_async() -> list:
    conn = await _connect()
    try:
        await _require_job_table(conn)
        rows = await conn.fetch(
            f'''
            SELECT id, job_key, security_id, title, company, city, salary, avg, tier,
                   exp, edu, cats_json, kw_json, url, is_new, last_seen, crawled_at, content
            FROM {TABLE_JOB_DETAIL}
            ORDER BY avg DESC NULLS LAST, last_seen DESC NULLS LAST, id DESC
            '''
        )
    finally:
        await conn.close()

    jobs = []
    for row in rows:
        jobs.append({
            'title': row['title'],
            'company': row['company'],
            'city': row['city'],
            'salary': row['salary'],
            'avg': row['avg'],
            'tier': row['tier'],
            'exp': row['exp'],
            'edu': row['edu'],
            'cats': _json_loads(row['cats_json']),
            'kw': _json_loads(row['kw_json']),
            'desc': row['content'],
            'url': row['url'],
            'is_new': bool(row['is_new']),
            'security_id': row['security_id'],
            '_key': row['job_key'],
            '_date': row['last_seen'].isoformat() if row['last_seen'] else '',
            '_crawled_at': row['crawled_at'].isoformat() if row['crawled_at'] else '',
        })
    return jobs


def load_jobs(db_file: Optional[str] = None) -> list:
    """读回岗位（供旧接口/本地查看；db_file 参数已无意义）。"""
    return _run(_load_jobs_async())


# ==================== 采集审计 ====================

async def _save_run_async(**kwargs) -> None:
    finished_at = kwargs.get('finished_at') or dt.datetime.now(dt.timezone.utc)
    started_at = kwargs.get('started_at')
    if isinstance(started_at, str):
        started_at = dt.datetime.fromisoformat(started_at)
    conn = await _connect()
    try:
        await _ensure_schema(conn)
        await conn.execute(
            f'''
            INSERT INTO {TABLE_CRAWL_RUNS} (
                started_at, finished_at, keywords_json, cities_json, mode,
                raw_count, cleaned_count, added_count, db_file, note
            ) VALUES ($1, $2, $3::jsonb, $4::jsonb, $5, $6, $7, $8, $9, $10)
            ''',
            started_at,
            finished_at,
            _json_dumps(kwargs.get('keywords')),
            _json_dumps(kwargs.get('cities')),
            kwargs.get('mode') or '',
            int(kwargs.get('raw_count') or 0),
            int(kwargs.get('cleaned_count') or 0),
            int(kwargs.get('added_count') or 0),
            f'pgvector:{TABLE_JOB_DETAIL}',
            kwargs.get('note') or '',
        )
    finally:
        await conn.close()


def save_run(db_file: Optional[str] = None, **kwargs) -> None:
    """写一条采集审计到 crawl_runs（db_file 参数仅为兼容旧签名，落库值固定为 pgvector 标识）。"""
    _run(_save_run_async(**kwargs))
