from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.api.params import parse_time
from app.core.clock import now_kst
from app.core.container import AppServices, get_services
from app.core.errors import InvalidRequest
from app.models.schemas import (
    ArrivalsResponse,
    NearestResponse,
    NearestStation,
    StationItem,
    StationSearchResponse,
    TimetableResponse,
)
from app.services.normalize import normalize_line

router = APIRouter()

MAX_QUERY_LENGTH = 50
MAX_LIMIT = 50
MAX_TIMETABLE_COUNT = 10


@router.get("/v1/stations/search", response_model=StationSearchResponse)
async def search_stations(
    services: Annotated[AppServices, Depends(get_services)],
    q: str = "",
    limit: int = 20,
) -> StationSearchResponse:
    """역 이름·초성 검색(스펙 6.2, 6.5). 역 데이터는 서버에 있어 외부 API를 부르지 않는다."""
    query = q.strip()
    if not 1 <= len(query) <= MAX_QUERY_LENGTH or not 1 <= limit <= MAX_LIMIT:
        raise InvalidRequest("PARAMS")
    return StationSearchResponse(
        items=[
            StationItem(id=s.id, name=s.name, lines=list(s.lines), lat=s.lat, lng=s.lng)
            for s in services.stations.search(query, limit)
        ]
    )


@router.get("/v1/stations/nearest", response_model=NearestResponse)
async def nearest_station(
    services: Annotated[AppServices, Depends(get_services)],
    lat: float | None = None,
    lng: float | None = None,
) -> NearestResponse:
    """현재 위치에서 반경 2km 안의 가장 가까운 역(재설계 스펙 6). 외부 호출은 없다."""
    if lat is None or lng is None or not (-90 <= lat <= 90 and -180 <= lng <= 180):
        raise InvalidRequest("PARAMS")
    found = services.stations.nearest(lat, lng)
    if found is None:
        return NearestResponse(station=None)
    station, distance_m = found
    return NearestResponse(
        station=NearestStation(id=station.id, name=station.name, distance_m=distance_m)
    )


@router.get("/v1/stations/{station_id}/arrivals", response_model=ArrivalsResponse)
async def station_arrivals(
    station_id: str,
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    line: str | None = None,
    towards: str | None = None,
) -> ArrivalsResponse:
    """호선·방향별 다음 열차 최대 2대(스펙 6.2). 서울시 응답은 20초 동안 캐시한다."""
    response, cache_status = await services.arrivals.get_arrivals(station_id, line, towards)
    request.state.cache_status = cache_status
    return response


@router.get("/v1/stations/{station_id}/timetable", response_model=TimetableResponse)
async def station_timetable(
    station_id: str,
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    line: str,
    direction: str,
    from_: Annotated[str | None, Query(alias="from")] = None,
    count: int = 4,
) -> TimetableResponse:
    """`from` 이후 시간표 열차 `count`대(기본 4, 최대 10). 재설계 스펙 6."""
    if not 1 <= count <= MAX_TIMETABLE_COUNT:
        raise InvalidRequest("PARAMS")
    after = parse_time(from_) or now_kst(services.clock)
    trains, cache_status = await services.timetable.trains_with_status(
        station_id, line, direction, after, count
    )
    request.state.cache_status = cache_status
    return TimetableResponse(
        station_id=station_id, line=normalize_line(line), direction=direction, trains=trains
    )
