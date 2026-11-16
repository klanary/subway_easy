"""B1·B2 평가(설계 §4): 날짜로 나눈 시험 세트에서 예측과 실측의 차이를 본다.

사용법 (server/에서):
    python -m ml.evaluate --db ml_data/collector.sqlite --out ml_data/eval.md
    python -m ml.evaluate --timetable-cache ml_data/timetable_cache   # B0(공식 시간표)와 비교

- 앞 날짜로 만들고 뒤 날짜로 평가한다(기본: 뒤 20%, 최소 1일).
- 지표: MAE(초), P90(초), ±60초·±120초 안에 든 비율. 호선·요일 구분별로도 낸다.
- B0와의 비교는 "둘 다 답한 같은 관측"에서만 한다.
- `--same-day-cutoff HH:MM`은 날짜가 하루뿐일 때 파이프라인이 도는지 보는 점검용이다
  (날짜 분할이 아니다).
"""

import argparse
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

from app.services.station_index import StationIndex
from ml.b0 import B0, TimetableCache, station_codes
from ml.b1 import (
    MIN_DAYS,
    MIN_DAYS_AFTER_CHANGE,
    SLOT_WIDTH_SEC,
    MeasuredTimetable,
    build_b1,
    departure_points,
)
from ml.b2 import MIN_N, SegmentModel, _percentile, build_b2
from ml.changes import ChangeState, CorruptState
from ml.dataset import ARRIVE, Run, build_runs, load_events
from ml.scopes import band_of

TEST_SHARE = 0.2
NEAREST_MAX_GAP_SEC = 600  # 열차 번호 없이 맞출 때, 이보다 먼 예측 시각은 "예측 없음"으로 친다
MAX_STOPS = 10  # B2: 몇 정거장 앞까지 평가할지
DAY_TYPE_NAMES = {1: "평일", 2: "토요일", 3: "휴일"}
PHASE_NAMES = {"stable": "안정", "post_change": "변경 직후", "incident": "하루짜리 이상"}


class NotEnoughDates(Exception):
    """날짜로 나눌 수 없다(운행일이 2일 미만이거나 학습할 날짜가 남지 않음)."""


def split_by_date(runs: list[Run], *, test_days: int | None = None) -> tuple[list[Run], list[Run]]:
    """(앞 날짜들, 뒤 `test_days`일). 한 날짜는 한쪽에만 들어간다."""
    dates = sorted({run.service_date for run in runs})
    if test_days is None:
        test_days = max(1, round(len(dates) * TEST_SHARE))
    if len(dates) < 2 or not 0 < test_days < len(dates):
        raise NotEnoughDates(f"운행일 {len(dates)}일, 시험 {test_days}일로는 날짜로 나눌 수 없다")
    first_test = dates[-test_days]
    train = [run for run in runs if run.service_date < first_test]
    test = [run for run in runs if run.service_date >= first_test]
    return train, test


def split_same_day(runs: list[Run], cutoff_secs: int) -> tuple[list[Run], list[Run]]:
    """점검용: 운행을 시작한 시각이 cutoff 앞이면 학습, 뒤면 시험."""
    train = [run for run in runs if run.visits[0].first < cutoff_secs]
    test = [run for run in runs if run.visits[0].first >= cutoff_secs]
    return train, test


def metrics(errors: list[float]) -> dict | None:
    """오차(예측 − 실측, 초)의 절대값 기준 지표. 비어 있으면 None."""
    if not errors:
        return None
    ordered = sorted(abs(e) for e in errors)
    n = len(ordered)
    return {
        "n": n,
        "mae": round(sum(ordered) / n, 1),
        "p90": round(_percentile(ordered, 0.9), 1),
        "within60": sum(e <= 60 for e in ordered) / n,
        "within120": sum(e <= 120 for e in ordered) / n,
    }


