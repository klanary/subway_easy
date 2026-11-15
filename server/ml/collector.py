"""서울시 실시간 열차 위치 수집기(설계 §2).

사용법 (server/에서):
    python -m ml.collector                    # 05:00~01:00 KST 동안 30초마다 계속
    python -m ml.collector --once             # 한 바퀴만
    python -m ml.collector --max-minutes 75   # 75분 뒤 끝

- 키는 위치 → 예비 1 → 예비 2 순서로만 쓴다(ml.keys). 서버의 실시간 키는 읽지 않는다.
- ERROR-337(일일 한도)이면 그 키는 그날 쉬고 다음 키로 같은 호선을 다시 부른다.
- 키 값·URL은 DB와 로그에 남기지 않는다. 로그의 URL은 mask_url을 거친다.
"""

import argparse
import logging
import os
import signal
import sys
import time
from collections import Counter
from collections.abc import Callable
from datetime import date, datetime, timedelta
from logging.handlers import RotatingFileHandler
from urllib.parse import quote

import httpx

from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.clients.http import mask_url
from app.core.clock import KST
from app.services.station_index import StationIndex
from ml.keys import KEY_ENV, KeyRing, load_keys
from ml.parse import (
    CODE_NO_DATA,
    CODE_OK,
    CODE_QUOTA_EXCEEDED,
    parse_rows,
    result_code,
    service_date,
)
from ml.store import Store

logger = logging.getLogger("ml.collector")

# 위치 API의 호선명. 2026-11-15에 19개 모두 열차 목록을 돌려주는 것을 확인했다.
# 역 DB(stations.sqlite)의 line_name과 같은 문자열이다.
LINES: tuple[str, ...] = (
    "1호선", "2호선", "3호선", "4호선", "5호선", "6호선", "7호선", "8호선", "9호선",
    "경의중앙선", "공항철도", "경춘선", "수인분당선", "신분당선", "우이신설선",
    "서해선", "경강선", "신림선", "GTX-A",
)  # fmt: skip
PERIOD_SEC = 30
SERVICE_START_HOUR = 5  # 05:00부터
SERVICE_END_HOUR = 1  # 다음 날 01:00 전까지
PAGE_SIZE = 200  # 한 호선의 열차는 100대를 넘지 않는다(1호선 80대)
TIMEOUT_SEC = 5.0
IDLE_NAP_SEC = 30  # 쉬는 동안에도 이 간격으로 깨어 멈춤 신호를 본다
SUMMARY_EVERY_CYCLES = 20  # 10분마다 요약 로그


def in_service_hours(now: datetime) -> bool:
    hour = now.astimezone(KST).hour
    return hour >= SERVICE_START_HOUR or hour < SERVICE_END_HOUR


def _position_url(key: str, line: str) -> str:
    return f"{SEOUL_REALTIME_BASE}/{key}/json/realtimePosition/0/{PAGE_SIZE}/{quote(line)}"


def remap_stations(store: Store, index: StationIndex) -> int:
    """잇지 못했던 역을 다시 찾아 잇는다(별칭을 더한 뒤). 이은 역의 수."""
    done = 0
    for line, statn_id, statn_nm in store.unmapped_stations():
        station = index.resolve(statn_nm, line)
        if station is not None:
            store.set_station(line, statn_id, station.id)
            done += 1
    store.commit()
    return done


