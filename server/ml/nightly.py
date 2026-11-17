"""밤마다 한 번: 전날 하루치를 뽑고, 시간표 변경을 판단하고, 실측 시간표(B1)를 다시 만든다.

사용법 (server/에서, 백업 02:10 뒤·수집 시작 05:00 전):
    python -m ml.nightly              # 관찰 모드: 변경을 기록만 하고 B1에는 적용하지 않는다
    python -m ml.nightly --enforce    # 확정된 변경일 이후 날짜로만 그 단위를 다시 만든다

순서: 상태 읽기 → 빠진 날을 날짜순으로 뽑고 판단 → 호선마다 B1 → 결과물·상태·기록 쓰기.
한 번에 한 호선만 메모리에 올린다. 같은 밤에 다시 돌려도 결과가 같다.
"""

import argparse
import json
import logging
import os
import shutil
import sys
from collections import defaultdict
from collections.abc import Callable
from datetime import date, datetime, timedelta
from pathlib import Path

from app.core.clock import KST
from app.services.station_index import StationIndex
from ml.b0 import B0, TimetableCache, load_diff_log, station_codes
from ml.b1 import STALE_DAYS, WINDOW, build_b1_from_points
from ml.changes import (
    DEFAULT,
    ChangeRecord,
    ChangeState,
    CorruptState,
    Thresholds,
    annotate,
    append_log,
    detect_day,
    load_official_changed,
    official_fit,
    render,
)
from ml.dataset import _open_ro, service_dates, station_ids, station_names
from ml.official import (
    candidate_lines,
    ingest_candidates,
    judge_candidates,
    official_fill,
    prune_candidates,
)
from ml.points import PointStore
from ml.scopes import Scope

logger = logging.getLogger("ml.nightly")

CATCH_UP_DAYS = 14  # raw 보관 기간과 같다. 그보다 오래 꺼져 있었으면 수집 횟수를 모른다
_DISPUTED = ("early", "hold:mixed", "hold:base_fit")  # 관측이 새 공식 시간표를 받쳐 주지 않은 판정
KEEP_POINTS_DAYS = 70  # 창이 가장 긴 토·휴일 8주를 덮는다(하루 약 27MB)


def _event_lines(db: Path, day: date) -> list[str]:
    con = _open_ro(db)
    try:
        rows = con.execute(
            "SELECT DISTINCT line FROM events WHERE service_date = ? ORDER BY 1", (day.isoformat(),)
        ).fetchall()
    finally:
        con.close()
    return [line for (line,) in rows]


def _active_dates(
    store: PointStore, line: str, day_type: int, dates: list[date], th: Thresholds
) -> dict[Scope, set[date]]:
    """수집이 정상이었던 (단위 → 날짜). 수집 횟수를 모르는 날은 넣지 않는다."""
    active: dict[Scope, set[date]] = defaultdict(set)
    for day in dates:
        counts = store.coverage(line, day)
        for band, n in (counts or {}).items():
            if n >= th.min_coverage:
                for direction in (0, 1):
                    active[(day_type, line, direction, band)].add(day)
    return dict(active)


def _build_line(
    store: PointStore, line: str, as_of: date, epochs: dict[Scope, date] | None, th: Thresholds
) -> dict:
    points = []
    active: dict[Scope, set[date]] = {}
    for day_type in sorted(WINDOW):
        dates = store.dates(line, day_type, upto=as_of, n=WINDOW[day_type])
        points += store.points(line, day_type, dates)
        active.update(_active_dates(store, line, day_type, dates, th))
    return build_b1_from_points(
        points, window=WINDOW, stale_days=STALE_DAYS, epochs=epochs, active=active, as_of=as_of
    )


def _line_of(key: str) -> str:
    return key.split("|")[1]


def _merge(parts: dict[str, dict], previous: dict | None, failed: list[str], as_of: date) -> dict:
    """호선별 결과물을 하나로 합친다. 실패한 호선은 이전 결과물의 것을 그대로 옮긴다."""
    merged = build_b1_from_points([], window=WINDOW, stale_days=STALE_DAYS, as_of=as_of)
    dates_from: list[str] = []
    dates_to: list[str] = []
    n_dates: dict[str, int] = {}
    for part in parts.values():
        for name in ("cells", "scopes", "modes", "train_no_stability"):
            merged[name].update(part[name])
        merged["n_departures"] += part["n_departures"]
        if part["date_from"]:
            dates_from.append(part["date_from"])
            dates_to.append(part["date_to"])
        for day_type, n in part["n_dates"].items():
            n_dates[day_type] = max(n, n_dates.get(day_type, 0))
    if previous is not None:
        for line in failed:
            for name in ("cells", "scopes"):
                merged[name].update(
                    {k: v for k, v in (previous.get(name) or {}).items() if _line_of(k) == line}
                )
            if line in previous.get("modes", {}):
                merged["modes"][line] = previous["modes"][line]
    merged["date_from"] = min(dates_from) if dates_from else None
    merged["date_to"] = max(dates_to) if dates_to else None
    merged["n_dates"] = dict(sorted(n_dates.items()))
    merged["cells"] = dict(sorted(merged["cells"].items()))
    return merged