@dataclass
class Scores:
    total: int = 0  # 실측 관측 수
    model: list[float] = field(default_factory=list)  # 모델이 답한 관측의 오차
    paired_model: list[float] = field(default_factory=list)  # 모델·B0가 둘 다 답한 관측
    paired_b0: list[float] = field(default_factory=list)

    def add(self, model_error: float | None, b0_error: float | None) -> None:
        self.total += 1
        if model_error is not None:
            self.model.append(model_error)
        if model_error is not None and b0_error is not None:
            self.paired_model.append(model_error)
            self.paired_b0.append(b0_error)

    def summary(self) -> dict:
        return {
            "total": self.total,
            "coverage": len(self.model) / self.total if self.total else 0.0,
            "model": metrics(self.model),
            "model_paired": metrics(self.paired_model),
            "b0": metrics(self.paired_b0),
        }


@dataclass
class Report:
    name: str  # "B1 실측 시간표" 같은 제목
    what: str  # 무엇을 맞히는지 한 줄
    overall: Scores = field(default_factory=Scores)
    by_line: dict[str, Scores] = field(default_factory=lambda: defaultdict(Scores))
    by_day_type: dict[int, Scores] = field(default_factory=lambda: defaultdict(Scores))
    # 변경 기록을 줬을 때만 채운다: stable(안정), post_change(변경 직후), incident(하루짜리 이상)
    by_phase: dict[str, Scores] = field(default_factory=dict)

    def add(
        self,
        line: str,
        day_type: int,
        model_error: float | None,
        b0_error: float | None,
        phase: str | None = None,
    ) -> None:
        for scores in (self.overall, self.by_line[line], self.by_day_type[day_type]):
            scores.add(model_error, b0_error)
        if phase is not None:
            self.by_phase.setdefault(phase, Scores()).add(model_error, b0_error)

    def summary(self) -> dict:
        return self.overall.summary()


def evaluate_b1(
    train: list[Run],
    test: list[Run],
    *,
    b0: B0 | None = None,
    min_days: int = MIN_DAYS,
    slot_width: int = SLOT_WIDTH_SEC,
    mode: str = "auto",
    state: ChangeState | None = None,
) -> Report:
    """시험 날짜의 실제 출발을 학습 날짜로 만든 실측 시간표와 견준다.

    `state`(시간표 변경 기록)를 주면 학습 마지막 날까지 확정된 변경만 학습 쪽 B1에 적용하고
    (시험 날짜의 지식은 쓰지 않는다), 관측을 안정·변경 직후·하루짜리 이상으로 나눠 함께 센다.
    전체 숫자는 모든 관측을 그대로 센다(숨기지 않는다).

    출발(상태 2)이 안 찍힌 역은 도착 + 학습 날짜의 호선 정차 시간을 실측으로 쓴다.
    (경의중앙선·수인분당선 등은 출발이 아예 오지 않는다. 같은 값을 양쪽에 더하므로
    결국 도착 시각이 날마다 얼마나 같은지를 재는 것이다.)
    """
    train_dates: dict[int, list[date]] = defaultdict(list)
    for day_type, day in sorted({(run.day_type, run.service_date) for run in train}):
        train_dates[day_type].append(day)
    last_train = max((run.service_date for run in train), default=None)
    all_epochs = state.epochs() if state is not None else {}
    incidents = state.incidents() if state is not None else {}
    known = {scope: day for scope, day in all_epochs.items() if last_train and day <= last_train}
    artifact = build_b1(
        train, min_days=min_days, slot_width=slot_width, mode=mode, epochs=known or None
    )
    table = MeasuredTimetable(artifact)

    def phase_of(p) -> str | None:
        if state is None:
            return None
        scope = (p.day_type, p.line, p.direction, band_of(p.secs))
        if p.service_date in incidents.get(scope, ()):
            return "incident"
        changed_on = all_epochs.get(scope)
        if changed_on is not None and changed_on <= p.service_date:
            learned = sum(day >= changed_on for day in train_dates[p.day_type])
            if learned < MIN_DAYS_AFTER_CHANGE:
                return "post_change"
        return "stable"

    report = Report(
        "B1 실측 시간표",
        "역 출발 시각(초). 오차 = 시간표 시각 − 실제 출발(출발이 안 찍힌 역은 도착 + 정차 중앙값)",
    )
    for p in departure_points(test, dwell=artifact["dwell"]):
        cell = (p.day_type, p.line, p.direction, p.statn_id)
        if artifact["modes"].get(p.line) == "train_no":
            entry = table.entry_for(*cell, p.train_no)
        else:
            entry = table.nearest(*cell, p.secs)
            if entry is not None and abs(entry.secs - p.secs) > NEAREST_MAX_GAP_SEC:
                entry = None
        model_error = entry.secs - p.secs if entry is not None else None
        b0_error = None
        if b0 is not None:
            scheduled = b0.departure(*cell, train_no=p.train_no, near_secs=p.secs)
            b0_error = scheduled - p.secs if scheduled is not None else None
        report.add(p.line, p.day_type, model_error, b0_error, phase_of(p))
    return report


