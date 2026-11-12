from typing import Annotated

from fastapi import APIRouter, Depends, Request

from app.core.container import AppServices, get_services
from app.core.errors import InvalidRequest
from app.models.schemas import PlaceSearchResponse, ReverseResponse

router = APIRouter()

MAX_QUERY_LENGTH = 100


def _check_coords(lat: float | None, lng: float | None, required: bool) -> None:
    """위도·경도는 둘 다 주거나 둘 다 생략한다. 범위를 벗어나면 400."""
    if (lat is None) != (lng is None) or (required and lat is None):
        raise InvalidRequest("PARAMS")
    if lat is not None and not (-90 <= lat <= 90 and -180 <= lng <= 180):
        raise InvalidRequest("PARAMS")


@router.get("/v1/places/search", response_model=PlaceSearchResponse)
async def search_places(
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    q: str = "",
    lat: float | None = None,
    lng: float | None = None,
) -> PlaceSearchResponse:
    """장소 검색(스펙 6.2). 좌표를 주면 가까운 지점이 앞에 온다."""
    query = q.strip()
    if not 1 <= len(query) <= MAX_QUERY_LENGTH:
        raise InvalidRequest("PARAMS")
    _check_coords(lat, lng, required=False)
    items, cache_status = await services.places.search(query, lat, lng)
    request.state.cache_status = cache_status
    return PlaceSearchResponse(items=items)


@router.get("/v1/places/reverse", response_model=ReverseResponse)
async def reverse_geocode(
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    lat: float | None = None,
    lng: float | None = None,
) -> ReverseResponse:
    """지도 핀의 주소와 반경 2km 안의 가장 가까운 역(스펙 6.2, ③)."""
    _check_coords(lat, lng, required=True)
    response, cache_status = await services.places.reverse(lat, lng)
    request.state.cache_status = cache_status
    return response
