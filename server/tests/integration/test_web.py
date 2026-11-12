async def test_home_page_is_served(client):
    r = await client.get("/")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/html")
    assert "지금출발" in r.text
    # 페이지가 부르는 API 경로가 서버에 있어야 한다.
    assert "/v1/stations/search" in r.text
    assert "/arrivals" in r.text


async def test_logo_is_served(client):
    r = await client.get("/logo.png")
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert r.content[:8] == b"\x89PNG\r\n\x1a\n"