def evaluate_b2(
    train: list[Run],
    test: list[Run],
    *,
    b0: B0 | None = None,
    max_stops: int = MAX_STOPS,
    min_n: int = MIN_N,
) -> Report:
    """시험 날짜의 열차마다, 한 역 도착을 보고 그 뒤 `max_stops`정거장의 도착을 예측한다."""
    model = SegmentModel(build_b2(train, min_n=min_n))
    report = Report(
        "B2 구간 시간", f"하류 역 도착 시각(초, {max_stops}정거장 앞까지). 오차 = 예측 − 실제 도착"
    )
    for run in test:
        stops = [v for v in run.visits if v.arr is not None]
        for i, here in enumerate(stops):
            for there in stops[i + 1 : i + 1 + max_stops]:
                predicted = model.eta(
                    run.line, run.direction, here.statn_id, ARRIVE, here.arr, there.statn_id,
                    express=run.express, day_type=run.day_type,
                )  # fmt: skip
                model_error = predicted - there.arr if predicted is not None else None
                b0_error = None
                if b0 is not None:
                    cell = (run.day_type, run.line, run.direction)
                    a = b0.arrival(*cell, here.statn_id, train_no=run.train_no)
                    b = b0.arrival(*cell, there.statn_id, train_no=run.train_no)
                    if a is not None and b is not None:
                        b0_error = here.arr + (b - a) - there.arr
                report.add(run.line, run.day_type, model_error, b0_error)
    return report


def _fmt(m: dict | None) -> str:
    if m is None:
        return "– | – | – | – | –"
    return (
        f"{m['n']} | {m['mae']:.1f} | {m['p90']:.1f} | "
        f"{m['within60'] * 100:.1f}% | {m['within120'] * 100:.1f}%"
    )


