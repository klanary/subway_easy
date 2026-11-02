import asyncio
from unittest.mock import AsyncMock

import pytest

from app.cache.ttl_cache import TtlCache
from app.core.errors import NoRoute, UpstreamError, UpstreamTimeout


@pytest.fixture
def cache(clock, metrics):
    return TtlCache(20, clock=clock, metrics=metrics)


def counting(value="v"):
    calls = []

    async def fetch():
        calls.append(1)
        return value

    return fetch, calls


async def test_hit_within_ttl(cache, clock):
    fetch, calls = counting()
    a = await cache.get_or_fetch("k", fetch)
    clock.advance(19.9)
    b = await cache.get_or_fetch("k", fetch)
    assert (a.status, b.status, len(calls)) == ("miss", "hit", 1)


async def test_refetch_after_ttl(cache, clock):
    fetch, calls = counting()
    await cache.get_or_fetch("k", fetch)
    clock.advance(20)
    assert (await cache.get_or_fetch("k", fetch)).status == "miss" and len(calls) == 2


async def test_multiplier_extends_ttl(clock, metrics):
    cache = TtlCache(20, clock=clock, metrics=metrics, ttl_multiplier=lambda: 3.0)
    fetch, calls = counting()
    await cache.get_or_fetch("k", fetch)
    clock.advance(59)
    assert (await cache.get_or_fetch("k", fetch)).status == "hit"
    clock.advance(1)
    assert (await cache.get_or_fetch("k", fetch)).status == "miss"


async def test_concurrent_calls_share_one_fetch(cache, metrics):
    gate, calls = asyncio.Event(), []

    async def slow():
        calls.append(1)
        await gate.wait()
        return "v"

    tasks = [asyncio.create_task(cache.get_or_fetch("k", slow)) for _ in range(10)]
    await asyncio.sleep(0)
    gate.set()
    results = await asyncio.gather(*tasks)
    assert len(calls) == 1 and {r.value for r in results} == {"v"}
    assert (metrics.cache_hits, metrics.cache_misses) == (9, 1)


async def test_cancelled_waiter_does_not_cancel_shared_fetch(cache):
    gate = asyncio.Event()

    async def slow():
        await gate.wait()
        return "v"

    first = asyncio.create_task(cache.get_or_fetch("k", slow))
    await asyncio.sleep(0)
    second = asyncio.create_task(cache.get_or_fetch("k", slow))
    await asyncio.sleep(0)
    first.cancel()
    gate.set()
    assert (await second).value == "v"


async def test_stale_on_upstream_failure_after_expiry(cache, clock):
    await cache.get_or_fetch("k", counting("old")[0])
    clock.advance(25)
    r = await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamError()))
    assert (r.value, r.status, r.stale) == ("old", "stale", True)


async def test_raises_without_entry(cache):
    with pytest.raises(UpstreamTimeout):
        await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamTimeout()))


async def test_stale_entry_dropped_after_keep_period(cache, clock):
    await cache.get_or_fetch("k", counting("old")[0])
    clock.advance(20 + 86_400 + 1)
    with pytest.raises(UpstreamError):
        await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamError()))


async def test_non_upstream_errors_are_not_masked(cache, clock):
    await cache.get_or_fetch("k", counting("old")[0])
    clock.advance(25)
    with pytest.raises(NoRoute):
        await cache.get_or_fetch("k", AsyncMock(side_effect=NoRoute("NO_RESULTS")))


async def test_failures_are_not_cached(cache):
    with pytest.raises(UpstreamError):
        await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamError()))
    assert (await cache.get_or_fetch("k", counting()[0])).status == "miss"
