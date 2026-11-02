"""유효 시간(TTL) 메모리 캐시 (스펙 6.4).

- 유효 시간 안이면 저장된 값을 돌려준다(hit).
- 같은 키의 외부 호출이 진행 중이면 새로 부르지 않고 그 결과를 함께 기다린다(동시 요청 합치기).
- 외부 호출이 실패하면, 만료됐어도 보관 기간 안의 값이 있으면 그 값을 stale로 돌려준다.
"""

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Literal

from app.core.errors import UpstreamFailure
from app.core.metrics import Metrics


@dataclass(frozen=True)
class CacheResult[V]:
    value: V
    status: Literal["hit", "miss", "stale"]
    fetched_at: float

    @property
    def stale(self) -> bool:
        return self.status == "stale"


@dataclass(frozen=True)
class _Entry[V]:
    value: V
    fetched_at: float


class TtlCache[V]:
    def __init__(
        self,
        ttl_sec: float,
        *,
        clock: Callable[[], float],
        metrics: Metrics,
        ttl_multiplier: Callable[[], float] = lambda: 1.0,
        stale_keep_sec: float = 86_400,
    ):
        self._ttl_sec = ttl_sec
        self._clock = clock
        self._metrics = metrics
        self._ttl_multiplier = ttl_multiplier
        self._stale_keep_sec = stale_keep_sec
        self._entries: dict[str, _Entry[V]] = {}
        self._inflight: dict[str, asyncio.Task[V]] = {}

    def _fresh_sec(self) -> float:
        # 호출 한도의 80%를 넘으면 배율이 3이 된다(QuotaGuard).
        return self._ttl_sec * self._ttl_multiplier()

    async def get_or_fetch(self, key: str, fetch: Callable[[], Awaitable[V]]) -> CacheResult[V]:
        entry = self._entries.get(key)
        if entry is not None and self._clock() - entry.fetched_at < self._fresh_sec():
            self._metrics.cache_hits += 1
            return CacheResult(entry.value, "hit", entry.fetched_at)

        task = self._inflight.get(key)
        joined = task is not None
        if task is None:
            task = asyncio.create_task(fetch())
            self._inflight[key] = task
            task.add_done_callback(lambda t: self._on_fetch_done(key, t))

        try:
            # shield: 기다리던 요청 하나가 취소돼도 함께 기다리는 요청을 위해 호출은 계속된다.
            value = await asyncio.shield(task)
        except UpstreamFailure:
            self._metrics.cache_misses += 1
            entry = self._entries.get(key)
            age_limit = self._fresh_sec() + self._stale_keep_sec
            if entry is not None and self._clock() - entry.fetched_at < age_limit:
                return CacheResult(entry.value, "stale", entry.fetched_at)
            raise
        except Exception:
            self._metrics.cache_misses += 1
            raise

        stored = self._entries.get(key)
        fetched_at = stored.fetched_at if stored is not None else self._clock()
        if joined:
            self._metrics.cache_hits += 1
            return CacheResult(value, "hit", fetched_at)
        self._metrics.cache_misses += 1
        return CacheResult(value, "miss", fetched_at)

    def _on_fetch_done(self, key: str, task: asyncio.Task[V]) -> None:
        """호출이 끝나면 진행 중 표에서 빼고, 성공한 값만 저장한다.

        완료 콜백은 shield로 기다리던 요청들이 깨어나기 전에 실행되므로,
        깨어난 요청은 저장된 항목을 본다.
        """
        if self._inflight.get(key) is task:
            del self._inflight[key]
        if task.cancelled() or task.exception() is not None:
            return
        now = self._clock()
        self._drop_expired(now)
        self._entries[key] = _Entry(task.result(), now)

    def _drop_expired(self, now: float) -> None:
        age_limit = self._fresh_sec() + self._stale_keep_sec
        for key in [k for k, e in self._entries.items() if now - e.fetched_at >= age_limit]:
            del self._entries[key]
