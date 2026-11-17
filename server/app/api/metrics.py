import secrets
from typing import Annotated

from fastapi import APIRouter, Depends, Header

from app.core.container import AppServices, get_services
from app.core.errors import NotFound
from app.models.schemas import CacheStats, MetricsResponse, RequestCounts, UpstreamStats

router = APIRouter()


@router.get("/v1/metrics", response_model=MetricsResponse)
async def metrics(
    services: Annotated[AppServices, Depends(get_services)],
    token: Annotated[str, Header(alias="X-Metrics-Token")] = "",
) -> MetricsResponse:
    """부하 테스트와 운영 확인용 지표(스펙 6.2). 서버가 재시작되면 0부터 다시 센다.

    외부 API 호출 수·한도가 보이므로 METRICS_TOKEN을 아는 사람만 본다. 없거나 틀리면 404다.
    """
    expected = services.settings.metrics_token
    if not expected or not secrets.compare_digest(token.encode(), expected.encode()):
        raise NotFound(message="없는 주소예요.")
    m = services.metrics
    return MetricsResponse(
        since=m.since,
        requests=RequestCounts(total=m.requests_total, rate_limited=m.rate_limited),
        cache=CacheStats(hits=m.cache_hits, misses=m.cache_misses, hit_rate=m.hit_rate()),
        upstream={
            name: UpstreamStats.model_validate(guard.snapshot())
            for name, guard in services.quotas.items()
        },
        unmatched_stations=len(m.unmatched_station_names),
    )
