"""앱이 쓰는 의존 객체를 한곳에서 만든다. create_app이 app.state.services에 담는다."""

from collections.abc import Callable
from dataclasses import dataclass

import httpx
from fastapi import Request

from app.core.config import Settings


@dataclass
class AppServices:
    settings: Settings
    clock: Callable[[], float]


def build_services(
    settings: Settings,
    clock: Callable[[], float],
    transport: httpx.AsyncBaseTransport | None,
) -> AppServices:
    return AppServices(settings=settings, clock=clock)


def get_services(request: Request) -> AppServices:
    return request.app.state.services
