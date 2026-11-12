from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.api.params import parse_time
from app.core.container import AppServices, get_services
from app.models.schemas import RoutesResponse

router = APIRouter()


@router.get("/v1/routes", response_model=RoutesResponse, response_model_exclude_none=False)
async def search_routes(
    request: Request,
    services: Annotated[AppServices, Depends(get_services)],
    from_: Annotated[str, Query(alias="from")],
    to: str,
    via: str | None = None,
    depart_at: Annotated[str | None, Query(alias="departAt")] = None,
) -> RoutesResponse:
    """역 id에서 역 id까지 지하철 경로 최대 5개(재설계 스펙 5.3).

    `via`(경유역)가 있으면 두 구간을 합친다. `departAt`이 있으면 시간표 열차와 도착 예상을 붙인다.
    """
    response, cache_status = await services.routes.search(
        from_, to, via or None, parse_time(depart_at)
    )
    request.state.cache_status = cache_status
    return response
