#!/usr/bin/env python3
"""BOSS直聘爬虫 —— boss_fetch 独立脚本入口。

等价于在 boss_fetch 目录下执行:  python -m crawler.boss  <参数>

用法示例（与爬取岗位文档说明.md 一致）:
  python run.py --login                                   # 仅登录并保存 Cookie
  python run.py --city 北京 --new-target 20 --max-jobs 100
  python run.py --merge --db jobs_data.db                 # 采集并清洗入库 SQLite
  python run.py --process-partial crawl_partial.json --merge --db jobs_data.db
"""
import sys
from pathlib import Path

# 保证无论从哪个目录启动，都能 import 本目录下的 crawler 包
sys.path.insert(0, str(Path(__file__).resolve().parent))

from crawler.boss import main  # noqa: E402

if __name__ == '__main__':
    sys.exit(main())
