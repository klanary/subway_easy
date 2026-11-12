"""장소 검색과 좌표→주소·가까운 역. 카카오 샘플은 2026-11-12 본인 키로 받은 실제 응답이다."""

import httpx

from app.clients.endpoints import KAKAO_LOCAL_BASE
from tests.helpers import fixture_response, load_fixture

KEYWORD = f"{KAKAO_LOCAL_BASE}/search/keyword.json"
COORD = f"{KAKAO_LOCAL_BASE}/geo/coord2address.json"


async def test_keyword_search_maps_fields_and_sends_xy(client, respx_mock):
    route = respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_hongik.json"))
    params = {"q": "홍익대학교", "lat": 37.5571, "lng": 126.9245}
    r = await client.get("/v1/places/search", params=params)
    sent = route.calls.last.request.url.params
    assert (sent["x"], sent["y"], sent["sort"], sent["size"]) == (
        "126.9245",
        "37.5571",
        "accuracy",  # 거리순은 관련 없는 근처 장소가 앞에 온다(2026-11-12 실제 비교)
        "15",
    )
    assert route.calls.last.request.headers["Authorization"] == "KakaoAK TESTKAKAOKEY"
    item = r.json()["items"][0]
    assert set(item) == {"name", "address", "lat", "lng", "category"}
    assert 37 < item["lat"] < 38 and 126 < item["lng"] < 128
    assert item["name"].startswith("홍익대학교")


async def test_search_without_coords_sends_no_xy(client, respx_mock):
    route = respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_hongik.json"))
    await client.get("/v1/places/search", params={"q": "홍익대학교"})
    assert "x" not in route.calls.last.request.url.params


async def test_search_cache_rounds_coords_to_2_decimals(client, respx_mock):
    route = respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_hongik.json"))
    for lat, lng in [(37.5571, 126.9245), (37.5609, 126.9201)]:
        await client.get("/v1/places/search", params={"q": "홍익대학교", "lat": lat, "lng": lng})
    assert route.call_count == 1


async def test_empty_result_is_empty_list(client, respx_mock):
    respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_empty.json"))
    r = await client.get("/v1/places/search", params={"q": "ㅁㄴㅇㄹㅁㄴㅇㄹ"})
    assert r.status_code == 200 and r.json() == {"items": []}


async def test_bad_params_are_400(client):
    for params in [{"q": "a", "lat": 37.5}, {"q": " "}, {"q": "a", "lat": 99, "lng": 127}, {}]:
        r = await client.get("/v1/places/search", params=params)
        assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST", params
    assert (await client.get("/v1/places/reverse", params={"lat": 37.5})).status_code == 400


async def test_reverse_address_and_nearest_station(client, respx_mock):
    respx_mock.get(COORD).mock(return_value=fixture_response("kakao_coord2address_hongdae.json"))
    r = await client.get("/v1/places/reverse", params={"lat": 37.5571, "lng": 126.9245})
    body = r.json()
    doc = load_fixture("kakao_coord2address_hongdae.json")[1]["documents"][0]
    assert body["address"] == (doc.get("road_address") or doc["address"])["address_name"]
    nearest = body["nearestStation"]
    assert nearest["id"] == "홍대입구" and nearest["distanceM"] < 300
    assert set(nearest) == {"id", "name", "distanceM"}


async def test_reverse_in_sea_has_nulls(client, respx_mock):
    respx_mock.get(COORD).mock(return_value=fixture_response("kakao_coord2address_sea.json"))
    r = await client.get("/v1/places/reverse", params={"lat": 37.0, "lng": 125.0})
    assert r.json() == {"address": None, "nearestStation": None}


async def test_reverse_cached_by_4_decimals(client, respx_mock):
    route = respx_mock.get(COORD).mock(
        return_value=fixture_response("kakao_coord2address_hongdae.json")
    )
    for lng in (126.92451, 126.92449):
        await client.get("/v1/places/reverse", params={"lat": 37.5571, "lng": lng})
    assert route.call_count == 1


async def test_kakao_401_is_upstream_error(client, respx_mock):
    respx_mock.get(KEYWORD).mock(
        return_value=httpx.Response(401, json={"errorType": "AccessDeniedError"})
    )
    r = await client.get("/v1/places/search", params={"q": "홍익대학교"})
    assert r.status_code == 502 and r.json()["error"]["code"] == "UPSTREAM_ERROR"