class Collector:
    def __init__(
        self,
        http: httpx.Client,
        store: Store,
        keys: KeyRing,
        index: StationIndex,
        clock: Callable[[], float] = time.time,
        lines: tuple[str, ...] = LINES,
    ):
        self._http = http
        self._store = store
        self._keys = keys
        self._index = index
        self._clock = clock
        self._lines = lines
        self._stations: dict[str, dict[str, str | None]] = {}
        self._stats: Counter[str] = Counter()
        # 다시 띄워도 오늘 한도에 걸린 키는 다시 쓰지 않는다.
        today = self._now().date()
        for name in store.blocked_keys(today):
            if name in keys.names:
                keys.block(name, today)
        reconnected = remap_stations(store, index)
        if reconnected:
            logger.info("전에 잇지 못한 역 %d개를 이었다", reconnected)

    def _now(self) -> datetime:
        return datetime.fromtimestamp(self._clock(), KST)

    def poll_line(self, line: str) -> str:
        """한 호선을 한 번 불러 저장하고 결과 코드를 돌려준다. 예외를 밖으로 내지 않는다."""
        try:
            return self._poll_line(line)
        except Exception as e:
            # 한 호선의 실패(DB 잠김 등)가 수집 전체를 멈추지 않게 한다. 이 호선의 쓰기는 버린다.
            # 예외 메시지는 찍지 않는다(URL이 들어 있을 수 있다).
            self._store.rollback()
            self._stats["errors"] += 1
            logger.error("예상하지 못한 오류(%s): %s", line, type(e).__name__)
            return "INTERNAL"
        finally:
            self._store.commit()

    def _poll_line(self, line: str) -> str:
        while True:
            now = self._now()
            today = now.date()
            key = self._keys.current(today)
            if key is None:
                return "NO_KEY"
            name, value = key
            outcome, body_text, body = self._fetch(name, value, line)
            if outcome == CODE_QUOTA_EXCEEDED:
                self._keys.block(name, today)
                self._store.record_call(today, name, error=True)
                self._store.mark_quota_hit(today, name, now)
                logger.warning("일일 한도 초과(ERROR-337): 키 %s는 오늘 쉰다", name)
                continue  # 같은 호선을 다음 키로
            ok = outcome in (CODE_OK, CODE_NO_DATA)
            self._store.record_call(today, name, error=not ok)
            self._stats["calls"] += 1
            if not ok:
                self._stats["errors"] += 1
            if body is None:
                return outcome
            received_at = self._now()
            self._store.add_snapshot(line, received_at, outcome, body_text)
            if outcome == CODE_OK:
                self._save_events(body, line, received_at)
            elif not ok:
                logger.warning("위치 API 오류 코드 %s (%s)", outcome, line)
            return outcome

    def _fetch(self, key_name: str, key: str, line: str) -> tuple[str, str, dict | None]:
        """(결과 코드, 본문 글자, 본문). 본문을 못 읽으면 본문은 None."""
        url = _position_url(key, line)
        try:
            response = self._http.get(url, timeout=TIMEOUT_SEC)
        except httpx.HTTPError as e:
            # 예외 메시지에는 URL(키)이 들어갈 수 있다. 종류 이름과 가린 URL만 남긴다.
            logger.warning(
                "위치 API 호출 실패: %s key=%s url=%s", type(e).__name__, key_name, mask_url(url)
            )
            return "NETWORK", "", None
        if response.status_code >= 500:
            logger.warning("위치 API HTTP %s (%s)", response.status_code, line)
            return f"HTTP_{response.status_code}", "", None
        try:
            body = response.json()
        except ValueError:
            body = None
        code = result_code(body)
        if not isinstance(body, dict) or code is None:
            logger.warning("위치 API 본문을 읽을 수 없음 (%s, HTTP %s)", line, response.status_code)
            return "BAD_BODY", "", None
        # 서울시 응답은 키를 되돌려 주지 않지만, 혹시 들어 있어도 저장하지 않게 가린다.
        return code, response.text.replace(key, "***"), body

    def _save_events(self, body: dict, line: str, received_at: datetime) -> None:
        events, skipped = parse_rows(body, line, received_at)
        known = self._stations.get(line)
        if known is None:
            known = self._stations[line] = self._store.known_stations(line)
        for event in events:
            if event.statn_id in known:
                continue
            station = self._index.resolve(event.statn_nm, line)
            station_id = station.id if station is not None else None
            self._store.map_station(
                line, event.statn_id, event.statn_nm, station_id, received_at.date()
            )
            known[event.statn_id] = station_id
            if station_id is None:
                logger.warning("역을 잇지 못함: %s %s(%s)", line, event.statn_nm, event.statn_id)
        added = self._store.upsert_events(events, known, received_at)
        self._stats["rows"] += len(events)
        self._stats["new_events"] += added
        self._stats["skipped_rows"] += skipped

    def _log_unmapped(self) -> None:
        unmapped = self._store.unmapped_stations()
        if unmapped:
            names = ", ".join(f"{line} {name}({statn_id})" for line, statn_id, name in unmapped)
            logger.warning("잇지 못한 역 %d개(별칭 추가 필요): %s", len(unmapped), names)

    def _log_summary(self) -> None:
        logger.info(
            "요약: 호출 %d, 오류 %d, 받은 행 %d, 새 이벤트 %d, 건너뛴 행 %d",
            self._stats["calls"], self._stats["errors"], self._stats["rows"],
            self._stats["new_events"], self._stats["skipped_rows"],
        )  # fmt: skip
        self._stats.clear()

    def run(
        self,
        *,
        period: float = PERIOD_SEC,
        sleep: Callable[[float], None] = time.sleep,
        should_stop: Callable[[], bool] = lambda: False,
        max_cycles: int | None = None,
        any_hour: bool = False,
    ) -> None:
        """운행 시간 동안 `period`초마다 모든 호선을 한 번씩, 주기 안에 고르게 흩어 부른다."""
        slot = period / len(self._lines)
        cycles = 0
        idle_reason: str | None = None
        last_service_date: date | None = None
        while not should_stop() and (max_cycles is None or cycles < max_cycles):
            now = self._now()
            if not any_hour and not in_service_hours(now):
                reason, resume = (
                    "운행 시간 밖(01:00~05:00)",
                    now.replace(hour=SERVICE_START_HOUR, minute=0, second=0, microsecond=0),
                )
            elif self._keys.current(now.date()) is None:
                midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
                reason, resume = "쓸 수 있는 키가 없음(자정까지 쉼)", midnight + timedelta(days=1)
            else:
                reason, resume = None, now
            if reason is not None:
                if reason != idle_reason:
                    logger.info("쉬는 중: %s", reason)
                    idle_reason = reason
                sleep(max(0.001, min(IDLE_NAP_SEC, (resume - now).total_seconds())))
                continue
            idle_reason = None
            today = service_date(now)
            if last_service_date is not None and today != last_service_date:
                self._log_unmapped()
            last_service_date = today

            started = self._clock()
            for i, line in enumerate(self._lines):
                wait = started + i * slot - self._clock()
                if wait > 0:
                    sleep(wait)
                if should_stop() or self.poll_line(line) == "NO_KEY":
                    break
            cycles += 1
            if cycles % SUMMARY_EVERY_CYCLES == 0:
                self._log_summary()
            wait = started + period - self._clock()
            if wait > 0 and not should_stop():
                sleep(wait)
        self._log_summary()
        self._log_unmapped()


