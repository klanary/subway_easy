from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.container import AppServices, get_services
from app.models.schemas import CacheStats, MetricsResponse, RequestCounts, UpstreamStats

router = APIRouter()


@router.get("/v1/metrics", response_model=MetricsResponse)
async def metrics(services: Annotated[AppServices, Depends(get_services)]) -> MetricsResponse:
    """부하 테스트와 운영 확인용 지표(스펙 6.2). 서버가 재시작되면 0부터 다시 센다."""
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
