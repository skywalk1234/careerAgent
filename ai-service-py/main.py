from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.database import init_db
from app.routers import chat


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时自动建表
    await init_db()
    yield


app = FastAPI(title=settings.app_name, lifespan=lifespan)

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