def _write_json(path: Path, data: dict) -> None:
    """임시 파일에 다 쓴 뒤 바꾼다. 바꾸기 전 것은 `….prev`로 남긴다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    if path.is_file():
        shutil.copyfile(path, path.with_name(path.name + ".prev"))
    os.replace(tmp, path)


def run_nightly(
    *,
    db: str | Path,
    points_path: str | Path,
    state_path: str | Path,
    out_path: str | Path,
    log_path: str | Path,
    today: date,
    enforce: bool = False,
    official_diff_path: str | Path | None = None,
    b0: B0 | None = None,
    timetable_cache: str | Path | None = None,
    codes: dict[tuple[str, str], str] | None = None,
    notify: Callable[[str], None] | None = None,
    reextract: bool = False,
    catch_up_days: int = CATCH_UP_DAYS,
    keep_points_days: int = KEEP_POINTS_DAYS,
    th: Thresholds = DEFAULT,
) -> int:
    """종료 코드: 0 정상, 1 일부 호선 실패(나머지는 반영), 2 시작할 수 없음(아무것도 안 씀)."""
    db, out_path = Path(db), Path(out_path)
    if not db.is_file():
        print(f"수집 DB가 없습니다: {db}", file=sys.stderr)
        return 2
    try:
        state = ChangeState.load(state_path)
    except CorruptState as e:
        print(f"상태 파일을 읽을 수 없어 멈춥니다(덮어쓰지 않음): {e}", file=sys.stderr)
        return 2
    as_of = today - timedelta(days=1)
    failed = False
    store = PointStore(points_path)
    try:
        # 1) 빠진 날을 날짜순으로 뽑고 판단한다.
        changed: list[ChangeRecord] = []
        official_changed = (
            load_official_changed(official_diff_path, today=today)
            if official_diff_path is not None
            else set()
        )
        # 공식 시간표가 달라진 단위를 후보로 들인다(밤 작업이 빠진 날의 것도 함께).
        candidate_log: list[str] = []
        if official_diff_path is not None:
            try:
                entries = load_diff_log(official_diff_path)["entries"]
                candidate_log += candidate_lines(ingest_candidates(state, entries))
            except Exception as e:
                logger.error("후보 들이기 실패: %s", type(e).__name__)
                failed = True
        can_judge = (
            timetable_cache is not None and codes is not None and Path(timetable_cache).is_dir()
        )
        days = service_dates(db, date_from=today - timedelta(days=catch_up_days), date_to=as_of)
        for day in days:
            try:
                have = set() if reextract else {line for line, _ in store.lines(day)}
                missing = [line for line in _event_lines(db, day) if line not in have]
                if missing:
                    store.extract_day(db, day, lines=missing)
                # 후보 판정이 먼저다. 확정되면 그 단위의 기준일이 그 날이 되어, 아래 실측
                # 감지의 기준표에서 그 단위가 빠진다.
                if can_judge:
                    try:
                        confirmed, lines = judge_candidates(
                            state, store, timetable_cache, codes, day, th=th
                        )
                        changed += confirmed
                        candidate_log += lines
                    except Exception as e:
                        logger.error("후보 판정 실패(%s): %s", day.isoformat(), type(e).__name__)
                        failed = True
                # 후보 판정이 그 날 "관측은 아직 옛 시간표"·"엇갈림"·"공식 시간표가 안 맞음"으로
                # 본 단위는, 실측 의심이 걸려도 공식 근거로 하루 만에 확정하지 않는다.
                disputed = {
                    c.scope
                    for c in state.candidates
                    if any(tried == day and result in _DISPUTED for tried, result in c.tries)
                }
                changed += detect_day(
                    store, state, day, th=th, official_changed=official_changed - disputed
                )
            except Exception as e:  # 그 뒤 날짜를 건너뛰면 순서가 어긋나므로 판단을 멈춘다
                logger.error("판단 실패(%s): %s", day.isoformat(), type(e).__name__)
                failed = True
                break
        # 확정된 변경을 공식 시간표가 따라왔는지(1~9호선, 시간표 캐시가 있을 때만).
        if b0 is not None and not failed:
            try:
                changed += official_fit(b0, store, state, as_of, th=th)
            except Exception as e:
                logger.error("공식 시간표 비교 실패: %s", type(e).__name__)
                failed = True

        # 2) 호선마다 B1을 만든다.
        previous = None
        if out_path.is_file():
            try:
                previous = json.loads(out_path.read_text(encoding="utf-8"))
            except ValueError:
                previous = None
        epochs = state.epochs() if enforce else None
        parts: dict[str, dict] = {}
        failed_lines: list[str] = []
        for line in sorted(store.all_lines()):
            try:
                parts[line] = _build_line(store, line, as_of, epochs, th)
                if enforce and b0 is not None and codes is not None:
                    # 공식 근거로 확정된 단위는 실측이 찰 때까지 새 공식 시각으로 채운다.
                    stations = [statn for known, statn in codes if known == line]
                    official_fill(parts[line], state, b0, stations, line=line)
            except Exception as e:
                logger.error("B1 실패(%s): %s", line, type(e).__name__)
                failed_lines.append(line)
        failed = failed or bool(failed_lines)
        artifact = _merge(parts, previous, failed_lines, as_of)
        annotate(artifact, state, as_of=as_of, enforce=enforce)
        names, ids = station_names(db), station_ids(db)
        artifact["stations"] = {
            f"{line}|{statn_id}": {"station_id": ids.get((line, statn_id)), "name": name}
            for (line, statn_id), name in sorted(names.items())
        }
        _write_json(out_path, artifact)

        # 3) 상태와 기록. 같은 기록이 하루에 두 번 바뀌었으면(따라잡기) 마지막 모습만 적는다.
        prune_candidates(state, today)
        state.save(state_path)
        latest = list({id(r): r for r in changed}.values())
        append_log(log_path, as_of, candidate_log + render(latest))
        if notify is not None:
            for text in render([r for r in latest if r.status == "confirmed"]):
                try:
                    notify(text)
                except Exception as e:
                    logger.warning("알림 실패: %s", type(e).__name__)
        store.prune(today - timedelta(days=keep_points_days))
    finally:
        store.close()

    counts = {s: sum(r.status == s for r in latest) for s in ("suspect", "confirmed", "cleared")}
    n_entries = sum(len(v) for v in artifact["cells"].values())
    print(
        f"밤 작업({as_of.isoformat()}까지): 날짜 {len(days)}일,"
        f" 의심 {counts['suspect']}·확정 {counts['confirmed']}·해제 {counts['cleared']},"
        f" 칸 {len(artifact['cells'])}개·시각 {n_entries}개,"
        f" {'적용' if enforce else '관찰'} 모드" + (" (일부 실패)" if failed else "")
    )
    return 1 if failed else 0


def main(argv: list[str] | None = None, *, now: datetime | None = None) -> int:
    parser = argparse.ArgumentParser(description="밤 작업: 시간표 변경 판단과 B1 다시 만들기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--points", default="ml_data/points.sqlite")
    parser.add_argument("--state", default="ml_data/change_state.json")
    parser.add_argument("--out", default="ml_data/artifacts/measured_timetable.json")
    parser.add_argument("--log", default="ml_data/changes.md")
    parser.add_argument("--enforce", action="store_true", help="확정된 변경을 B1에 적용한다")
    parser.add_argument("--day", default="", help="오늘 날짜 대신 쓸 날짜(YYYY-MM-DD)")
    parser.add_argument(
        "--reextract", action="store_true", help="따라잡기 창 안의 날을 모두 다시 뽑는다"
    )
    parser.add_argument(
        "--official-diff", default="ml_data/official_diff.json", help="ml.b0 --refresh가 쓴 파일"
    )
    parser.add_argument(
        "--timetable-cache",
        default="ml_data/timetable_cache",
        help="있을 때만 공식 시간표와 견준다",
    )
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    today = (
        date.fromisoformat(args.day)
        if args.day
        else (now or datetime.now(KST)).astimezone(KST).date()
    )
    b0, codes = None, None
    if Path(args.timetable_cache).is_dir() and Path(args.db).is_file():
        codes = station_codes(args.db, StationIndex.load(args.stations_db))
        b0 = B0(TimetableCache(args.timetable_cache), codes)
    return run_nightly(
        db=args.db, points_path=args.points, state_path=args.state, out_path=args.out,
        log_path=args.log, today=today, enforce=args.enforce,
        official_diff_path=args.official_diff, b0=b0, reextract=args.reextract,
        timetable_cache=args.timetable_cache if codes is not None else None, codes=codes,
    )  # fmt: skip


if __name__ == "__main__":
    raise SystemExit(main())