def _setup_logging(log_file: str) -> None:
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    root = logging.getLogger("ml")
    root.setLevel(logging.INFO)
    root.handlers.clear()
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stderr)]
    if log_file:
        os.makedirs(os.path.dirname(log_file) or ".", exist_ok=True)
        handlers.append(
            RotatingFileHandler(log_file, maxBytes=5_000_000, backupCount=5, encoding="utf-8")
        )
    for handler in handlers:
        handler.setFormatter(fmt)
        root.addHandler(handler)
    # httpx는 요청 URL(키 포함)을 INFO로 찍는다. 수집기에서는 끈다.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="서울시 실시간 열차 위치 수집기")
    parser.add_argument("--db", default="ml_data/collector.sqlite", help="수집 DB 경로")
    parser.add_argument("--env-file", default=".env", help="키를 읽을 .env")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    parser.add_argument("--lines", default="", help="쉼표로 나눈 호선(기본: 전부)")
    parser.add_argument("--exclude", default="", help="뺄 호선(쉼표)")
    parser.add_argument("--period", type=float, default=PERIOD_SEC, help="주기(초)")
    parser.add_argument("--once", action="store_true", help="한 바퀴만 돌고 끝")
    parser.add_argument(
        "--any-hour", action="store_true", help="운행 시간(05:00~01:00) 밖에도 부른다(설치 확인용)"
    )
    parser.add_argument("--max-minutes", type=float, default=None, help="이 시간 뒤 끝")
    parser.add_argument("--log-file", default="ml_data/collector.log", help="빈 값이면 화면에만")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    _setup_logging(args.log_file)
    keys = load_keys(args.env_file, os.environ)
    if not keys:
        names = ", ".join(KEY_ENV.values())
        print(
            f"수집기 키가 없습니다. {args.env_file}에 {names} 중 하나를 넣으세요.", file=sys.stderr
        )
        return 2
    lines = tuple(x for x in args.lines.split(",") if x) or LINES
    excluded = {x for x in args.exclude.split(",") if x}
    lines = tuple(x for x in lines if x not in excluded)
    unknown = [x for x in lines if x not in LINES]
    if unknown or not lines:
        print(f"모르는 호선: {unknown}. 쓸 수 있는 호선: {', '.join(LINES)}", file=sys.stderr)
        return 2

    stop = {"now": False}

    def request_stop(*_: object) -> None:
        stop["now"] = True

    previous = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            previous[sig] = signal.signal(sig, request_stop)
        except ValueError:  # 주 스레드가 아니면 신호를 걸 수 없다
            pass

    deadline = None if args.max_minutes is None else time.time() + args.max_minutes * 60

    def should_stop() -> bool:
        return stop["now"] or (deadline is not None and time.time() >= deadline)

    store = Store(args.db)
    try:
        index = StationIndex.load(args.stations_db)
        with httpx.Client() as http:
            collector = Collector(http, store, KeyRing(keys), index, lines=lines)
            logger.info(
                "수집 시작: 호선 %d개, 주기 %s초, 키 %s", len(lines), args.period, list(keys)
            )
            collector.run(
                period=args.period,
                should_stop=should_stop,
                max_cycles=1 if args.once else None,
                any_hour=args.any_hour,
            )
        logger.info("수집 끝")
    finally:
        store.close()
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
