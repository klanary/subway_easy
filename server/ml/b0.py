"""B0 공식 시간표(설계 §4의 기준선). 서버 `TimetableService`와 같은 출처(2~9호선만).

사용법 (server/에서):
    python -m ml.b0 --lines 2호선,5호선     # 기본: --db ml_data/collector.sqlite, 2~9호선 전부
    python -m ml.b0 --refresh               # 전부 다시 받아 지난번과의 차이를 적는다(주 1회)

- 평가·분석이 쓰도록 역·요일·방향별 시간표를 파일로 받아 둔다(한 번 받은 것은 다시 받지 않는다).
- 이 도구만 일반 인증키(SEOUL_OPENAPI_KEY)를 쓴다. 수집기와는 별개인 오프라인 도구다.
  수집기의 키 세 개는 여기서 쓰지 않는다.
"""

import argparse
import json
import logging
import os
import shutil
import sys
import time
from collections import defaultdict
from collections.abc import Callable, Iterable
from datetime import date, datetime
from pathlib import Path

import httpx
from dotenv import dotenv_values

from app.clients.endpoints import SEOUL_OPENAPI_BASE
from app.clients.http import mask_url
from app.clients.seoul_timetable import CODE_NO_DATA, CODE_OK, MAX_ROWS, SERVICE, response_code
from app.core.clock import KST
from app.services.station_index import StationIndex
from ml.dataset import _open_ro, station_ids
from ml.scopes import Scope, band_of, scope_key

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

    def departures(
        self, day_type: int, line: str, direction: int, statn_id: str
    ) -> list[tuple[str, int]]:
        """(열차 번호, 출발 초), 시각순. 이 역에서 끝나는 열차는 뺀다."""
        table = self._table(day_type, line, direction, statn_id)
        return sorted(
            ((number, dep) for number, _, dep in table if dep is not None), key=lambda x: x[1]
        )

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


def diff_caches(
    old: TimetableCache,
    new: TimetableCache,
    targets: Iterable[Target],
    lines_by_code: dict[str, str],
) -> dict[Scope, float]:
    """두 캐시의 차이를 단위(요일 구분, 호선, 방향, 시간대)별 비율로.

    비율 = (열차 번호, 출발 시각) 집합의 대칭차 ÷ 합집합. 차이가 없는 단위는 결과에 없다.
    `old`에 없는 대상(처음 받는 역)은 건너뛴다.
    """
    differ: dict[Scope, int] = defaultdict(int)
    union: dict[Scope, int] = defaultdict(int)
    for station_cd, week_tag, inout_tag in targets:
        before, after = (
            old.rows(station_cd, week_tag, inout_tag),
            new.rows(station_cd, week_tag, inout_tag),
        )
        line = lines_by_code.get(station_cd)
        if before is None or after is None or line is None:
            continue

        def departures(rows: list[dict]) -> set[tuple[str, int]]:
            found = set()
            for row in rows:
                secs = _secs(row.get("LEFTTIME"))
                if secs is not None:
                    found.add((str(row.get("TRAIN_NO") or ""), secs))
            return found

        a, b = departures(before), departures(after)
        for _, secs in a | b:
            union[(week_tag, line, inout_tag - 1, band_of(secs))] += 1
        for _, secs in a ^ b:
            differ[(week_tag, line, inout_tag - 1, band_of(secs))] += 1
    return {scope: differ[scope] / union[scope] for scope in differ}


def refresh(
    http: httpx.Client,
    key: str,
    targets: Iterable[Target],
    cache_dir: str | Path,
    *,
    today: date,
    lines_by_code: dict[str, str],
    keep: int = 4,
    sleep: Callable[[float], None] = time.sleep,
) -> tuple[dict[Scope, float], int]:
    """모두 새로 받아 지난 캐시와 견준다. (단위별 차이, 실패 수).

    하나라도 실패하면 아무것도 바꾸지 않는다. 성공하면 지난 캐시는 `캐시-YYYYMMDD`로 남기고
    (최근 `keep`개만) 새로 받은 것이 캐시가 된다.
    """
    cache_dir = Path(cache_dir)
    targets = list(targets)
    fresh_dir = cache_dir.with_name(cache_dir.name + ".next")
    shutil.rmtree(fresh_dir, ignore_errors=True)
    fresh = TimetableCache(fresh_dir)
    _, failed = fetch_timetables(http, key, targets, fresh, sleep=sleep)
    if failed:
        shutil.rmtree(fresh_dir, ignore_errors=True)
        return {}, failed
    changed = diff_caches(TimetableCache(cache_dir), fresh, targets, lines_by_code)
    if cache_dir.is_dir():
        kept = cache_dir.with_name(f"{cache_dir.name}-{today:%Y%m%d}")
        shutil.rmtree(kept, ignore_errors=True)
        cache_dir.rename(kept)
        dated = sorted(
            p for p in cache_dir.parent.glob(f"{cache_dir.name}-*") if p.name[-8:].isdigit()
        )
        for stale in dated[: max(0, len(dated) - keep)]:
            shutil.rmtree(stale, ignore_errors=True)
    fresh_dir.rename(cache_dir)
    return changed, 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="공식 시간표(B0) 받아 두기")
    parser.add_argument("--refresh", action="store_true", help="전부 다시 받아 차이를 적는다")
    parser.add_argument("--diff-out", default="ml_data/official_diff.json")
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
    if args.refresh:
        today = datetime.now(KST).date()
        lines_by_code = {code: line for (line, _), code in codes.items()}
        with httpx.Client() as http:
            changed, failed = refresh(
                http, key, targets, args.cache, today=today, lines_by_code=lines_by_code
            )
        if failed:
            print(f"시간표 다시 받기 실패 {failed}개. 캐시는 그대로 둡니다.", file=sys.stderr)
            return 1
        out = Path(args.diff_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "checked_on": today.isoformat(),
            "changed": {scope_key(scope): round(ratio, 4) for scope, ratio in changed.items()},
        }
        out.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"시간표: 대상 {len(targets)}개 다시 받음, 달라진 단위 {len(changed)}개 ({out})")
        return 0
    with httpx.Client() as http:
        fetched, failed = fetch_timetables(http, key, targets, TimetableCache(args.cache))
    print(f"시간표: 대상 {len(targets)}개, 새로 받음 {fetched}개, 실패 {failed}개 ({args.cache})")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
