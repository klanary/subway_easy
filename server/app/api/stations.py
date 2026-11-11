from typing import Annotated

from fastapi import APIRouter, Depends, Request

from app.core.container import AppServices, get_services
from app.core.errors import InvalidRequest
from app.models.schemas import ArrivalsResponse, StationItem, StationSearchResponse

router = APIRouter()

MAX_QUERY_LENGTH = 50
MAX_LIMIT = 50


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
