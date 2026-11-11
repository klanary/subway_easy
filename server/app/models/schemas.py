from datetime import datetime

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    """모든 응답 모델의 부모. 파이썬에서는 snake_case, JSON에서는 camelCase를 쓴다."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class RequestCounts(ApiModel):
    total: int
    rate_limited: int


class CacheStats(ApiModel):
    hits: int
    misses: int
    hit_rate: float


class UpstreamStats(ApiModel):
    calls_today: int
    daily_limit: int
    errors_today: int


class MetricsResponse(ApiModel):
    since: datetime
    requests: RequestCounts
    cache: CacheStats
    upstream: dict[str, UpstreamStats]
    unmatched_stations: int


class StationItem(ApiModel):
    id: str
    name: str
    lines: list[str]
    lat: float
    lng: float


class StationSearchResponse(ApiModel):
    items: list[StationItem]
