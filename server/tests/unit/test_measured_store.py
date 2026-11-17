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
):
    b1 = b1 or server_b1({**fixture("b1_small.json"), "as_of": as_of})
    files = {B1_NAME: pack(b1), B2_NAME: pack(server_b2(fixture("b2_small.json")))}
    manifest = {
        "built_at": built_at,
        "as_of": as_of,
        "b1": {"name": B1_NAME, "sha256": b1_sha or hashlib.sha256(files[B1_NAME]).hexdigest()},
        "b2": {"name": B2_NAME, "sha256": hashlib.sha256(files[B2_NAME]).hexdigest()},
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
    serve(respx, *release(built_at="2026-11-22T03:30:00+09:00", b1_sha="0" * 64))
    assert await store.refresh() is False
    assert store.current() is first


@respx.mock
async def test_wrong_version_keeps_previous(store):
    serve(respx, *release())
    await store.refresh()
    first = store.current()
    respx.reset()
    bad = {**server_b1(fixture("b1_small.json")), "version": 99}
    serve(respx, *release(built_at="2026-11-22T03:30:00+09:00", b1=bad))
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
