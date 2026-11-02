"""앱이 쓰는 의존 객체를 한곳에서 만든다. create_app이 app.state.services에 담는다."""

from collections.abc import Callable
from dataclasses import dataclass

import httpx
from fastapi import Request

from app.core.clock import now_kst
from app.core.config import Settings
from app.core.metrics import Metrics


@dataclass
class AppServices:
    settings: Settings
    clock: Callable[[], float]
    metrics: Metrics


def build_services(
    settings: Settings,
    clock: Callable[[], float],
    transport: httpx.AsyncBaseTransport | None,
) -> AppServices:
    return AppServices(settings=settings, clock=clock, metrics=Metrics(since=now_kst(clock)))


def get_services(request: Request) -> AppServices:
    return request.app.state.services
