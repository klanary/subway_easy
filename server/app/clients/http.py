"""모든 외부 API 호출이 거치는 공통 처리 (스펙 6.3·6.4·6.8).

시간 제한, 1회 재시도, 호출 한도, 키와 좌표를 가린 로그.
"""

import asyncio
import logging
import re
import time

import httpx

from app.cache.quota_guard import QuotaGuard
from app.core.errors import QuotaExceeded, UpstreamError, UpstreamFailure, UpstreamTimeout

logger = logging.getLogger("app.upstream")

_SEOUL_KEY_IN_PATH = re.compile(r"(/api/subway/)[^/]+(/)")
_COORD_PARAM = re.compile(r"([?&](?:x|y|start_x|start_y|end_x|end_y|lat|lng)=)(-?\d+(?:\.\d+)?)")


def mask_url(url: str) -> str:
    """서울시 키(URL 경로)를 ***로 가리고, 좌표 파라미터는 소수점 3자리로 줄인다(스펙 5.4)."""
    url = _SEOUL_KEY_IN_PATH.sub(r"\1***\2", url)
    return _COORD_PARAM.sub(lambda m: f"{m.group(1)}{float(m.group(2)):.3f}", url)


async def call_upstream(
    http: httpx.AsyncClient,
    guard: QuotaGuard,
    request: httpx.Request,
    *,
    timeout_sec: float,
    retry_delay_sec: float,
) -> httpx.Response:
    """2xx와 (429를 뺀) 4xx는 응답을 그대로 돌려준다. 4xx의 해석은 각 클라이언트가 한다.

    그 밖의 실패는 1회 재시도 뒤 예외(QuotaExceeded·UpstreamError·UpstreamTimeout)로 바꾼다.
    """
    failure: UpstreamFailure = UpstreamError(reason="NETWORK")
    for attempt in (1, 2):
        if not guard.can_call():
            raise QuotaExceeded()
        guard.record_call()
        started = time.perf_counter()
        status: int | None = None
        try:
            async with asyncio.timeout(timeout_sec):
                response = await http.send(request)
            status = response.status_code
        except (TimeoutError, httpx.TimeoutException):
            guard.record_error()
            failure = UpstreamTimeout()
        except httpx.HTTPError:
            guard.record_error()
            failure = UpstreamError(reason="NETWORK")
        else:
            if 200 <= status < 300:
                return response
            if status == 429:
                guard.mark_exhausted()
                raise QuotaExceeded()
            guard.record_error()
            if status < 500:
                return response
            failure = UpstreamError(reason=f"HTTP_{status}")
        finally:
            logger.info(
                "upstream",
                extra={
                    "api": guard.name,
                    "url": mask_url(str(request.url)),
                    "status": status,
                    "attempt": attempt,
                    "duration_ms": round((time.perf_counter() - started) * 1000),
                },
            )
        if attempt == 1:
            await asyncio.sleep(retry_delay_sec)
    raise failure
