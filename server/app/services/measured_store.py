"""실측 결과물을 GitHub 릴리스에서 받아 메모리에 둔다(설계 2026-11-17 §4.2).

- `manifest.json`의 `built_at`이 바뀌었을 때만 두 자산을 받는다. VM은 manifest를 마지막에 올린다.
- sha256·gzip·형식을 검증하고, 하나라도 틀리거나 받기에 실패하면 지난 것을 그대로 쓴다.
- 요청 처리는 받기를 기다리지 않는다. `current()`가 6시간마다 백그라운드 받기를 하나 띄운다
  (무료 서버는 잠들므로 따로 도는 스케줄러를 두지 않는다).
- `as_of`가 14일보다 오래되면(VM이 멈춤) 쓰지 않는다.
"""

import asyncio
import gzip
import hashlib
import json
import logging
from collections.abc import Callable

import httpx

from app.core.clock import kst_from_epoch
from app.services.measured import MeasuredData
from app.services.trips import TripsData

logger = logging.getLogger("app.measured")

MANIFEST_FILE = "manifest.json"
# 데이터 파일은 빌드마다 이름이 다르다(예: measured_timetable-20261118T033000.json.gz).
# manifest가 지금 쓸 이름과 sha256을 알려 준다(최종 리뷰 #5: 올리다 실패해도 옛 파일이 남는다).
B1_PREFIX = "measured_timetable-"
B2_PREFIX = "segment_times-"
TRIPS_PREFIX = "trips-"  # 열차 운행표(경로 설계 2026-11-18). 매일 바뀐다
REFRESH_SEC = 21_600
RETRY_SEC = 120  # 받기가 실패하면 이만큼 뒤에 다시(경로가 운행표에 기대므로 6시간을 기다리지 않음)
STALE_DAYS = 14
TIMEOUT_SEC = 30.0
TRIPS_WARN_DAYS = 3


class MeasuredStore:
    def __init__(self, http: httpx.AsyncClient, base_url: str, clock: Callable[[], float]):
        self._http = http
        self._base = base_url.rstrip("/")
        self._clock = clock
        self._data: MeasuredData | None = None
        self._built_at: str | None = None
        self._b12_names: tuple[str, str] | None = None
        self._trips: TripsData | None = None
        self._trips_name: str | None = None
        self._checked_at: float | None = None
        self.refreshing: asyncio.Task[bool] | None = None

    def current(self) -> MeasuredData | None:
        """지금 가진 결과물. 새로 받을 때가 됐으면 백그라운드 받기를 띄우고 기다리지 않는다."""
        self._maybe_start_refresh()
        data = self._data
        if data is None:
            return None
        today = kst_from_epoch(self._clock()).date()
        if (today - data.as_of).days > STALE_DAYS:
            return None
        return data

    def _maybe_start_refresh(self) -> None:
        if not self._base:
            return
        if self.refreshing is not None and not self.refreshing.done():
            return
        now = self._clock()
        if self._checked_at is not None and now - self._checked_at < REFRESH_SEC:
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        self._checked_at = now
        self.refreshing = loop.create_task(self.refresh())

    async def refresh(self) -> bool:
        """새 결과물이 있으면 받아 바꾼다. 바꿨거나 이미 최신이면 True, 하나라도 실패면 False.

        B1·B2는 수·일에만, 운행표는 매일 바뀐다. 이름이 그대로인 파일은 다시 받지 않는다.
        실패한 부분은 지난 것을 그대로 둔다.
        """
        if not self._base:
            return False
        try:
            manifest = (await self._get(MANIFEST_FILE)).json()
            built_at = manifest["built_at"]
        except Exception as e:
            logger.warning("실측 결과물 받기 실패: %s", type(e).__name__)
            self._retry_soon()
            return False
        if built_at == self._built_at:
            return True
        ok = True
        try:
            names = (manifest["b1"]["name"], manifest["b2"]["name"])
            if names != self._b12_names:
                b1 = await self._get_checked(names[0], manifest["b1"]["sha256"])
                b2 = await self._get_checked(names[1], manifest["b2"]["sha256"])
                # 압축 풀기·색인 만들기는 다른 스레드에서(요청 처리를 멈추지 않게, 최종 리뷰 #4)
                self.replace(await asyncio.to_thread(_build, b1, b2))
                self._b12_names = names
                logger.info("실측 결과물 바꿈: %s", names[0])
        except Exception as e:  # 받기·검증 실패는 지난 것을 두고 넘어간다
            logger.warning("실측 결과물 받기 실패: %s", type(e).__name__)
            ok = False
        entry = manifest.get("trips")
        try:
            if entry and entry["name"] != self._trips_name:
                body = await self._get_checked(entry["name"], entry["sha256"])
                self.replace_trips(await asyncio.to_thread(_build_trips, body))
                self._trips_name = entry["name"]
                logger.info("운행표 바꿈: %s", entry["name"])
        except Exception as e:
            logger.warning("운행표 받기 실패: %s", type(e).__name__)
            ok = False
        if ok:
            self._built_at = built_at
        else:
            self._retry_soon()
        return ok

    def _retry_soon(self) -> None:
        """다음 확인을 6시간 뒤가 아니라 RETRY_SEC 뒤로 당긴다."""
        self._checked_at = self._clock() - REFRESH_SEC + RETRY_SEC

    def replace(self, data: MeasuredData) -> None:
        """실측 결과물을 바꾼다(받기가 쓰고, 테스트가 픽스처를 넣을 때도 쓴다)."""
        self._data = data

    def replace_trips(self, trips: TripsData) -> None:
        """운행표를 바꾼다. 만든 날이 3일 넘게 지났으면 경고만 남긴다(쓰기는 계속)."""
        today = kst_from_epoch(self._clock()).date()
        if (today - trips.built_on).days > TRIPS_WARN_DAYS:
            logger.warning("운행표가 오래됐습니다: built_on=%s", trips.built_on)
        self._trips = trips

    def current_trips(self) -> TripsData | None:
        """지금 가진 운행표. `current()`처럼 새로 받을 때가 됐으면 백그라운드 받기를 띄운다."""
        self._maybe_start_refresh()
        return self._trips

    async def _get(self, name: str) -> httpx.Response:
        response = await self._http.get(
            f"{self._base}/{name}", follow_redirects=True, timeout=TIMEOUT_SEC
        )
        response.raise_for_status()
        return response

    async def _get_checked(self, name: str, sha256: str) -> bytes:
        body = (await self._get(name)).content
        if hashlib.sha256(body).hexdigest() != sha256:
            raise ValueError(f"{name} sha256이 manifest와 다릅니다")
        return body


def _build_trips(body: bytes) -> TripsData:
    return TripsData.from_artifact(json.loads(gzip.decompress(body).decode("utf-8")))


def _build(b1: bytes, b2: bytes) -> MeasuredData:
    def load(body: bytes) -> dict:
        return json.loads(gzip.decompress(body).decode("utf-8"))

    return MeasuredData.from_artifacts(load(b1), load(b2))
