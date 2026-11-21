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
    daily_limit: int | None  # None = 제한 없음
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
    # 열차가 지금 있는 역 이름(arvlMsg3). 역 시트의 열차 위치 표시에 쓴다(수동 검토 2026-11-22).
    current_station: str | None = None


class ArrivalGroup(ApiModel):
    line: str
    direction: str | None  # 비서울 노선(source "none")은 None
    towards: str | None
    trains: list[Train]
    # 열차 정보의 출처(재설계 스펙 5.1): 실시간, 시간표(실시간이 없는 방향), 없음(비서울 노선)
    # measured: 시간표 API가 없는 노선을 VM 실측 시간표로 채운 것(설계 2026-11-17)
    source: Literal["realtime", "timetable", "measured", "none"] = "realtime"
    # 이 방향으로 볼 때 바로 앞(열차가 오는 쪽)·바로 뒤(가는 쪽) 역 이름(수동 검토 2026-11-22)
    prev_station: str | None = None
    next_station: str | None = None


class ArrivalsResponse(ApiModel):
    station_id: str
    updated_at: datetime
    stale: bool
    groups: list[ArrivalGroup]


class TimetableTrain(ApiModel):
    departs_at: datetime
    destination: str | None
    is_express: bool
    train_no: str | None
    # 공식 시간표(서울시) 또는 실측(VM 위치 수집, 설계 2026-11-17)
    source: Literal["official", "measured"] = "official"


class TimetableResponse(ApiModel):
    station_id: str
    line: str
    direction: str
    trains: list[TimetableTrain]
    # 열차들의 출처. 열차가 없으면 none
    source: Literal["official", "measured", "none"] = "none"


class NearestStation(ApiModel):
    id: str
    name: str
    distance_m: int


class NearestResponse(ApiModel):
    station: NearestStation | None


class TransferTrain(ApiModel):
    """환승역에서 탈 열차. 첫째가 걸어서 탈 수 있는 빠른 열차, 둘째가 그다음 열차다."""

    departs_at: datetime
    destination: str | None
    source: Literal["realtime", "timetable", "measured"]


class Candidate(ApiModel):
    """구간에서 탈 수 있는 열차(열차 고르기 설계 2026-11-18): 탈 역 출발, 내릴 역 도착."""

    train_no: str | None
    departs_at: datetime
    arrives_at: datetime
    is_express: bool
    destination: str | None
    source: Literal["official", "measured"]
    estimated: bool


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
    # 환승해서 타는 지하철 단계만: 타고 온 열차가 이 역에 도착하는 예상 시각과 그 뒤 출발 열차 2대
    board_after: datetime | None = None
    transfer_trains: list[TransferTrain] | None = None
    # 운행표로 고른 열차(경로 설계 2026-11-18): 탈 역 출발, 내릴 역 도착, 번호·급행·행선지·출처
    departs_at: datetime | None = None
    arrives_at: datetime | None = None
    train_no: str | None = None
    is_express: bool = False
    destination: str | None = None
    source: Literal["official", "measured"] | None = None
    estimated: bool = False  # 구간 시간으로 어림한 시각(번호로 못 묶는 노선)
    candidates: list[Candidate] | None = None  # 이 구간에서 고를 수 있는 열차(출발 순)


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
    # 출발 시각을 지정했을 때만: 첫 열차 출발 시각과 도착 예상(재설계 스펙 5.3)
    depart_at: datetime | None = None
    arrive_at: datetime | None = None


class RoutesResponse(ApiModel):
    updated_at: datetime
    stale: bool
    routes: list[Route]
