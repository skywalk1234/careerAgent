from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.config import settings
from app.database import init_db
from app.routers import chat


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时自动建表
    await init_db()
    yield


app = FastAPI(
    title="AI 求职助手服务",
    description=(
        "基于 DeepSeek 的求职助手聊天服务。\n\n"
        "- 交互式接口测试页面（Swagger UI）：`/docs`\n"
        "- 接口文档（ReDoc）：`/redoc`\n"
        "- 服务端口：8086（不经过 Java 网关，前端直连）"
    ),
    version="1.0.0",
    lifespan=lifespan,
)

# 开发阶段放开跨域，方便前端联调
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(chat.router, tags=["chat"])


@app.get("/health")
async def health():
    return {"status": "ok"}


# 统一错误响应格式 {code, msg, data}，与前端 services/http.ts 的约定一致
@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"code": exc.status_code, "msg": str(exc.detail), "data": None},
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    return JSONResponse(
        status_code=422,
        content={"code": 422, "msg": "请求参数校验失败", "data": None},
    )


if __name__ == "__main__":
    import uvicorn

    # 注意：Windows 下 uvicorn 的 reload(watchfiles) 不稳定，此处关闭；改代码后手动重启即可
    uvicorn.run("main:app", host="0.0.0.0", port=settings.server_port)
