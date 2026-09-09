"""BOSS 直聘岗位采集 —— 后台异步采集管理器（单例）。

把独立脚本 boss_fetch 的爬虫包（已迁到 ai-service-py 顶层 crawler/）以库方式接进 FastAPI：

- 爬虫是**同步阻塞分钟级**任务（DrissionPage 驱动真实 Chrome），不能跑进 asyncio 事件循环，
  这里用独立 daemon 线程执行；同一时刻只允许一个采集任务（同 Cookie 并发敏感，见 boss_fetch README）。
- 服务端跑必须关掉 platform_utils 的模态登录弹窗：本模块 import 时即设 AI_PM_UNATTENDED=1
  （若 Cookie 失效不会卡在「请先登录」对话框等人点确认，而是快速失败 → 任务进入 error）。
- 采集 → 清洗 → 入库 SQLite 全流程与脚本 `--merge` 行为一致：
  crawler.run() → process_batch() → upsert_jobs() → save_run()。
- 进度通过 crawler 的 set_progress_callback 回填到快照，供 GET /status 轮询；stop() 走
  crawler.request_stop() 优雅停止（后台线程内不能注册信号，见 boss.py _setup_signal_handler）。
- 线程内中断语义：run() 只返回「详情已完整」的岗位子集，未抓完详情的留在 crawl_partial.json（可续采）。

限制：单进程假设（uvicorn 单 worker，python main.py 启动）；多 worker 部署需进程级互斥，本期未做。
"""

import os
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

# 防 platform_utils 弹模态登录框；必须在任何 crawler 调用前生效
os.environ.setdefault("AI_PM_UNATTENDED", "1")

from app.config import settings  # noqa: E402

# 与 scripts/import_intern_jobs.py 一致：ai-service-py 根目录
PROJECT_ROOT = Path(__file__).resolve().parents[2]
# 爬虫配置（关键词/城市/分类规则），默认取迁入的 keywords.json
CRAWLER_CONFIG_FILE = str(PROJECT_ROOT / "crawler" / "config" / "keywords.json")
# 探测用：不存在的路径 → load_config 返回内置 20 城默认表（含文件里没写的城）
_BUILTIN_PROBE = str(Path(CRAWLER_CONFIG_FILE).with_name("__builtin_probe__.json"))

from crawler import BossCrawler  # noqa: E402
from crawler.boss import BossAuthenticationError, load_cities, load_config, load_keywords  # noqa: E402
from crawler.db import load_existing_job_index, save_run, upsert_jobs  # noqa: E402
from crawler.pipeline import MIN_AVG_SALARY_K, process_batch  # noqa: E402


def _resolve(path: str) -> Path:
    """相对路径按 ai-service-py 根目录解析，绝对路径原样返回。"""
    p = Path(path).expanduser()
    return p if p.is_absolute() else (PROJECT_ROOT / p)


def _city_code_table() -> dict:
    """支持的城市表 = 内置 20 城 ∪ 配置文件 cities（请求里城市名按此解析为码）。"""
    file_cities = load_config(CRAWLER_CONFIG_FILE).get("cities") or {}
    builtin_cities = load_config(_BUILTIN_PROBE).get("cities") or {}
    table = dict(builtin_cities)
    table.update(file_cities)
    return table


class _RunSnapshot:
    """进程内任务状态（worker 线程写、HTTP 读，用 _lock 保护）。"""

    def __init__(self) -> None:
        self.run_id: Optional[str] = None
        self.status: str = "idle"  # idle / running / stopping / done / error
        self.stop_requested: bool = False
        self.completed_units: int = 0
        self.total_units: int = 0
        self.current_keyword: str = ""
        self.current_city: str = ""
        self.jobs_so_far: int = 0
        self.started_at: Optional[str] = None
        self.finished_at: Optional[str] = None
        self.keywords: list = []
        self.cities: list = []
        self.stats: Optional[dict] = None
        self.db_file: Optional[str] = None
        self.message: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "run_id": self.run_id,
            "status": self.status,
            "combo_index": self.completed_units,
            "total_combos": self.total_units,
            "current_keyword": self.current_keyword,
            "current_city": self.current_city,
            "jobs_so_far": self.jobs_so_far,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "stats": self.stats,
            "db_file": self.db_file,
            "message": self.message,
        }


