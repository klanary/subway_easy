"""수집 첫 주에 확인할 것(설계 §3)을 Markdown 보고서로 낸다.

사용법 (server/에서):
    python -m ml.analyze --db ml_data/collector.sqlite --out ml_data/analysis.md
    python -m ml.analyze --timetable-cache ml_data/timetable_cache   # 4번(공식 시간표와의 차이)까지
"""

import argparse
import sqlite3
import sys
from collections import Counter, defaultdict
from pathlib import Path

from app.services.station_index import StationIndex
from ml.b0 import B0, TIMETABLE_LINES, TimetableCache, station_codes
from ml.b1 import departure_points, train_no_stability
from ml.b2 import MAX_DWELL_SEC, MAX_HOP_SEC, hop_samples, stats
from ml.collector import LINES
from ml.dataset import ARRIVE, DEPART, Obs, Run, _open_ro, build_runs, load_events, station_names

_MIN_STATION_EVENTS = 10  # "늘 이상한 역"을 가릴 때 이보다 적게 본 역은 판단하지 않는다


def _line_order(line: str) -> int:
    return LINES.index(line) if line in LINES else len(LINES)


def _dist(values: list[float]) -> str:
    if not values:
        return "–"
    s = stats(values)
    return f"{s['median']:.0f} ({s['p10']:.0f}~{s['p90']:.0f}, n={s['n']})"


def _stability(runs: list[Run]) -> list[str]:
    out = ["## 1. 열차 번호가 날마다 같은가", ""]
    dates_by_type: dict[int, set] = defaultdict(set)
    for run in runs:
        dates_by_type[run.day_type].add(run.service_date)
    most = max((len(d) for d in dates_by_type.values()), default=0)
    out.append(
        "요일 구분별 운행일 수: "
        + (", ".join(f"{k}번 {len(v)}일" for k, v in sorted(dates_by_type.items())) or "없음")
        + " (1 평일, 2 토요일, 3 휴일)"
    )
    out.append("")
    if most < 2:
        out += ["같은 요일 구분의 날짜가 2일 미만이라 아직 판단할 수 없다.", ""]
        return out
    pairs: Counter[str] = Counter()
    seen: dict[tuple, set] = defaultdict(set)
    for p in departure_points(runs):
        seen[(p.line, p.day_type, p.direction, p.statn_id, p.train_no)].add(p.service_date)
    for (line, *_), days in seen.items():
        if len(days) >= 2:
            pairs[line] += 1
    stability = train_no_stability(runs)
    out += [
        "같은 요일 구분에서 2일 이상 본 (역, 열차 번호) 가운데 출발 시각 차이가 300초 안인 비율.",
        "0.8 이상이면 B1을 열차 번호별로, 아니면 시간대 묶음으로 만든다.",
        "",
        "| 호선 | 2일 이상 본 (역, 열차 번호) | 같은 시각 비율 |",
        "|---|---|---|",
    ]
    for line in sorted(stability, key=_line_order):
        out.append(f"| {line} | {pairs[line]} | {stability[line] * 100:.1f}% |")
    out.append("")
    return out


def _times(runs: list[Run]) -> list[str]:
    dwell: dict[str, list[float]] = defaultdict(list)
    travel: dict[str, list[float]] = defaultdict(list)
    hop: dict[str, list[float]] = defaultdict(list)
    for run in runs:
        for v in run.visits:
            if v.arr is not None and v.dep is not None and 0 < v.dep - v.arr <= MAX_DWELL_SEC:
                dwell[run.line].append(v.dep - v.arr)
        for a, b in zip(run.visits, run.visits[1:], strict=False):
            if a.dep is not None and b.arr is not None and 0 < b.arr - a.dep <= MAX_HOP_SEC:
                travel[run.line].append(b.arr - a.dep)
    for run, _, _, _, took in hop_samples(runs):
        hop[run.line].append(took)
    out = [
        "## 2. 정차 시간과 역 사이 이동 시간",
        "",
        "초 단위. 중앙값 (10백분위~90백분위, 표본 수).",
        "",
        "| 호선 | 정차(도착→출발) | 이동(출발→다음 역 도착) | 도착→다음 역 도착 |",
        "|---|---|---|---|",
    ]
    for line in sorted(set(dwell) | set(travel) | set(hop), key=_line_order):
        out.append(
            f"| {line} | {_dist(dwell[line])} | {_dist(travel[line])} | {_dist(hop[line])} |"
        )
    out.append("")
    return out