def _table(rows: list[tuple[str, Scores]]) -> list[str]:
    out = [
        "| 구분 | 실측 수 | 예측한 비율 | 모델 n | MAE(초) | P90(초) | ±60초 | ±120초 |"
        " 같은 관측 n | 모델 MAE | B0 MAE | B0 P90 | B0 ±60초 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for label, scores in rows:
        s = scores.summary()
        paired, b0 = s["model_paired"], s["b0"]
        tail = (
            f"{b0['n']} | {paired['mae']:.1f} | {b0['mae']:.1f} | {b0['p90']:.1f} |"
            f" {b0['within60'] * 100:.1f}%"
            if b0 and paired
            else "– | – | – | – | –"
        )
        out.append(
            f"| {label} | {s['total']} | {s['coverage'] * 100:.1f}% | {_fmt(s['model'])} | {tail} |"
        )
    return out


def render(reports: list[Report], *, title: str, note: str) -> str:
    lines = [f"# {title}", "", note, ""]
    for report in reports:
        lines += [f"## {report.name}", "", report.what, ""]
        rows = [("전체", report.overall)]
        rows += [(line, s) for line, s in sorted(report.by_line.items())]
        rows += [
            (DAY_TYPE_NAMES.get(day_type, str(day_type)), s)
            for day_type, s in sorted(report.by_day_type.items())
        ]
        lines += _table(rows) + [""]
        if report.by_phase:
            lines += ["구간별(시간표 변경 기록 기준):", ""]
            lines += _table(
                [(PHASE_NAMES[name], report.by_phase[name]) for name in PHASE_NAMES
                 if name in report.by_phase]
            ) + [""]  # fmt: skip
    lines += [
        "- 오차는 절대값으로 센다. `예측한 비율`은 모델이 답을 낸 관측의 비율이다"
        "(관측 일수·표본 하한에 못 미치면 답하지 않는다).",
        "- `같은 관측 n` 뒤의 칸은 모델과 B0(공식 시간표, 2~9호선)가"
        " 둘 다 답한 관측만 견준 것이다.",
        "",
    ]
    return "\n".join(lines)


def _date_range(runs: list[Run]) -> str:
    dates: list[date] = sorted({run.service_date for run in runs})
    if not dates:
        return "없음"
    return f"{dates[0].isoformat()}~{dates[-1].isoformat()}({len(dates)}일, 운행 {len(runs)}건)"


def _parse_hhmm(value: str) -> int:
    hours, minutes = (int(x) for x in value.split(":"))
    return hours * 3600 + minutes * 60


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="B1·B2 평가(날짜 분할, B0 비교)")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--out", default="ml_data/eval.md")
    parser.add_argument("--test-days", type=int, default=None, help="시험 날짜 수(기본: 뒤 20%%)")
    parser.add_argument("--timetable-cache", default="", help="B0 시간표 캐시(python -m ml.b0)")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    parser.add_argument("--min-days", type=int, default=MIN_DAYS)
    parser.add_argument("--min-n", type=int, default=MIN_N)
    parser.add_argument("--max-stops", type=int, default=MAX_STOPS)
    parser.add_argument("--same-day-cutoff", default="", help="점검용: HH:MM 앞은 학습, 뒤는 시험")
    parser.add_argument("--changes", default="", help="시간표 변경 상태 파일(change_state.json)")
    args = parser.parse_args(argv)
    if not Path(args.db).is_file():
        print(f"수집 DB가 없습니다: {args.db}", file=sys.stderr)
        return 2
    state = None
    if args.changes:
        try:
            state = ChangeState.load(args.changes)
        except CorruptState as e:
            print(f"변경 상태 파일을 읽을 수 없습니다: {e}", file=sys.stderr)
            return 2
    runs = build_runs(load_events(args.db))
    if args.same_day_cutoff:
        train, test = split_same_day(runs, _parse_hhmm(args.same_day_cutoff))
        how = (
            f"**점검용 실행이며 날짜 분할이 아니다.** 같은 기간 안에서 {args.same_day_cutoff} 앞에"
            " 출발한 운행으로 만들고 뒤의 운행으로 견줬다."
            " 숫자는 파이프라인이 도는지 보는 용도로만 쓴다."
        )
    else:
        try:
            train, test = split_by_date(runs, test_days=args.test_days)
        except NotEnoughDates as e:
            print(
                f"날짜가 모자라 평가할 수 없습니다: {e}."
                " 파이프라인 점검만 하려면 --same-day-cutoff HH:MM",
                file=sys.stderr,
            )
            return 1
        how = "날짜 분할: 앞 날짜로 만들고 뒤 날짜로 견줬다."
    b0 = None
    if args.timetable_cache:
        codes = station_codes(args.db, StationIndex.load(args.stations_db))
        b0 = B0(TimetableCache(args.timetable_cache), codes)
    reports = [
        evaluate_b1(train, test, b0=b0, min_days=args.min_days, state=state),
        evaluate_b2(train, test, b0=b0, max_stops=args.max_stops, min_n=args.min_n),
    ]
    b0_note = (
        f"시간표 캐시 {args.timetable_cache}" if b0 else "비교하지 않음(--timetable-cache 없음)"
    )
    note = (
        f"{how}\n\n- 학습: {_date_range(train)}\n- 시험: {_date_range(test)}\n"
        f"- B1 관측 일수 하한 {args.min_days}일, B2 표본 하한 {args.min_n}개\n"
        f"- B0: {b0_note}"
    )
    if state is not None:
        # 공식 시간표(B0) 비교는 지금 캐시 한 벌로 한다.
        note += (
            f"\n- 변경 기록: {args.changes}. 시험 기간 안에 공식 시간표가 바뀌었으면"
            " 그 전 날짜의 B0 숫자는 믿지 않는다(B0는 지금 받은 시간표 한 벌로 견준다)."
        )
    text = render(reports, title="B1·B2 평가", note=note)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    for report in reports:
        s = report.summary()
        mae = s["model"]["mae"] if s["model"] else None
        print(f"{report.name}: 실측 {s['total']}, 예측한 비율 {s['coverage']:.1%}, MAE {mae}")
    print(f"보고서: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