class CrawlManager:
    """采集任务管理器（模块级单例 _manager）。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._snap = _RunSnapshot()
        self._crawler: Optional[BossCrawler] = None
        self._thread: Optional[threading.Thread] = None

    # ---------- 对外只读 ----------

    @property
    def busy(self) -> bool:
        with self._lock:
            return self._thread is not None and self._thread.is_alive()

    def status(self) -> dict:
        with self._lock:
            return self._snap.to_dict()

    # ---------- 启动 ----------

    def start(
        self,
        keywords: Optional[list] = None,
        cities: Optional[list] = None,
        search_filters: Optional[dict] = None,
        new_job_target: Optional[int] = None,
        max_jobs: Optional[int] = None,
        headless: Optional[bool] = None,
        profile_dir: Optional[str] = None,
        db_file: Optional[str] = None,
    ) -> dict:
        """校验参数并后台启动一个采集任务。

        返回状态快照。若已有任务在跑，raise RuntimeError（路由转 409）；城市名非法 raise ValueError（转 400）。
        """
        with self._lock:
            if self.busy:
                raise RuntimeError("已有采集任务在运行中，请先等待完成或停止")

            cfg = load_config(CRAWLER_CONFIG_FILE)
            keywords = list(keywords) if keywords else list(load_keywords(CRAWLER_CONFIG_FILE))
            table = _city_code_table()
            if not cities:
                cities = list(load_cities(CRAWLER_CONFIG_FILE).keys())
            city_map: dict = {}
            for name in cities:
                code = table.get(name)
                if not code:
                    raise ValueError(f"未知城市「{name}」，支持: {', '.join(table)}")
                city_map[name] = code

            db = _resolve(db_file or settings.crawler_db_file)
            profile = _resolve(profile_dir or settings.crawler_profile_dir)
            if headless is None:
                headless = settings.crawler_headless

            self._snap = _RunSnapshot()
            snap = self._snap
            snap.run_id = uuid.uuid4().hex[:12]
            snap.status = "running"
            snap.started_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
            snap.keywords = keywords
            snap.cities = list(city_map.keys())
            snap.db_file = str(db)
            snap.total_units = len(keywords) * len(city_map)

            crawler = BossCrawler(
                profile_dir=str(profile),
                config_file=CRAWLER_CONFIG_FILE,
            )
            # 已入库岗位跳过详情页抓取（复用 db 现有索引，首次运行空索引无害）
            try:
                crawler.set_existing_job_index(load_existing_job_index(str(db)))
            except Exception:
                pass  # 库文件不存在/损坏时忽略，仍可全量爬

            crawler.set_progress_callback(self._on_progress)
            crawler.set_crawl_started_callback(self._on_crawl_started)
            crawler.set_auth_failed_callback(self._on_auth_failed)
            self._crawler = crawler

            thread = threading.Thread(
                target=self._run_worker,
                args=(crawler, keywords, city_map, search_filters, new_job_target,
                      max_jobs, headless, str(db)),
                name=f"boss-crawl-{snap.run_id}",
                daemon=True,
            )
            self._thread = thread
            thread.start()
            return self._snap.to_dict()

    # ---------- 停止 ----------

    def stop(self) -> bool:
        """请求优雅停止当前任务，成功返回 True；无运行任务返回 False。"""
        with self._lock:
            if not self.busy or self._crawler is None:
                return False
            self._snap.stop_requested = True
            self._snap.status = "stopping"
            self._snap.message = "停止请求已发出，正在保存已采集数据..."
            try:
                self._crawler.request_stop()
            except Exception:
                pass
            return True

    # ---------- 回调（worker 线程内执行） ----------

    def _on_progress(self, combo_idx: int, total_combos: int, keyword: str,
                     city_name: str, total_jobs: int) -> None:
        # 每次回调=一个「关键词×城市」组合已采完；first 参数无全局含义，只用它做计数
        with self._lock:
            snap = self._snap
            snap.completed_units = min(snap.total_units or combo_idx, snap.completed_units + 1)
            snap.current_keyword = keyword
            snap.current_city = city_name
            snap.jobs_so_far = int(total_jobs)
            if snap.status not in ("stopping", "done", "error"):
                snap.status = "running"

    def _on_crawl_started(self) -> None:
        with self._lock:
            if self._snap.status not in ("stopping", "done", "error"):
                self._snap.status = "running"

    def _on_auth_failed(self) -> None:
        with self._lock:
            if self._snap.status not in ("done", "error"):
                self._snap.status = "running"  # 具体失败由 run() 抛 BossAuthenticationError 收尾

    # ---------- worker 线程主流程 ----------

    def _run_worker(self, crawler: BossCrawler, keywords: list, city_map: dict,
                    search_filters: Optional[dict], new_job_target: Optional[int],
                    max_jobs: Optional[int], headless: bool, db_file: str) -> None:
        """同步执行：爬取 → 清洗 → 入库 → 落审计（与 boss_fetch --merge 一致）。"""
        try:
            raw_jobs = crawler.run(
                keywords,
                city_map,
                headless=headless,
                new_job_target=new_job_target,
                max_jobs=max_jobs,
                search_filters=search_filters,
            )
            if not raw_jobs:
                self._finish("done", stats=None, message="未抓取到岗位（可能关键词过窄、城市无数据或已全部入库）")
                return

            cfg = load_config(CRAWLER_CONFIG_FILE)
            cleaned = process_batch(
                raw_jobs,
                cat_rules=cfg.get("cat_rules"),
                min_salary=float(cfg.get("min_salary", MIN_AVG_SALARY_K)),
                relevance_keywords=cfg.get("relevance_keywords"),
                blacklist_keywords=cfg.get("blacklist_keywords"),
                target_keywords=keywords,
            )
            stats = upsert_jobs(cleaned, db_file)
            save_run(
                db_file,
                keywords=keywords,
                cities=list(city_map.keys()),
                mode="crawl-api",
                raw_count=len(raw_jobs),
                cleaned_count=len(cleaned),
                added_count=stats["inserted"],
            )
            self._finish(
                "done",
                stats={"raw": len(raw_jobs), "cleaned": len(cleaned), **stats},
                message=f"采集完成：新增 {stats['inserted']} 条，刷新 {stats['updated']} 条，跳过 {stats['skipped']} 条",
            )
        except BossAuthenticationError:
            self._finish(
                "error",
                stats=None,
                message=(
                    "BOSS 登录态失效或未登录。请先准备已登录 BOSS 直聘的 Chrome profile："
                    "本地跑一次 boss_fetch 的 run.py --login（profile 指向 CRAWLER_PROFILE_DIR），"
                    "或把已有登录 profile 复制到该目录后重试。"
                ),
            )
        except Exception as e:  # noqa: BLE001 —— 后台任务兜底，不让线程静默死亡
            self._finish("error", stats=None, message=f"采集异常: {type(e).__name__}: {e}")

    def _finish(self, status: str, stats: Optional[dict], message: Optional[str]) -> None:
        with self._lock:
            snap = self._snap
            snap.status = status
            snap.stats = stats
            snap.message = message
            snap.finished_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
            snap.completed_units = snap.total_units  # 结束即视为跑满（便于前端算进度条）


# 模块级单例
_manager = CrawlManager()


def start_crawl(*args: Any, **kwargs: Any) -> dict:
    return _manager.start(*args, **kwargs)


def stop_crawl() -> bool:
    return _manager.stop()


def crawl_status() -> dict:
    return _manager.status()


def supported_cities() -> dict:
    """支持的城市码表 {城市名: 城市码}（供前端筛选项渲染）。"""
    return dict(_city_code_table())
