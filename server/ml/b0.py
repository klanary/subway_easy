"""B0 공식 시간표(설계 §4의 기준선). 서버 `TimetableService`와 같은 출처(2~9호선만).

사용법 (server/에서):
    python -m ml.b0 --lines 2호선,5호선     # 기본: --db ml_data/collector.sqlite, 2~9호선 전부

- 평가·분석이 쓰도록 역·요일·방향별 시간표를 파일로 받아 둔다(한 번 받은 것은 다시 받지 않는다).
- 이 도구만 일반 인증키(SEOUL_OPENAPI_KEY)를 쓴다. 수집기와는 별개인 오프라인 도구다.
  수집기의 키 세 개는 여기서 쓰지 않는다.
"""

import argparse
import json
import logging
import os
import sys
import time
from collections.abc import Callable, Iterable
from pathlib import Path

import httpx
from dotenv import dotenv_values

from app.clients.endpoints import SEOUL_OPENAPI_BASE
from app.clients.http import mask_url
from app.clients.seoul_timetable import CODE_NO_DATA, CODE_OK, MAX_ROWS, SERVICE, response_code
from app.services.station_index import StationIndex
from ml.dataset import _open_ro, station_ids

logger = logging.getLogger("ml.b0")

TIMETABLE_LINES: tuple[str, ...] = tuple(f"{n}호선" for n in range(2, 10))
NEAREST_MAX_GAP_SEC = 600  # 열차 번호가 안 맞을 때, 이 안의 가장 가까운 시각만 같은 열차로 본다
_KEY_ENV = "SEOUL_OPENAPI_KEY"

Target = tuple[str, int, int]  # (역 코드, 요일 코드, 방향 코드 1 상행·내선 / 2 하행·외선)


def _secs(value: object) -> int | None:
    """ "HH:MM:SS" → 운행일 0시부터 초. 자정 넘은 열차는 24시 이후로 온다. 빈 값·00:00:00은 None."""
    try:
        h, m, s = (int(x) for x in str(value).split(":"))
    except ValueError:
        return None
    total = h * 3600 + m * 60 + s
    return total or None


class TimetableCache:
    """역 코드·요일·방향별 시간표 행을 JSON 파일로 둔다."""

    def __init__(self, directory: str | Path):
        self._dir = Path(directory)

    def _path(self, station_cd: str, week_tag: int, inout_tag: int) -> Path:
        return self._dir / f"{station_cd}_{week_tag}_{inout_tag}.json"

    def rows(self, station_cd: str, week_tag: int, inout_tag: int) -> list[dict] | None:
        path = self._path(station_cd, week_tag, inout_tag)
        if not path.is_file():
            return None
        return json.loads(path.read_text(encoding="utf-8"))

    def save(self, station_cd: str, week_tag: int, inout_tag: int, rows: list[dict]) -> None:
        self._dir.mkdir(parents=True, exist_ok=True)
        self._path(station_cd, week_tag, inout_tag).write_text(
            json.dumps(rows, ensure_ascii=False), encoding="utf-8"
        )


class B0:
    """관측(요일 구분, 호선, 방향, statnId)에 맞는 공식 시간표 시각을 찾는다."""

    def __init__(self, cache: TimetableCache, codes: dict[tuple[str, str], str]):
        self._cache = cache
        self._codes = codes
        self._tables: dict[Target, list[tuple[str, int | None, int | None]]] = {}

    def _table(
        self, day_type: int, line: str, direction: int, statn_id: str
    ) -> list[tuple[str, int | None, int | None]]:
        """(열차 번호, 도착 초, 출발 초). 이 역에서 끝나는 열차의 출발은 None."""
        code = self._codes.get((line, statn_id))
        if code is None:
            return []
        target = (code, day_type, direction + 1)
        if target not in self._tables:
            rows = self._cache.rows(*target) or []
            self._tables[target] = [
                (
                    str(row.get("TRAIN_NO") or ""),
                    _secs(row.get("ARRIVETIME")),
                    None if row.get("DESTSTATION") == code else _secs(row.get("LEFTTIME")),
                )
                for row in rows
            ]
        return self._tables[target]

    def departure(
        self, day_type: int, line: str, direction: int, statn_id: str, *, train_no: str,
        near_secs: int,
    ) -> int | None:  # fmt: skip
        """같은 열차 번호의 출발 시각. 번호가 없으면 `near_secs`에 가장 가까운 것(10분 안)."""
        table = self._table(day_type, line, direction, statn_id)
        for number, _, dep in table:
            if number == train_no and dep is not None:
                return dep
        deps = [dep for _, _, dep in table if dep is not None]
        if not deps:
            return None
        best = min(deps, key=lambda dep: abs(dep - near_secs))
        return best if abs(best - near_secs) <= NEAREST_MAX_GAP_SEC else None

    def has_train(
        self, day_type: int, line: str, direction: int, statn_id: str, train_no: str
    ) -> bool:
        """이 역 시간표에 같은 열차 번호가 있는가(위치 API와 시간표의 번호가 통하는지 볼 때)."""
        table = self._table(day_type, line, direction, statn_id)
        return any(number == train_no for number, _, _ in table)

    def arrival(
        self, day_type: int, line: str, direction: int, statn_id: str, *, train_no: str
    ) -> int | None:
        for number, arr, dep in self._table(day_type, line, direction, statn_id):
            if number == train_no:
                return arr if arr is not None else dep
        return None


