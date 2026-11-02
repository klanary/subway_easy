from fastapi import FastAPI

from app.api import health
from app.core.config import Settings, get_settings


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="지금출발 서버")
    app.state.settings = settings
    app.include_router(health.router)
    return app


app = create_app()
