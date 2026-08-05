from pathlib import Path

import jwt
from cryptography import x509
from fastapi import HTTPException, Request, status

# Java 侧 hmall.jks 导出的公钥证书（X.509），与网关 JWT 验签体系一致
_CERT_PATH = Path(__file__).parent / "hmall-public.pem"


def _load_public_key():
    """从 X.509 证书中提取 RSA 公钥（用于 RS256 验签）"""
    cert = x509.load_pem_x509_certificate(_CERT_PATH.read_bytes())
    return cert.public_key()


PUBLIC_KEY = _load_public_key()


def verify_token(token: str) -> int | None:
    """按 Java hutool JWT 体系验签（RS256），成功返回 userId，失败返回 None"""
    try:
        payload = jwt.decode(
            token,
            PUBLIC_KEY,
            algorithms=["RS256"],
            options={"require": ["user", "exp"]},
        )
        return int(payload.get("user"))
    except Exception:
        return None


async def get_current_user_id(request: Request) -> int:
    """FastAPI 依赖：解析 JWT 得到真实 userId。

    支持两种携带方式：
    - 请求头 `Authorization: Bearer <token>`（普通接口）
    - URL 查询参数 `?token=<token>`（SSE 流，EventSource 无法自定义请求头）
    """
    token = None
    auth = request.headers.get("authorization")
    if auth and auth.startswith("Bearer "):
        token = auth[7:]
    if not token:
        token = request.query_params.get("token")
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或登录失效")

    user_id = verify_token(token)
    if user_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="token 无效或已过期")
    return user_id
