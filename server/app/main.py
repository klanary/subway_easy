import time
from collections.abc import Callable

import httpx
from fastapi import FastAPI

from app.api import health
from app.core.config import Settings, get_settings
from app.core.container import build_services
from app.core.errors import install_error_handlers


def create_app(
    settings: Settings | None = None,
    *,
    clock: Callable[[], float] = time.time,
    transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="지금출발 서버")
    install_error_handlers(app)
    app.state.services = build_services(settings, clock, transport)
    app.include_router(health.router)
    return app


app = create_app()
