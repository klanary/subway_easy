"""실측 결과물 받기(설계 2026-11-17 §4.2): 바뀌었을 때만 받고, 틀리면 지난 것을 둔다."""

import asyncio
import gzip
import hashlib
import json
from datetime import datetime

import httpx
import pytest
import respx

from app.core.clock import KST
from app.services.measured_store import MANIFEST_FILE, REFRESH_SEC, MeasuredStore
from ml.publish import server_b1, server_b2
from tests.helpers import FIXTURES_DIR, FakeClock

BASE = "https://example.test/releases/download/measured-timetable"


def fixture(name: str) -> dict:
    return json.loads((FIXTURES_DIR / "measured" / name).read_text(encoding="utf-8"))


def pack(data: dict) -> bytes:
    return gzip.compress(json.dumps(data, ensure_ascii=False).encode("utf-8"))


B1_NAME = "measured_timetable-20261118T033000.json.gz"
B2_NAME = "segment_times-20261118T033000.json.gz"


def release(
    built_at: str = "2026-11-18T03:30:00+09:00",
    as_of: str = "2026-11-17",
    b1_sha: str | None = None,
    b1: dict | None = None,
    stamp: str = "20261118T033000",
):
    b1_name, b2_name = f"measured_timetable-{stamp}.json.gz", f"segment_times-{stamp}.json.gz"
    b1 = b1 or server_b1({**fixture("b1_small.json"), "as_of": as_of})
    files = {b1_name: pack(b1), b2_name: pack(server_b2(fixture("b2_small.json")))}
    manifest = {
        "built_at": built_at,
        "as_of": as_of,
        "b1": {"name": b1_name, "sha256": b1_sha or hashlib.sha256(files[b1_name]).hexdigest()},
        "b2": {"name": b2_name, "sha256": hashlib.sha256(files[b2_name]).hexdigest()},
    }
    return manifest, files


def serve(mock, manifest, files):
    routes = {MANIFEST_FILE: mock.get(f"{BASE}/{MANIFEST_FILE}").mock(
        return_value=httpx.Response(200, json=manifest)
    )}  # fmt: skip
    for name, body in files.items():
        routes[name] = mock.get(f"{BASE}/{name}").mock(
            return_value=httpx.Response(200, content=body)
        )
    return routes


@pytest.fixture
def clock():
    return FakeClock(datetime(2026, 11, 18, 9, 0, tzinfo=KST).timestamp())


@pytest.fixture
async def store(clock):
    async with httpx.AsyncClient() as http:
        yield MeasuredStore(http, BASE, clock)


@respx.mock
async def test_refresh_loads_manifest_then_assets(store):
    serve(respx, *release())
    assert await store.refresh() is True
    data = store.current()
    assert data is not None and data.built_at == "2026-11-18T02:41:00+09:00"


@respx.mock
async def test_same_built_at_does_not_download_again(store):
    routes = serve(respx, *release())
    await store.refresh()
    await store.refresh()
    assert routes[MANIFEST_FILE].call_count == 2 and routes[B1_NAME].call_count == 1


@respx.mock
async def test_bad_checksum_keeps_previous(store):
    serve(respx, *release())
    await store.refresh()
    first = store.current()
    respx.reset()
    serve(
        respx,
        *release(built_at="2026-11-22T03:30:00+09:00", b1_sha="0" * 64, stamp="20261122T033000"),
    )
    assert await store.refresh() is False
    assert store.current() is first


@respx.mock
async def test_wrong_version_keeps_previous(store):
    serve(respx, *release())
    await store.refresh()
    first = store.current()
    respx.reset()
    bad = {**server_b1(fixture("b1_small.json")), "version": 99}
    serve(respx, *release(built_at="2026-11-22T03:30:00+09:00", b1=bad, stamp="20261122T033000"))
    assert await store.refresh() is False and store.current() is first


@respx.mock
async def test_download_error_keeps_previous(store):
    serve(respx, *release())
    await store.refresh()
    first = store.current()
    respx.reset()
    respx.get(f"{BASE}/{MANIFEST_FILE}").mock(return_value=httpx.Response(500))
    assert await store.refresh() is False and store.current() is first


@respx.mock
async def test_stale_as_of_is_not_used(store):
    serve(respx, *release(as_of="2026-11-03"))  # 오늘(11-18)보다 15일 전
    assert await store.refresh() is True
    assert store.current() is None


