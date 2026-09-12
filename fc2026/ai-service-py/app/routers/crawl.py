"""BOSS 直聘岗位采集接口 —— 前端 aiHttp 直连（8086，不经过 Java 网关）。

对外提供四个动作：提交采集 / 查状态 / 取结果 / 请求停止。采集在后台 daemon 线程执行
（见 app/services/crawl_service.py），本路由只做参数校验与状态转发，不阻塞事件循环。

提交成功后即可轮询 GET /status（进度由 crawler 进度回调回填）；同一时刻只允许一个任务在跑，
重复提交返回 409。结果落在本地 SQLite（配置 CRAWLER_DB_FILE，默认 jobs_data.db）。
"""

from fastapi import APIRouter, Depends, HTTPException, status as http_status

from app.schemas import ApiResponse, CrawlStartRequest, CrawlStatusResponse
from app.security import get_current_user_id
from app.services import crawl_service

router = APIRouter(prefix="/users/me/crawl", tags=["crawl"])


@router.post("/start", response_model=ApiResponse[CrawlStatusResponse])
async def start_crawl(
    body: CrawlStartRequest,
    _: int = Depends(get_current_user_id),
) -> ApiResponse[CrawlStatusResponse]:
    """提交一个采集任务（异步）：接收筛选参数 → 后台调爬虫爬取 → 清洗 → 入 SQLite。

    返回任务状态；之后用 GET /status 轮询。字段均可空，空则用 crawler 配置默认
    （keywords.json：关键词/城市/分类规则；scrape_limits 里的数量）。
    """
    try:
        state = crawl_service.start_crawl(
            keywords=body.keywords,
            cities=body.cities,
            search_filters=body.search_filters,
            new_job_target=body.new_job_target,
            max_jobs=body.max_jobs,
            headless=body.headless,
        )
    except RuntimeError as e:  # 已有任务在跑
        raise HTTPException(status_code=http_status.HTTP_409_CONFLICT, detail=str(e)) from e
    except ValueError as e:  # 城市名解析失败
        raise HTTPException(status_code=http_status.HTTP_400_BAD_REQUEST, detail=str(e)) from e
    return ApiResponse(data=CrawlStatusResponse(**state))


@router.get("/status", response_model=ApiResponse[CrawlStatusResponse])
async def crawl_status(_: int = Depends(get_current_user_id)) -> ApiResponse[CrawlStatusResponse]:
    """查询当前采集任务状态（idle/running/stopping/done/error + 进度）。"""
    return ApiResponse(data=CrawlStatusResponse(**crawl_service.crawl_status()))


@router.get("/result", response_model=ApiResponse[CrawlStatusResponse])
async def crawl_result(_: int = Depends(get_current_user_id)) -> ApiResponse[CrawlStatusResponse]:
    """取最近一次采集结果：状态快照 + 清洗/入库统计 + 落库 SQLite 路径。"""
    state = crawl_service.crawl_status()
    if state["run_id"] is None:
        raise HTTPException(status_code=http_status.HTTP_404_NOT_FOUND,
                            detail="暂无采集记录，请先 POST /start 发起一次采集")
    return ApiResponse(data=CrawlStatusResponse(**state))


@router.get("/cities", response_model=ApiResponse[dict])
async def supported_cities(_: int = Depends(get_current_user_id)) -> ApiResponse[dict]:
    """支持的城市码表 {城市名: 城市码}，供前端筛选项渲染。"""
    return ApiResponse(data=crawl_service.supported_cities())


@router.post("/stop", response_model=ApiResponse[CrawlStatusResponse])
async def stop_crawl(_: int = Depends(get_current_user_id)) -> ApiResponse[CrawlStatusResponse]:
    """请求优雅停止当前采集（保存已采数据、关闭浏览器后任务进入 done/stopping）。"""
    if not crawl_service.stop_crawl():
        raise HTTPException(status_code=http_status.HTTP_400_BAD_REQUEST,
                            detail="当前没有运行中的采集任务")
    return ApiResponse(data=CrawlStatusResponse(**crawl_service.crawl_status()))
