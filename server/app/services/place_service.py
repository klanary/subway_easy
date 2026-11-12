"""장소 검색과 좌표→주소·가까운 역 (스펙 6.2): 카카오 응답을 앱이 쓰는 형식으로 바꾼다."""

from app.cache.ttl_cache import TtlCache
from app.clients.kakao_local import KakaoLocalClient
from app.models.schemas import NearestStation, Place, ReverseResponse
from app.services.station_index import StationIndex

PLACE_TTL_SEC = 86_400  # 장소와 주소는 하루 안에 거의 바뀌지 않는다


def _to_place(doc: dict) -> Place:
    category = doc.get("category_group_name") or None
    if category is None and doc.get("category_name"):
        category = doc["category_name"].split(" > ")[-1]
    return Place(
        name=doc["place_name"],
        address=doc.get("road_address_name") or doc.get("address_name") or None,
        lat=float(doc["y"]),
        lng=float(doc["x"]),
        category=category,
    )


def _first_address(body: dict) -> str | None:
    documents = body.get("documents") or []
    if not documents:
        return None
    first = documents[0]
    for key in ("road_address", "address"):
        part = first.get(key)
        if part and part.get("address_name"):
            return part["address_name"]
    return None


class PlaceService:
    def __init__(
        self,
        client: KakaoLocalClient,
        index: StationIndex,
        search_cache: TtlCache[list[Place]],
        reverse_cache: TtlCache[str | None],
    ):
        self._client = client
        self._index = index
        self._search_cache = search_cache
        self._reverse_cache = reverse_cache

    async def search(self, q: str, lat: float | None, lng: float | None) -> tuple[list[Place], str]:
        # 좌표는 소수점 2자리(약 1km)로 묶어 캐시한다. 근처에서 같은 검색어면 결과가 거의 같다.
        key = f"{q}|{lat:.2f},{lng:.2f}" if lat is not None and lng is not None else f"{q}|-"

        async def fetch() -> list[Place]:
            body = await self._client.search_keyword(q, lat, lng)
            return [_to_place(doc) for doc in body.get("documents") or []]

        cached = await self._search_cache.get_or_fetch(key, fetch)
        return cached.value, cached.status

    async def reverse(self, lat: float, lng: float) -> tuple[ReverseResponse, str]:
        async def fetch() -> str | None:
            return _first_address(await self._client.coord_to_address(lat, lng))

        cached = await self._reverse_cache.get_or_fetch(f"{lat:.4f},{lng:.4f}", fetch)
        # 가까운 역은 서버 안의 역 데이터로 계산하므로 캐시하지 않는다.
        found = self._index.nearest(lat, lng)
        nearest = (
            NearestStation(id=found[0].id, name=found[0].name, distance_m=found[1])
            if found
            else None
        )
        return ReverseResponse(address=cached.value, nearest_station=nearest), cached.status
