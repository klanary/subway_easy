from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.container import AppServices, get_services
from app.core.errors import InvalidRequest
from app.models.schemas import StationItem, StationSearchResponse

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
