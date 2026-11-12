"""현재 위치 → 가장 가까운 역(재설계 스펙 6). 외부 호출 없이 역 데이터만 쓴다."""

NEAREST = "/v1/stations/nearest"


async def test_nearest_station(client):
    body = (await client.get(NEAREST, params={"lat": 37.5665, "lng": 126.9780})).json()
    assert body["station"]["id"] == "시청" and body["station"]["distanceM"] < 400
    assert set(body["station"]) == {"id", "name", "distanceM"}


async def test_nearest_none_far_away(client):
    assert (await client.get(NEAREST, params={"lat": 37.0, "lng": 125.0})).json() == {
        "station": None
    }


async def test_nearest_requires_both_coords(client):
    assert (await client.get(NEAREST, params={"lat": 37.5})).status_code == 400


async def test_nearest_rejects_out_of_range(client):
    assert (await client.get(NEAREST, params={"lat": 91, "lng": 0})).status_code == 400


async def test_places_api_removed(client):
    assert (await client.get("/v1/places/search", params={"q": "a"})).status_code == 404
    reverse = await client.get("/v1/places/reverse", params={"lat": 37.5, "lng": 127})
    assert reverse.status_code == 404
