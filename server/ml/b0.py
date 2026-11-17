"""B0 공식 시간표(설계 §4의 기준선). 서버 `TimetableService`와 같은 출처(1~9호선).

사용법 (server/에서):
    python -m ml.b0 --lines 2호선,5호선     # 기본: --db ml_data/collector.sqlite, 1~9호선 전부
    python -m ml.b0 --refresh               # 전부 다시 받아 지난번과의 차이를 적는다(매일)

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
from collections.abc import Callable, Iterable, Set
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

import httpx
from dotenv import dotenv_values

from app.clients.endpoints import SEOUL_OPENAPI_BASE
from app.clients.http import mask_url
from app.clients.seoul_timetable import CODE_NO_DATA, CODE_OK, MAX_ROWS, SERVICE, response_code
from app.core.clock import KST
from app.services.station_index import StationIndex
from ml.alerts import notifier_from_env, report_exit
from ml.dataset import station_ids
from ml.scopes import Scope, band_of, scope_key

logger = logging.getLogger("ml.b0")

# 시간표 API가 주는 노선. 2026-11-17에 1호선도 행이 오는 것을 확인했다(코레일 구간 포함).
TIMETABLE_LINES: tuple[str, ...] = tuple(f"{n}호선" for n in range(1, 10))
DAY_TYPES: tuple[int, ...] = (1, 2, 3)  # 평일·토요일·휴일. 본 적 없는 요일 구분도 미리 받는다
NEAREST_MAX_GAP_SEC = 600  # 열차 번호가 안 맞을 때, 이 안의 가장 가까운 시각만 같은 열차로 본다
_KEY_ENV = "SEOUL_OPENAPI_KEY"
EMPTY_STREAK_ACCEPT = 3  # 이만큼 연속으로 빈 응답이면 실제로 없어진 것으로 받아들인다
EMPTY_FAIL_SHARE = 0.20  # 갑자기 빈 대상이 이 비율을 넘으면 받기 전체를 실패로 본다
KEEP_OLD_CACHES = 6  # 남겨 두는 옛 캐시 폴더 수(열린 후보가 가리키는 것은 따로 지킨다)
DIFF_KEEP_DAYS = 30  # 차이 목록에 남기는 기간
DIFF_VERSION = 2

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
    """(호선, statnId) → 시간표 역 코드. 시간표 API가 있는 1~9호선만."""
    codes: dict[tuple[str, str], str] = {}
    for (line, statn_id), station_id in station_ids(db_path).items():
        if line not in TIMETABLE_LINES or station_id is None:
            continue
        code = index.station_code(station_id, line)
        if code is not None:
            codes[(line, statn_id)] = code
    return codes


def targets_for(codes: dict[tuple[str, str], str], lines: set[str]) -> list[Target]:
    """받을 대상: 그 호선들의 역 코드 × 요일 구분 셋 × 방향 둘."""
    return sorted(
        {
            (code, day_type, inout)
            for (line, _), code in codes.items()
            if line in lines
            for day_type in DAY_TYPES
            for inout in (1, 2)
        }
    )


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


def target_key(target: Target) -> str:
    station_cd, week_tag, inout_tag = target
    return f"{station_cd}_{week_tag}_{inout_tag}"


@dataclass(frozen=True)
class RefreshResult:
    changed: dict[Scope, float]
    failed: int  # 0이 아니면 아무것도 바꾸지 않았다
    skipped: list[str]  # 빈 응답이라 지난 행을 유지한 대상(target_key)
    old_cache: str | None  # 차이가 있어 남긴 옛 캐시 폴더 이름
    empty_streaks: dict[str, int]  # 대상별 연속 빈 응답 횟수(다음 받기에 넘긴다)


def refresh(
    http: httpx.Client,
    key: str,
    targets: Iterable[Target],
    cache_dir: str | Path,
    *,
    today: date,
    lines_by_code: dict[str, str],
    empty_streaks: dict[str, int] | None = None,
    protected: Set[str] = frozenset(),
    keep: int = KEEP_OLD_CACHES,
    sleep: Callable[[float], None] = time.sleep,
) -> RefreshResult:
    """모두 새로 받아 지난 캐시와 견준다.

    - 하나라도 실패하면 아무것도 바꾸지 않는다.
    - 지난번에 행이 있던 대상이 0행으로 오면 받기 오류로 보고 지난 행을 유지한다(차이에서 뺀다).
      `EMPTY_STREAK_ACCEPT`번 연속이면 실제로 없어진 것으로 받아들인다. 그런 대상이 전체의
      `EMPTY_FAIL_SHARE`를 넘으면 받기 전체를 실패로 본다.
    - 차이가 있을 때만 지난 캐시를 `캐시-YYYYMMDD`로 남긴다. 그 이름이 이미 있으면 있던 것을
      둔다(그날 처음의 옛 시간표). `protected`에 든 폴더는 지우지 않고 나머지는 최근 `keep`개.
    """
    cache_dir = Path(cache_dir)
    targets = list(targets)
    streaks = dict(empty_streaks or {})
    fresh_dir = cache_dir.with_name(cache_dir.name + ".next")
    shutil.rmtree(fresh_dir, ignore_errors=True)
    fresh = TimetableCache(fresh_dir)
    _, failed = fetch_timetables(http, key, targets, fresh, sleep=sleep)
    if failed:
        shutil.rmtree(fresh_dir, ignore_errors=True)
        return RefreshResult({}, failed, [], None, streaks)

    old = TimetableCache(cache_dir)
    new_streaks = dict(streaks)
    skipped: list[Target] = []
    for target in targets:
        name = target_key(target)
        if old.rows(*target) and fresh.rows(*target) == []:
            count = streaks.get(name, 0) + 1
            if count < EMPTY_STREAK_ACCEPT:
                skipped.append(target)
                new_streaks[name] = count
                continue
        new_streaks.pop(name, None)
    if len(skipped) > EMPTY_FAIL_SHARE * len(targets):
        shutil.rmtree(fresh_dir, ignore_errors=True)
        return RefreshResult({}, len(skipped), [], None, streaks)
    for target in skipped:
        fresh.save(*target, old.rows(*target) or [])
    held = set(skipped)
    changed = diff_caches(old, fresh, [t for t in targets if t not in held], lines_by_code)

    old_cache: str | None = None
    if cache_dir.is_dir():
        aside = cache_dir.with_name(cache_dir.name + ".old")
        shutil.rmtree(aside, ignore_errors=True)
        if changed:
            kept = cache_dir.with_name(f"{cache_dir.name}-{today:%Y%m%d}")
            old_cache = kept.name
            cache_dir.rename(aside if kept.exists() else kept)
        else:
            cache_dir.rename(aside)
        fresh_dir.rename(cache_dir)
        shutil.rmtree(aside, ignore_errors=True)
        dated = sorted(
            p
            for p in cache_dir.parent.glob(f"{cache_dir.name}-*")
            if p.name[-8:].isdigit() and p.name not in protected and p.name != old_cache
        )
        budget = keep - (1 if old_cache else 0)
        for stale in dated[: max(0, len(dated) - budget)]:
            shutil.rmtree(stale, ignore_errors=True)
    else:
        fresh_dir.rename(cache_dir)
    return RefreshResult(changed, 0, [target_key(t) for t in skipped], old_cache, new_streaks)


def load_diff_log(path: str | Path) -> dict:
    """차이 목록. 파일이 없거나 옛 꼴·깨진 파일이면 빈 목록."""
    empty = {"version": DIFF_VERSION, "entries": [], "empty_streaks": {}}
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return empty
    if (
        not isinstance(data, dict)
        or data.get("version") != DIFF_VERSION
        or not isinstance(data.get("entries"), list)
    ):
        return empty
    streaks = data.get("empty_streaks")
    return {
        "version": DIFF_VERSION,
        "entries": data["entries"],
        "empty_streaks": dict(streaks) if isinstance(streaks, dict) else {},
    }


def record_refresh(path: str | Path, result: RefreshResult, *, today: date) -> None:
    """그날의 받기 결과를 차이 목록에 더한다. `DIFF_KEEP_DAYS`일 지난 항목은 버린다.

    같은 날 항목이 이미 있으면 합친다(먼저 적은 옛 캐시 이름을 지킨다). 밤 작업이 하루
    빠져도 그 날의 차이를 잃지 않게 날짜별로 둔다.
    """
    path = Path(path)
    data = load_diff_log(path)
    iso = today.isoformat()
    changed = {scope_key(scope): round(ratio, 4) for scope, ratio in result.changed.items()}
    entry = next((e for e in data["entries"] if e.get("checked_on") == iso), None)
    if entry is None:
        data["entries"].append(
            {
                "checked_on": iso,
                "changed": changed,
                "old_cache": result.old_cache,
                "skipped": list(result.skipped),
            }
        )
    else:
        entry["changed"] = {**(entry.get("changed") or {}), **changed}
        entry["old_cache"] = entry.get("old_cache") or result.old_cache
        entry["skipped"] = sorted(set(entry.get("skipped") or []) | set(result.skipped))
    cutoff = (today - timedelta(days=DIFF_KEEP_DAYS)).isoformat()
    data["entries"] = sorted(
        (e for e in data["entries"] if str(e.get("checked_on", "")) >= cutoff),
        key=lambda e: e["checked_on"],
    )
    data["empty_streaks"] = dict(result.empty_streaks)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, path)


def _protected_caches(state_path: str | Path) -> set[str]:
    """열린 후보가 가리키는 옛 캐시 폴더 이름(지우면 판정할 수 없다). 못 읽으면 빈 집합.

    `ml.changes`가 이 모듈을 import하므로 상태 파일은 JSON으로 직접 읽는다.
    """
    try:
        data = json.loads(Path(state_path).read_text(encoding="utf-8"))
        return {
            str(c["old_cache"]) for c in data.get("candidates", []) if c.get("status") == "open"
        }
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return set()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="공식 시간표(B0) 받아 두기")
    parser.add_argument("--refresh", action="store_true", help="전부 다시 받아 차이를 적는다")
    parser.add_argument("--diff-out", default="ml_data/official_diff.json")
    parser.add_argument("--state", default="ml_data/change_state.json")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--cache", default="ml_data/timetable_cache")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    parser.add_argument("--lines", default="", help="쉼표로 나눈 호선(기본: 1~9호선)")
    args = parser.parse_args(argv)
    notify = notifier_from_env(args.env_file, os.environ)
    try:
        code = _run(args)
    except Exception:
        logger.exception("공식 시간표 받기가 예기치 않게 멈췄습니다")
        code = 1
    return report_exit(code, notify, "공식 시간표 받기", "subway-ml-official")


def _run(args: argparse.Namespace) -> int:
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
    targets = targets_for(codes, lines)
    if args.refresh:
        today = datetime.now(KST).date()
        lines_by_code = {code: line for (line, _), code in codes.items()}
        with httpx.Client() as http:
            result = refresh(
                http, key, targets, args.cache, today=today, lines_by_code=lines_by_code,
                empty_streaks=load_diff_log(args.diff_out)["empty_streaks"],
                protected=_protected_caches(args.state),
            )  # fmt: skip
        if result.failed:
            print(
                f"시간표 다시 받기 실패 {result.failed}개. 캐시는 그대로 둡니다.", file=sys.stderr
            )
            return 1
        record_refresh(args.diff_out, result, today=today)
        print(
            f"시간표: 대상 {len(targets)}개 다시 받음, 달라진 단위 {len(result.changed)}개,"
            f" 빈 응답이라 지난 것을 둔 대상 {len(result.skipped)}개 ({args.diff_out})"
        )
        return 0
    with httpx.Client() as http:
        fetched, failed = fetch_timetables(http, key, targets, TimetableCache(args.cache))
    print(f"시간표: 대상 {len(targets)}개, 새로 받음 {fetched}개, 실패 {failed}개 ({args.cache})")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
