"""앱이 쓰는 의존 객체를 한곳에서 만든다. create_app이 app.state.services에 담는다."""

from collections.abc import Callable
from dataclasses import dataclass

import httpx
from fastapi import Request

from app.cache.quota_guard import QuotaGuard
from app.core.clock import now_kst
from app.core.config import Settings
from app.core.metrics import Metrics
from app.core.rate_limit import SlidingWindowRateLimiter
from app.services.station_index import StationIndex


@dataclass
class AppServices:
    settings: Settings
    clock: Callable[[], float]
    metrics: Metrics
    quotas: dict[str, QuotaGuard]
    http: httpx.AsyncClient
    rate_limiter: SlidingWindowRateLimiter
    stations: StationIndex


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
    """transport를 주면(테스트) 외부 호출이 그 transport로 간다."""
    return AppServices(
        settings=settings,
        clock=clock,
        metrics=Metrics(since=now_kst(clock)),
        quotas=build_quotas(settings, clock),
        http=httpx.AsyncClient(transport=transport, timeout=settings.upstream_timeout_sec),
        rate_limiter=SlidingWindowRateLimiter(settings.rate_limit_per_minute),
        stations=StationIndex.load(settings.stations_db_path),
    )


def get_services(request: Request) -> AppServices:
    return request.app.state.services