def station_codes(db_path: str | Path, index: StationIndex) -> dict[tuple[str, str], str]:
    """(호선, statnId) → 시간표 역 코드. 시간표 API가 있는 2~9호선만."""
    codes: dict[tuple[str, str], str] = {}
    for (line, statn_id), station_id in station_ids(db_path).items():
        if line not in TIMETABLE_LINES or station_id is None:
            continue
        code = index.station_code(station_id, line)
        if code is not None:
            codes[(line, statn_id)] = code
    return codes


def fetch_timetables(
    http: httpx.Client,
    key: str,
    targets: Iterable[Target],
    cache: TimetableCache,
    *,
    sleep: Callable[[float], None] = time.sleep,
    pause_sec: float = 0.1,
) -> tuple[int, int]:
    """캐시에 없는 것만 받는다. (받은 수, 실패한 수). "데이터 없음"은 빈 목록으로 저장한다."""
    fetched = failed = 0
    for station_cd, week_tag, inout_tag in targets:
        if cache.rows(station_cd, week_tag, inout_tag) is not None:
            continue
        url = (
            f"{SEOUL_OPENAPI_BASE}/{key}/json/{SERVICE}"
            f"/1/{MAX_ROWS}/{station_cd}/{week_tag}/{inout_tag}/"
        )
        try:
            response = http.get(url, timeout=10.0)
            body = response.json() if response.status_code < 500 else None
        except (httpx.HTTPError, ValueError) as e:
            logger.warning("시간표 호출 실패: %s url=%s", type(e).__name__, mask_url(url))
            body = None
        code = response_code(body)
        if code == CODE_OK:
            cache.save(station_cd, week_tag, inout_tag, list(body[SERVICE].get("row") or []))
            fetched += 1
        elif code == CODE_NO_DATA:
            cache.save(station_cd, week_tag, inout_tag, [])
            fetched += 1
        else:
            logger.warning("시간표를 받지 못함: 역 코드 %s, 코드 %s", station_cd, code)
            failed += 1
        sleep(pause_sec)
    return fetched, failed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="공식 시간표(B0) 받아 두기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--cache", default="ml_data/timetable_cache")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    parser.add_argument("--lines", default="", help="쉼표로 나눈 호선(기본: 2~9호선)")
    args = parser.parse_args(argv)
    if not Path(args.db).is_file():
        print(f"수집 DB가 없습니다: {args.db}", file=sys.stderr)
        return 2
    key = (os.environ.get(_KEY_ENV) or dotenv_values(args.env_file).get(_KEY_ENV) or "").strip()
    if not key:
        print(f"{args.env_file}에 {_KEY_ENV}가 없습니다.", file=sys.stderr)
        return 2
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    lines = {x for x in args.lines.split(",") if x} or set(TIMETABLE_LINES)
    codes = station_codes(args.db, StationIndex.load(args.stations_db))
    con = _open_ro(args.db)
    try:
        day_types = [d for (d,) in con.execute("SELECT DISTINCT day_type FROM events")]
    finally:
        con.close()
    targets = sorted(
        {
            (code, day_type, inout)
            for (line, _), code in codes.items()
            if line in lines
            for day_type in day_types
            for inout in (1, 2)
        }
    )
    with httpx.Client() as http:
        fetched, failed = fetch_timetables(http, key, targets, TimetableCache(args.cache))
    print(f"시간표: 대상 {len(targets)}개, 새로 받음 {fetched}개, 실패 {failed}개 ({args.cache})")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
