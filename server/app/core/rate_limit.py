"""IP별 요청 제한: 메모리 슬라이딩 윈도우 (스펙 6.8)."""

import time
from collections import deque
from collections.abc import Callable

from starlette.requests import Request


class SlidingWindowRateLimiter:
    """키(IP)마다 최근 window_sec 동안의 요청 시각을 기억해 limit개까지만 허용한다."""

    def __init__(
        self, limit: int, window_sec: float = 60.0, clock: Callable[[], float] = time.monotonic
    ):
        self.limit = limit
        self.window_sec = window_sec
        self._clock = clock
        self._hits: dict[str, deque[float]] = {}
        self._last_sweep = clock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        self._sweep_idle_keys(now)
        hits = self._hits.setdefault(key, deque())
        while hits and now - hits[0] >= self.window_sec:
            hits.popleft()
        if len(hits) < self.limit:
            hits.append(now)
            return True
        if not hits:  # limit이 0이면 기록이 쌓이지 않으니 빈 키를 남기지 않는다
            del self._hits[key]
        return False

    def tracked_keys(self) -> int:
        return len(self._hits)

    def _sweep_idle_keys(self, now: float) -> None:
        """창 한 번마다, 마지막 요청이 창 밖인 키를 지운다.

        다시 오지 않는 IP의 기록이 메모리에 계속 쌓이지 않게 한다.
        """
        if now - self._last_sweep < self.window_sec:
            return
        self._last_sweep = now
        for key in [k for k, hits in self._hits.items() if self._is_idle(hits, now)]:
            del self._hits[key]

    def _is_idle(self, hits: deque[float], now: float) -> bool:
        return not hits or now - hits[-1] >= self.window_sec


def client_ip(request: Request) -> str:
    """호스팅의 프록시 뒤에 있으므로 X-Forwarded-For의 첫 번째 IP를 쓴다."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        first = forwarded.split(",")[0].strip()
        if first:
            return first
    if request.client is not None and request.client.host:
        return request.client.host
    return "unknown"
