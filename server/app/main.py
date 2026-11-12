import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from uuid import uuid4

import httpx
from fastapi import FastAPI, Request, Response

from app.api import health, metrics, places, stations
from app.core.config import Settings, get_settings
from app.core.container import build_services
from app.core.errors import RateLimited, error_response, install_error_handlers
from app.core.logging import configure_logging
from app.core.rate_limit import client_ip

access_logger = logging.getLogger("app.access")

# 요청 제한에서 뺀다: 호스팅 헬스 체크와 깨우기 호출이 막히면 서비스가 재시작된다(스펙 6.8).
_UNLIMITED_PATHS = {"/health"}


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    yield
    await app.state.services.http.aclose()


async def request_context(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    """요청 ID, IP별 요청 제한, 요청 수 지표, 접근 로그."""
    services = request.app.state.services
    request_id = uuid4().hex[:12]
    request.state.request_id = request_id
    path = request.url.path  # 쿼리(좌표·검색어)는 로그에 남기지 않는다
    started = time.perf_counter()
    status = 500
    try:
        if path.startswith("/v1/") and path != "/v1/metrics":
            services.metrics.requests_total += 1
        if path not in _UNLIMITED_PATHS and not services.rate_limiter.allow(client_ip(request)):
            services.metrics.rate_limited += 1
            response = error_response(RateLimited())
        else:
            response = await call_next(request)
        status = response.status_code
        response.headers["X-Request-ID"] = request_id
        return response
    finally:
        access_logger.info(
            "access",
            extra={
                "request_id": request_id,
                "method": request.method,
                "path": path,
                "status": status,
                "duration_ms": round((time.perf_counter() - started) * 1000),
                "cache": getattr(request.state, "cache_status", "none"),
            },
        )


def create_app(
    settings: Settings | None = None,
    *,
    clock: Callable[[], float] = time.time,
    transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging()
    app = FastAPI(title="지금출발 서버", lifespan=lifespan)
    install_error_handlers(app)
    app.middleware("http")(request_context)
    app.state.services = build_services(settings, clock, transport)
    app.include_router(health.router)
    app.include_router(metrics.router)
    app.include_router(stations.router)
    app.include_router(places.router)
    return app


app = create_app()
