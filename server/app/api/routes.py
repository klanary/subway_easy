from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.api.params import parse_latlng
from app.core.container import AppServices, get_services
from app.models.schemas import RoutesResponse

router = APIRouter()


@router.get("/v1/routes", response_model=RoutesResponse, response_model_exclude_none=False)
async def search_routes(
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    from_: Annotated[str, Query(alias="from")] = "",
    to: str = "",
    from_name: Annotated[str | None, Query(alias="fromName")] = None,
    to_name: Annotated[str | None, Query(alias="toName")] = None,
) -> RoutesResponse:
    """대중교통 경로 최대 5개(스펙 6.2). 같은 출발·도착은 10분 동안 캐시한다."""
    origin, dest = parse_latlng(from_), parse_latlng(to)
    response, cache_status = await services.routes.search(origin, dest, from_name, to_name)
    request.state.cache_status = cache_status
    return response
