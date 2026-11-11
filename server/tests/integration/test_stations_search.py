import pytest


async def test_search_endpoint_chosung(client):
    r = await client.get("/v1/stations/search", params={"q": "ㅎㄷㅇㄱ"})
    item = r.json()["items"][0]
    assert r.status_code == 200 and item["id"] == "홍대입구" and "2호선" in item["lines"]
    assert set(item) == {"id", "name", "lines", "lat", "lng"}


@pytest.mark.parametrize(
    "params", [{"q": ""}, {"q": "   "}, {"q": "홍대", "limit": 0}, {"q": "홍대", "limit": 51}, {}]
)
async def test_search_rejects_bad_params(client, params):
    r = await client.get("/v1/stations/search", params=params)
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"


async def test_search_default_limit_is_20(client):
    r = await client.get("/v1/stations/search", params={"q": "ㅅ"})
    assert len(r.json()["items"]) == 20


async def test_search_uses_the_configured_db(sample_client):
    r = await sample_client.get("/v1/stations/search", params={"q": "서울"})
    assert [i["id"] for i in r.json()["items"]] == ["서울", "서울대입구", "서울숲", "강변"]
