from datetime import datetime
from typing import Literal

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


class Place(ApiModel):
    name: str
    address: str | None
    lat: float
    lng: float
    category: str | None


class PlaceSearchResponse(ApiModel):
    items: list[Place]


class NearestStation(ApiModel):
    id: str
    name: str
    distance_m: int


class ReverseResponse(ApiModel):
    address: str | None
    nearest_station: NearestStation | None


class Step(ApiModel):
    mode: Literal["WALK", "SUBWAY", "BUS"]
    duration_sec: int
    distance_m: int
    guidance: str
    path: list[tuple[float, float]]  # (위도, 경도)
    line_name: str | None = None
    route_name: str | None = None
    stops: list[str] | None = None
    board_station_id: str | None = None
    alight_station_id: str | None = None


class Leg(ApiModel):
    steps: list[Step]


class FirstBoarding(ApiModel):
    station_id: str | None
    line_name: str
    towards: str | None
    sec_to_boarding: int
    all_walk_before_boarding: bool
    next_trains: list[Train] | None = None


class Route(ApiModel):
    id: str
    type: Literal["SUBWAY", "BUS", "MIXED", "WALK"]
    total_sec: int
    distance_m: int
    transfers: int
    fare: int | None
    legs: list[Leg]
    first_boarding: FirstBoarding | None


class RoutesResponse(ApiModel):
    updated_at: datetime
    stale: bool
    routes: list[Route]
