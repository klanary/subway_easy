"""앱이 쓰는 의존 객체를 한곳에서 만든다. create_app이 app.state.services에 담는다."""

from collections.abc import Callable
from dataclasses import dataclass

import httpx
from fastapi import Request

from app.cache.quota_guard import QuotaGuard
from app.core.clock import now_kst
from app.core.config import Settings
from app.core.metrics import Metrics


@dataclass
class AppServices:
    settings: Settings
    clock: Callable[[], float]
    metrics: Metrics
    quotas: dict[str, QuotaGuard]


def build_quotas(settings: Settings, clock: Callable[[], float]) -> dict[str, QuotaGuard]:
    limits = {
        "seoulRealtime": settings.seoul_realtime_daily_limit,
        "kakaoRoute": settings.kakao_route_daily_limit,
        "kakaoKeyword": settings.kakao_keyword_daily_limit,
        "kakaoCoord2Address": settings.kakao_coord2address_daily_limit,
    }
    return {name: QuotaGuard(name, limit, clock) for name, limit in limits.items()}


def build_services(
    settings: Settings,
    clock: Callable[[], float],
    transport: httpx.AsyncBaseTransport | None,
) -> AppServices:
    return AppServices(
        settings=settings,
        clock=clock,
        metrics=Metrics(since=now_kst(clock)),
        quotas=build_quotas(settings, clock),
    )


def get_services(request: Request) -> AppServices:
    return request.app.state.services
