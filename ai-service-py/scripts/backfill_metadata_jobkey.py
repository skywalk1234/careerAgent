# -*- coding: utf-8 -*-
"""一次性回填：把 job_detail_vector.metadata 里的 jobKey 对齐到 job_key 列。

背景：build_metadata 原先把 jobKey / jobId 都写成了 BOSS 招聘帖 id（boss:{encryptJobId}），
现在改为 jobKey = job_key 列（采集去重键）、jobId 保持 BOSS 招聘帖 id。本脚本负责把
改动前已入库的行刷成同一口径。

- 幂等：只处理 metadata->>'jobKey' 与 job_key 不相等的行，重复跑不会二次改动。
- metadata 是 json 类型（不是 jsonb），jsonb_set 需要先 ::jsonb 再转回来。
- 不动内容/向量：只改 metadata，embedding / vector_ready / content 一律不碰。

用法：
    python scripts/backfill_metadata_jobkey.py            # dry-run，只统计与抽样
    python scripts/backfill_metadata_jobkey.py --apply    # 实际写入
"""

import argparse
import asyncio
import json
import sys
from pathlib import Path

import asyncpg

# 保证从任意 cwd 都能 import 到 app 包
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import settings  # noqa: E402

TABLE = "job_detail_vector"

# 待回填的行：metadata 非空，且 jobKey 与 job_key 列不一致（含 metadata 里根本没有 jobKey 的行）
_PENDING_WHERE = f"""
    metadata IS NOT NULL
    AND metadata ->> 'jobKey' IS DISTINCT FROM job_key
"""

_PREVIEW_SQL = f"""
SELECT job_key,
       metadata ->> 'jobKey' AS old_job_key,
       metadata ->> 'jobId'  AS job_id,
       title
FROM {TABLE}
WHERE {_PENDING_WHERE}
ORDER BY id
LIMIT $1
"""

_COUNT_SQL = f"SELECT count(*) FROM {TABLE} WHERE {_PENDING_WHERE}"

_UPDATE_SQL = f"""
UPDATE {TABLE}
SET metadata = jsonb_set(metadata::jsonb, '{{jobKey}}', to_jsonb(job_key))::json
WHERE {_PENDING_WHERE}
"""


async def run(apply: bool) -> None:
    conn = await asyncpg.connect(settings.vector_database_url)
    try:
        total_rows = await conn.fetchval(f"SELECT count(*) FROM {TABLE}")
        pending = await conn.fetchval(_COUNT_SQL)

        print(f"表 {TABLE} 共 {total_rows} 行，其中 jobKey 与 job_key 列不一致的有 {pending} 行")
        if pending == 0:
            print("无需回填。")
            return

        print("\n--- 抽样（最多 5 行）---")
        for row in await conn.fetch(_PREVIEW_SQL, 5):
            print(json.dumps({
                "job_key": row["job_key"],
                "old_jobKey": row["old_job_key"],
                "jobId": row["job_id"],
                "title": row["title"],
            }, ensure_ascii=False))

        if not apply:
            print("\n[dry-run] 未写入。确认无误后加 --apply 再跑一次。")
            return

        # 单条 UPDATE 语句，自身即原子；用事务包一层只为拿准确的受影响行数
        async with conn.transaction():
            status = await conn.execute(_UPDATE_SQL)
        print(f"\n[ok] 回填完成：{status}")

        left = await conn.fetchval(_COUNT_SQL)
        print(f"[ok] 复查：仍不一致的行数 = {left}（应为 0）")
    finally:
        await conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="回填 metadata.jobKey = job_key 列")
    parser.add_argument("--apply", action="store_true", help="实际写入（默认只做 dry-run）")
    args = parser.parse_args()
    asyncio.run(run(args.apply))


if __name__ == "__main__":
    main()
