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


class Train(ApiModel):
    destination: str | None
    remaining_sec: int | None
    message: str
    is_last: bool
    is_express: bool
    # 열차 번호. 열차 위치정보와 같은 열차를 잇는 열쇠다(위치 결합 스펙 6.3).
    train_no: str | None = None


class ArrivalGroup(ApiModel):
    line: str
    direction: str
    towards: str | None
    trains: list[Train]


class ArrivalsResponse(ApiModel):
    station_id: str
    updated_at: datetime
    stale: bool
    groups: list[ArrivalGroup]
