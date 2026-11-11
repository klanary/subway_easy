"""앱이 쓰는 의존 객체를 한곳에서 만든다. create_app이 app.state.services에 담는다."""

from collections.abc import Callable
from dataclasses import dataclass

import httpx
from fastapi import Request

from app.cache.quota_guard import QuotaGuard
from app.cache.ttl_cache import TtlCache
from app.clients.seoul_subway import SeoulSubwayClient
from app.core.clock import now_kst
from app.core.config import Settings
from app.core.metrics import Metrics
from app.core.rate_limit import SlidingWindowRateLimiter
from app.services.arrival_service import ARRIVALS_TTL_SEC, ArrivalService
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
    arrivals: ArrivalService


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
    metrics = Metrics(since=now_kst(clock))
    quotas = build_quotas(settings, clock)
    http = httpx.AsyncClient(transport=transport, timeout=settings.upstream_timeout_sec)
    stations = StationIndex.load(settings.stations_db_path)
    seoul_guard = quotas["seoulRealtime"]
    arrivals = ArrivalService(
        SeoulSubwayClient(http, seoul_guard, settings),
        stations,
        # 한도의 80%를 넘으면 유효 시간이 3배가 된다(스펙 6.4).
        TtlCache(
            ARRIVALS_TTL_SEC,
            clock=clock,
            metrics=metrics,
            ttl_multiplier=seoul_guard.ttl_multiplier,
        ),
        clock,
    )
    return AppServices(
        settings=settings,
        clock=clock,
        metrics=metrics,
        quotas=quotas,
        http=http,
        rate_limiter=SlidingWindowRateLimiter(settings.rate_limit_per_minute),
        stations=stations,
        arrivals=arrivals,
    )


def get_services(request: Request) -> AppServices:
    return request.app.state.services