def _lag_by_line(con: sqlite3.Connection) -> dict[str, list[float]]:
    lag: dict[str, list[float]] = defaultdict(list)
    for line, seconds in con.execute(
        "SELECT line, (julianday(first_seen_at) - julianday(event_at)) * 86400.0 FROM events"
    ):
        if seconds is not None:
            lag[line].append(seconds)
    return lag


def _errors(
    all_events: list[Obs], names: dict[tuple[str, str], str], lag: dict[str, list[float]]
) -> list[str]:
    total: Counter[str] = Counter()
    flags: dict[str, Counter[str]] = defaultdict(Counter)
    flagged_station: Counter[tuple[str, str, str]] = Counter()
    by_station: dict[tuple[str, str], Counter[int]] = defaultdict(Counter)
    terminal: Counter[tuple[str, str]] = Counter()
    for obs in all_events:
        total[obs.line] += 1
        by_station[(obs.line, obs.statn_id)][obs.status] += 1
        if obs.statn_id == obs.dest_statn_id:
            terminal[(obs.line, obs.statn_id)] += 1
        if obs.flag:
            flags[obs.line][obs.flag] += 1
            flagged_station[(obs.line, obs.statn_id, obs.flag)] += 1
    out = [
        "## 3. recptnDt 오류와 늘 이상한 역",
        "",
        "`future`: 받은 시각보다 30초 넘게 미래. `stale`: 10분 넘게 오래됨."
        " `늦음`은 이벤트 시각(recptnDt)부터 수집기가 처음 볼 때까지 걸린 초다.",
        "",
        "| 호선 | 이벤트 | future | stale | 표시 비율 | 늦음: 중앙값 (p10~p90, n) |",
        "|---|---|---|---|---|---|",
    ]
    for line in sorted(total, key=_line_order):
        f = flags[line]
        share = (f["future"] + f["stale"]) / total[line] * 100
        out.append(
            f"| {line} | {total[line]} | {f['future']} | {f['stale']} | {share:.2f}% |"
            f" {_dist(lag.get(line, []))} |"
        )
    out.append("")
    if flagged_station:
        out += ["표시가 많이 달린 역(위에서 10곳):", ""]
        for (line, statn_id, flag), n in flagged_station.most_common(10):
            out.append(f"- {line} {names.get((line, statn_id), statn_id)}: {flag} {n}건")
        out.append("")
    arrive_only, never_arrive = [], []
    for (line, statn_id), statuses in sorted(by_station.items()):
        n = sum(statuses.values())
        if n < _MIN_STATION_EVENTS:
            continue
        label = f"{line} {names.get((line, statn_id), statn_id)}({n}건)"
        is_terminal = terminal[(line, statn_id)] > n / 2
        if statuses[DEPART] == 0 and statuses[ARRIVE] / n >= 0.9 and not is_terminal:
            arrive_only.append(label)
        if statuses[ARRIVE] == 0:
            never_arrive.append(label)
    out.append(
        "도착만 찍히는 역(종착역 제외, 강일 같은 경우): " + (", ".join(arrive_only) or "없음")
    )
    out.append("")
    out.append("도착이 한 번도 안 찍힌 역(천호 같은 경우): " + (", ".join(never_arrive) or "없음"))
    out.append("")
    return out


def _delay(runs: list[Run], b0: B0 | None) -> list[str]:
    out = ["## 4. 실측과 공식 시간표의 차이", ""]
    if b0 is None:
        out += [
            "시간표 캐시가 없어 건너뛰었다. `python -m ml.b0`로 받은 뒤"
            " `--timetable-cache`를 주면 채워진다(2~9호선만).",
            "",
        ]
        return out
    diffs: dict[str, list[float]] = defaultdict(list)
    exact: Counter[str] = Counter()
    for p in departure_points(runs):
        if p.estimated or p.line not in TIMETABLE_LINES:
            continue
        cell = (p.day_type, p.line, p.direction, p.statn_id)
        scheduled = b0.departure(*cell, train_no=p.train_no, near_secs=p.secs)
        if scheduled is None:
            continue
        diffs[p.line].append(p.secs - scheduled)
        exact[p.line] += b0.has_train(*cell, p.train_no)
    out += [
        "실제 출발(상태 2) − 시간표 출발, 초. 양수면 늦은 것이다."
        " 열차 번호가 시간표에 없으면 10분 안의 가장 가까운 시각과 견준다.",
        "",
        "| 호선 | 견준 출발 수 | 열차 번호 일치 | 지연 중앙값 | p10 | p90 | 절대값 평균 |",
        "|---|---|---|---|---|---|---|",
    ]
    for line in sorted(diffs, key=_line_order):
        values = diffs[line]
        s = stats(values)
        mean_abs = sum(abs(v) for v in values) / len(values)
        out.append(
            f"| {line} | {s['n']} | {exact[line] / s['n'] * 100:.1f}% | {s['median']:.0f} |"
            f" {s['p10']:.0f} | {s['p90']:.0f} | {mean_abs:.0f} |"
        )
    if not diffs:
        out.append("견줄 수 있는 출발이 없다(캐시에 그 역·요일의 시간표가 없음).")
    out.append("")
    return out