@respx.mock
async def test_current_starts_one_background_refresh_after_6h(store, clock):
    routes = serve(respx, *release())
    assert store.current() is None  # 기다리지 않는다
    first = store.refreshing
    assert first is not None and store.current() is None and store.refreshing is first
    await first
    assert store.current() is not None and routes[MANIFEST_FILE].call_count == 1
    clock.advance(REFRESH_SEC - 1)
    store.current()
    assert routes[MANIFEST_FILE].call_count == 1
    clock.advance(2)
    store.current()
    await store.refreshing
    assert routes[MANIFEST_FILE].call_count == 2


async def test_empty_base_url_disables(clock):
    async with httpx.AsyncClient() as http:
        disabled = MeasuredStore(http, "", clock)
        assert disabled.current() is None and disabled.refreshing is None
        assert await disabled.refresh() is False
    await asyncio.sleep(0)


@respx.mock
async def test_app_startup_starts_download_and_shutdown_cancels(test_settings, clock):
    # 잠든 서버가 깨어날 때(/health) 바로 받기 시작한다(최종 리뷰 #3).
    from app.main import create_app

    respx.get(f"{BASE}/{MANIFEST_FILE}").mock(side_effect=lambda request: asyncio.sleep(10))
    app = create_app(test_settings.model_copy(update={"measured_base_url": BASE}), clock=clock)
    store = app.state.services.measured
    async with app.router.lifespan_context(app):
        assert store.refreshing is not None and not store.refreshing.done()
    assert store.refreshing.done()


@respx.mock
async def test_decode_and_build_run_off_the_event_loop(store, monkeypatch):
    # 큰 결과물의 압축 풀기·색인 만들기가 요청 처리를 멈추지 않게 다른 스레드에서 한다(리뷰 #4).
    import threading

    from app.services import measured_store

    seen: list[int] = []
    original = measured_store.MeasuredData.from_artifacts

    def spy(b1, b2):
        seen.append(threading.get_ident())
        return original(b1, b2)

    monkeypatch.setattr(measured_store.MeasuredData, "from_artifacts", staticmethod(spy))
    serve(respx, *release())
    assert await store.refresh() is True
    assert seen and seen[0] != threading.get_ident()


TRIPS_ART = {
    "kind": "trips-server", "version": 1, "built_on": "2026-11-18", "stations": ["마포", "공덕"],
    "day_types": {"1": [{"line": "5호선", "direction": "하행", "stops": [0, 1],
                         "trips": [["5201", 0, "마천", 0, 0, [100, 130, 220, 250]]]}]},
}  # fmt: skip


def with_trips(manifest, files, *, name="trips-20261118T033000.json.gz", body=None, sha=None):
    body = body or pack(TRIPS_ART)
    files = {**files, name: body}
    manifest = {
        **manifest,
        "trips": {"name": name, "sha256": sha or hashlib.sha256(body).hexdigest(),
                  "built_on": "2026-11-18"},
    }  # fmt: skip
    return manifest, files


@respx.mock
async def test_store_loads_trips_from_manifest(store):
    serve(respx, *with_trips(*release()))
    assert await store.refresh() is True
    trips = store.current_trips()
    assert trips is not None and str(trips.built_on) == "2026-11-18"
    assert store.current() is not None


@respx.mock
async def test_trips_bad_checksum_keeps_previous(store):
    serve(respx, *with_trips(*release()))
    await store.refresh()
    first = store.current_trips()
    respx.reset()
    manifest, files = with_trips(
        *release(built_at="2026-11-19T03:30:00+09:00"),
        name="trips-20261119T033000.json.gz",
        sha="0" * 64,
    )
    serve(respx, manifest, files)
    assert await store.refresh() is False
    assert store.current_trips() is first


@respx.mock
async def test_unchanged_b1_b2_are_not_downloaded_again(store):
    routes = serve(respx, *with_trips(*release()))
    await store.refresh()
    respx.reset()
    manifest, files = with_trips(
        *release(built_at="2026-11-19T03:30:00+09:00"), name="trips-20261119T033000.json.gz"
    )
    routes = serve(respx, manifest, files)
    assert await store.refresh() is True
    assert (
        routes[B1_NAME].call_count == 0 and routes["trips-20261119T033000.json.gz"].call_count == 1
    )


@respx.mock
async def test_failed_refresh_retries_soon_not_after_6h(store, clock):
    # 첫 받기가 실패하면 6시간이 아니라 곧 다시 시도한다(최종 리뷰 #3: 경로가 6시간 503).
    from app.services.measured_store import RETRY_SEC

    route = respx.get(f"{BASE}/{MANIFEST_FILE}").mock(return_value=httpx.Response(500))
    store.current_trips()
    await store.refreshing
    clock.advance(RETRY_SEC - 1)
    store.current_trips()
    assert route.call_count == 1
    clock.advance(2)
    store.current_trips()
    await store.refreshing
    assert route.call_count == 2
