# boss_fetch — BOSS直聘岗位爬虫（独立脚本版）

从 BossFlow 项目中提取出的「爬取 BOSS直聘岗位 → 清洗 → 入库」核心代码，**与 BossFlow 的
FastAPI 后端 / Electron 桌面端 / MCP 完全解耦**，可单独作为一个脚本运行，无需任何前端。

原理：`crawler/boss.py` 用 **DrissionPage 驱动真实 Chrome**（带已登录 BOSS 直聘的持久化
Cookie），拦截 BOSS 前端自己的接口响应 `wapi/zpgeek/search/joblist.json`，滚动加载后拿到岗位；
再用 `pipeline.py` 清洗、`db.py` 写入 SQLite。不解析 HTML、不调第三方接口。

## 目录结构

```
boss_fetch/
├── crawler/                  # 爬虫包（原样搬自 BossFlow，保留相对 import）
│   ├── boss.py               #   核心：BossCrawler + 搜索 URL + 字段解析 + 人机模拟
│   ├── pipeline.py           #   清洗/过滤/分类/薪资解析
│   ├── db.py                 #   SQLite：jobs / crawl_runs 表、去重 upsert
│   ├── config.py             #   keywords.json 单例读写（可选）
│   ├── merger.py             #   旧 JSON/CSV 导出（兼容 __init__ 导出，实际用不上）
│   ├── platform_utils.py     #   登录辅助（弹窗/系统通知/激活 Chrome）
│   └── config/keywords.json  #   默认关键词 / 城市 / 分类规则 / 薪资下限
├── run.py                    # 独立脚本入口（等价 python -m crawler.boss）
└── requirements.txt
```

## 环境要求

- **Python ≥ 3.10**（代码用了 `dict | None` 等新语法；本机默认 `python` 若是 3.8 会导入失败）
- Chrome（已登录过一次 BOSS 直聘，之后 Cookie 持久化复用）
- 只需一个第三方库：`DrissionPage`

## 安装与运行

```bash
cd fc2026/boss_fetch
python -m venv .venv                          # 建议独立环境，避免用系统 Python3.8
.venv/Scripts/pip install -r requirements.txt # 即安装 DrissionPage

# 1) 首次：弹出真实 Chrome 手动登录一次，Cookie 存到 .chrome_profile（此后无需再登录）
.venv/Scripts/python run.py --login

# 2) 采集（headless 后台跑；清洗并入库 SQLite）
.venv/Scripts/python run.py --city 北京 --new-target 20 --max-jobs 100 --merge --db jobs_data.db
```

不使用 `run.py` 也可以直接以包方式运行（效果一致）：

```bash
.venv/Scripts/python -m crawler.boss --login
.venv/Scripts/python -m crawler.boss --city 北京 --merge --db jobs_data.db
```

`run.py` / `-m crawler.boss` 支持的全部参数：

| 参数 | 说明 |
| --- | --- |
| `--city 北京` | 只爬指定城市（其它城市用 `keywords.json` 里的 cities） |
| `--new-target N` | 每 关键词×城市 组合的新岗位目标数（默认 20） |
| `--max-jobs N` | 每组合最多浏览条数（默认 100） |
| `--login` | 仅登录保存 Cookie |
| `--headless` | 登录完成后切无头模式后台跑 |
| `--merge` | 采集后走 pipeline 清洗并入库 SQLite |
| `--db jobs_data.db` | SQLite 文件路径（配 `--merge` 用，默认 `jobs_data.db`） |
| `--config xxx.json` | 自定义关键词配置文件路径 |
| `--profile ./xxx` | Chrome 用户目录（Cookie），默认 `./.chrome_profile` |
| `--process-partial` | 直接处理中断保存的 `crawl_partial.json`，跳过爬虫 |
| `--new-target`/`--max-jobs` | 同上 |

> 细筛选（薪资/经验/学历/融资等 BOSS 站内筛选）CLI 不支持传，需在代码里
> `BossCrawler(...).run(keywords, cities, search_filters={...})`，见下。

## 代码方式调用（作为库/被其它服务调用）

```python
import sys; sys.path.insert(0, r"D:\desktop\university\career_agent\fc2026\boss_fetch")
from crawler import BossCrawler, process_batch
from crawler.db import upsert_jobs, save_run

crawler = BossCrawler(profile_dir="./.chrome_profile")   # 或复用已有登录 profile
raw = crawler.run(
    keywords=["大模型工程师"],
    cities={"北京": "101010100"},
    headless=True,
    new_job_target=20, max_jobs=100,
    search_filters={"salary": "406", "experience": "105", "degree": "203", "jobType": "1901"},
)
cleaned = process_batch(raw, target_keywords=["大模型工程师"])
stats = upsert_jobs(cleaned, "jobs_data.db")
print(stats)
```

## 常用代码片段

- `BossCrawler` 默认从 `crawler/config/keywords.json` 读关键词/城市/规则；
  自定义规则也可直接传 `process_batch(..., cat_rules=..., relevance_keywords=..., blacklist_keywords=...)`。
- 中断续爬：Ctrl+C 会落盘 `crawl_partial.json`，下次 `--process-partial crawl_partial.json --merge --db jobs_data.db` 续采。
- 已知/已入库岗位自动跳过详情抓取：`crawler.set_existing_job_index(load_existing_job_index(db))`。
- 只要列表不抓详情，可把 `BossCrawler._fetch_keyword_details` 短路，会快很多。

## 防封提醒

内置随机延迟/滚动节奏/UA/反检测注入，**别去掉**；不要并行开多个实例
（BOSS 对同一 Cookie 并发很敏感），频率过高触发安全验证时调大 `new_job_target` 间休息间隔。

## 来源

代码自 `BossFlow/crawler/` 原样拷贝（改代码时注意两边不同步）。完整接入说明见仓库根目录
`爬取岗位文档说明.md`。
