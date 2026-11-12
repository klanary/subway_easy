from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.core.container import AppServices, get_services
from app.models.schemas import RoutesResponse

router = APIRouter()


@router.get("/v1/routes", response_model=RoutesResponse, response_model_exclude_none=False)
async def search_routes(
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    from_: Annotated[str, Query(alias="from")],
    to: str,
) -> RoutesResponse:
    """역 id에서 역 id까지 지하철 경로 최대 5개(재설계 스펙 5.3). 같은 출발·도착은 10분 캐시."""
    response, cache_status = await services.routes.search(from_, to)
    request.state.cache_status = cache_status
    return response