def _coverage(events: list[Obs], con: sqlite3.Connection) -> list[str]:
    trains: dict[str, set] = defaultdict(set)
    count: Counter[str] = Counter()
    hours: dict[str, Counter[int]] = defaultdict(Counter)
    for obs in events:
        trains[obs.line].add((obs.service_date, obs.train_no))
        count[obs.line] += 1
        hours[obs.line][obs.secs // 3600 % 24] += 1
    codes: dict[str, Counter[str]] = defaultdict(Counter)
    for line, code, n in con.execute(
        "SELECT line, COALESCE(code, '?'), COUNT(*) FROM raw_snapshots GROUP BY 1, 2"
    ):
        codes[line][code] = n
    out = [
        "## 5. 노선별 관측과 시간대별 결측",
        "",
        "| 호선 | 열차(운행일×번호) | 이벤트 | 응답 코드별 횟수 | 이벤트가 있는 시간대 |"
        " 사이에 빈 시간대 |",
        "|---|---|---|---|---|---|",
    ]
    for line in sorted(set(count) | set(codes), key=_line_order):
        seen = sorted(hours[line])
        # 05시부터 다음 날 00시까지를 운행 순서로 놓고, 처음과 마지막 사이에 빈 시간을 찾는다.
        order = [(h - 5) % 24 for h in seen]
        gaps = (
            [f"{(k + 5) % 24}시" for k in range(min(order), max(order)) if k not in order]
            if order
            else []
        )
        code_text = ", ".join(f"{c} {n}" for c, n in sorted(codes[line].items())) or "–"
        span = f"{(min(order) + 5) % 24}시~{(max(order) + 5) % 24}시" if order else "–"
        out.append(
            f"| {line} | {len(trains[line])} | {count[line]} | {code_text} | {span} |"
            f" {', '.join(gaps) or '없음'} |"
        )
    missing = [line for line in LINES if line not in count]
    out.append("")
    out.append("이벤트가 하나도 없는 노선: " + (", ".join(missing) or "없음"))
    out.append("")
    return out


def analyze(db_path: str | Path, *, b0: B0 | None = None) -> str:
    all_events = load_events(db_path, include_flagged=True)
    clean = [obs for obs in all_events if obs.flag is None]
    runs = build_runs(clean)
    names = station_names(db_path)
    dates = sorted({obs.service_date for obs in all_events})
    con = _open_ro(db_path)
    try:
        lag = _lag_by_line(con)
        coverage = _coverage(clean, con)
    finally:
        con.close()
    head = ["# 수집 데이터 분석(설계 §3)", ""]
    if not all_events:
        head += ["이벤트가 없다. 수집기를 먼저 돌린다.", ""]
    else:
        head += [
            f"- 운행일: {dates[0].isoformat()}~{dates[-1].isoformat()} ({len(dates)}일)",
            f"- 이벤트 {len(all_events)}건(이상값 표시 {len(all_events) - len(clean)}건),"
            f" 열차 운행 {len(runs)}건",
            "- 2~5번 표는 이상값 표시가 달린 이벤트를 뺀 것이다(3번만 포함).",
            "",
        ]
    sections = _stability(runs) + _times(runs) + _errors(all_events, names, lag) + _delay(runs, b0)
    return "\n".join(head + sections + coverage)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="수집 데이터 분석(설계 §3)")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--out", default="ml_data/analysis.md")
    parser.add_argument("--timetable-cache", default="", help="B0 시간표 캐시(python -m ml.b0)")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    args = parser.parse_args(argv)
    if not Path(args.db).is_file():
        print(f"수집 DB가 없습니다: {args.db}", file=sys.stderr)
        return 2
    b0 = None
    if args.timetable_cache:
        codes = station_codes(args.db, StationIndex.load(args.stations_db))
        b0 = B0(TimetableCache(args.timetable_cache), codes)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(analyze(args.db, b0=b0), encoding="utf-8")
    print(f"분석 보고서: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
