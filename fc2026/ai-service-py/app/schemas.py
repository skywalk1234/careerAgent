from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """统一响应外壳 {code, msg, data}，与前端 services/http.ts 约定一致"""

    code: int = 200
    msg: str = "success"
    data: T | None = None


# ===================== 会话 =====================

class CreateSessionRequest(BaseModel):
    """前端 createHomeSession 发送的请求体"""

    scene: str = "home"
    temperature: float = 0.3


class CreateSessionResponse(BaseModel):
    sessionId: str
    createdAt: datetime
    welcomeMessage: None = None


class SessionItem(BaseModel):
    """会话列表项（alias 输出驼峰，对齐前端 HomeSession）"""

    session_id: str = Field(alias="sessionId")
    title: str
    last_message_preview: str = Field(alias="lastMessagePreview")
    pinned: bool
    favorited: bool
    updated_at: datetime = Field(alias="updatedAt")

    model_config = {"from_attributes": True, "populate_by_name": True}


class SessionListResponse(BaseModel):
    total: int
    list: list[SessionItem]


# ===================== 消息 =====================

class FileContextIn(BaseModel):
    hasResumeFile: bool = False
    fileNames: list[str] = []


class SendMessageRequest(BaseModel):
    """前端 createHomeSessionMessage 发送的请求体（暂未用到的字段仅做兼容接收）"""

    content: str = Field(..., min_length=1, description="用户输入的消息内容")
    temperature: float = 0.4
    traceId: str | None = None
    fileContext: FileContextIn | None = None
    pageContext: dict | None = None
    executionMode: str = "auto"
    goal: str = ""
    taskPolicy: dict | None = None
    toolContext: dict | None = None


class HomeMessage(BaseModel):
    """聊天消息（alias 输出驼峰，对齐前端 HomeMessage）"""

    message_id: str = Field(alias="messageId")
    role: str
    content: str
    status: str
    created_at: datetime = Field(alias="createdAt")
    file_names: list[str] = Field(default=[], alias="fileNames")
    agent_trace: dict | None = Field(default=None, alias="agentTrace")
    actions: list[dict] = []
    task_result_card: dict | None = Field(default=None, alias="taskResultCard")

    model_config = {"from_attributes": True, "populate_by_name": True}


class MessageListResponse(BaseModel):
    sessionId: str
    total: int
    list: list[HomeMessage]


class StreamConfigOut(BaseModel):
    protocol: str
    url: str


class SendMessageResponse(BaseModel):
    sessionId: str
    userMessage: HomeMessage
    assistantMessage: HomeMessage
    stream: StreamConfigOut


# ===================== 模拟面试（面试官 agent，独立会话，见 fc2026/模拟面试专家方案.md） =====================

INTERVIEW_TYPES = ("technical", "behavior", "mixed")


class InterviewCreateRequest(BaseModel):
    """开场建会话：jobId / profileId 由用户在开场时提供（页面带入或对话里给），可不全，缺哪个开场由面试官索取"""

    jobId: str | None = None
    profileId: str | None = None
    type: str = "mixed"  # technical / behavior / mixed


class InterviewSessionItem(BaseModel):
    """面试会话列表项（alias 输出驼峰）"""

    session_id: str = Field(alias="sessionId")
    type: str
    status: str
    job_id: str | None = Field(default=None, alias="jobId")
    job_title: str | None = Field(default=None, alias="jobTitle")
    company_name: str | None = Field(default=None, alias="companyName")
    profile_id: str | None = Field(default=None, alias="profileId")
    report_id: int | None = Field(default=None, alias="reportId")
    created_at: datetime = Field(alias="createdAt")
    ended_at: datetime | None = Field(default=None, alias="endedAt")

    model_config = {"from_attributes": True, "populate_by_name": True}


class InterviewSessionListResponse(BaseModel):
    total: int
    list: list[InterviewSessionItem]


class InterviewCreateResponse(BaseModel):
    sessionId: str
    type: str
    status: str
    createdAt: datetime
    stream: StreamConfigOut


class InterviewSendRequest(BaseModel):
    content: str = Field(..., min_length=1, description="用户本轮回答")


class InterviewMessageItem(BaseModel):
    """面试问答消息（alias 输出驼峰）"""

    message_id: str = Field(alias="messageId")
    role: str
    content: str
    status: str
    created_at: datetime = Field(alias="createdAt")

    model_config = {"from_attributes": True, "populate_by_name": True}


class InterviewMessageListResponse(BaseModel):
    sessionId: str
    total: int
    list: list[InterviewMessageItem]


class InterviewSendResponse(BaseModel):
    sessionId: str
    userMessage: InterviewMessageItem
    stream: StreamConfigOut


# ===================== BOSS 岗位采集（app/services/crawl_service.py） =====================

class CrawlStartRequest(BaseModel):
    """提交采集任务的请求体（字段均可空，空则取 crawler 配置默认）。

    注意：城市码表（BOSS 内置 20 城 + 配置 cities）在 ai-service-py/crawler 的
    boss.py load_config / config/keywords.json 里，请求只传城市名，后台负责解析成码。
    """

    keywords: list[str] | None = Field(
        default=None, description="岗位搜索词，标题需包含该词才会保留"
    )
    cities: list[str] | None = Field(
        default=None, description="意向城市名列表（如 ['北京']），后台解析为城市码"
    )
    search_filters: dict[str, str] | None = Field(
        default=None,
        alias="searchFilters",
        description="BOSS 站内筛选 code，如 {'salary':'406','experience':'105',"
        "'degree':'203','jobType':'1901'}，值只认数字 code（CLI 不支持的二级筛选）",
    )
    new_job_target: int | None = Field(
        default=None, ge=1, alias="newJobTarget",
        description="每 关键词×城市 组合的新岗位目标数，达到即停",
    )
    max_jobs: int | None = Field(
        default=None, ge=1, alias="maxJobs",
        description="每组合最多浏览岗位数（上限）",
    )
    headless: bool | None = Field(
        default=None, description="是否无头采集；缺省取配置 crawler_headless"
    )

    model_config = {"populate_by_name": True}


class CrawlStatusResponse(BaseModel):
    """采集任务状态快照（camelCase 输出，对齐前端约定）"""

    run_id: str | None = Field(default=None, alias="runId")
    # idle / running / stopping / done / error
    status: str = "idle"
    combo_index: int | None = Field(default=None, alias="comboIndex")
    total_combos: int | None = Field(default=None, alias="totalCombos")
    current_keyword: str | None = Field(default=None, alias="currentKeyword")
    current_city: str | None = Field(default=None, alias="currentCity")
    jobs_so_far: int = Field(default=0, alias="jobsSoFar")
    started_at: datetime | None = Field(default=None, alias="startedAt")
    finished_at: datetime | None = Field(default=None, alias="finishedAt")
    # done 后统计 {input, inserted, updated, skipped}；error 后 message 为原因
    stats: dict | None = Field(default=None)
    db_file: str | None = Field(default=None, alias="dbFile")
    message: str | None = Field(default=None)

    model_config = {"populate_by_name": True}
