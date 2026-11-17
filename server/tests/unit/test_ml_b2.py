import json
from datetime import date

from ml.b2 import SegmentModel, build_b2, main
from ml.dataset import build_runs
from tests.ml_helpers import trip, weekdays, write_db

H8 = 8 * 3600
HOPS = (60, 90, 120)  # A→B, B→C, C→D


def _model(n_days: int = 6, **kw) -> SegmentModel:
    events = [o for d in weekdays(n_days) for o in trip(d, H8, hop=HOPS, dwell=30, **kw)]
    return SegmentModel(build_b2(build_runs(events)))


def _eta(model: SegmentModel, statn: str, status: int, secs: int, target: str, **kw):
    return model.eta("9호선", 0, statn, status, secs, target, day_type=1, **kw)


def test_eta_adds_segment_medians_from_an_arrival():
    model = _model()
    assert _eta(model, "A", 1, H8, "C") == H8 + 150
    assert _eta(model, "A", 1, H8, "D") == H8 + 270
    assert _eta(model, "B", 1, H8, "B") == H8


def test_eta_from_a_departure_removes_the_dwell():
    model = _model()
    # A를 H8+30에 떠났다 = A에 H8에 도착했다.
    assert _eta(model, "A", 2, H8 + 30, "C") == H8 + 150


def test_eta_from_entering_and_approaching_uses_learned_lead():
    model = _model(statuses=(0, 1, 2, 3))
    assert _eta(model, "B", 0, H8 + 45, "C") == H8 + 150  # 진입은 도착 15초 전
    assert _eta(model, "B", 3, H8, "C") == H8 + 150  # 전역출발은 도착 60초 전


def test_segment_needs_five_samples():
    assert _eta(_model(n_days=4), "A", 1, H8, "B") is None
    assert _eta(_model(n_days=5), "A", 1, H8, "B") == H8 + 60


def test_hop_falls_back_from_hour_to_day_type_to_any_day():
    events = [o for d in weekdays(6) for o in trip(d, H8, hop=HOPS)]
    events += trip(date(2026, 11, 7), H8, hop=HOPS)  # 토요일 하루
    model = SegmentModel(build_b2(build_runs(events)))
    assert model.hop("9호선", 0, "A", "B", express=0, day_type=1, hour=8) == 60
    assert model.hop("9호선", 0, "A", "B", express=0, day_type=1, hour=18) == 60  # 시간대 없음
    assert model.hop("9호선", 0, "A", "B", express=0, day_type=2, hour=8) == 60  # 토요일 표본 1개
    assert model.hop("9호선", 0, "B", "A", express=0, day_type=1, hour=8) is None


def test_hour_specific_median_wins_when_it_has_enough_samples():
    events = [o for d in weekdays(6) for o in trip(d, H8, hop=60)]
    events += [o for d in weekdays(6) for o in trip(d, 18 * 3600, hop=80, train_no="200")]
    model = SegmentModel(build_b2(build_runs(events)))
    assert model.hop("9호선", 0, "A", "B", express=0, day_type=1, hour=8) == 60
    assert model.hop("9호선", 0, "A", "B", express=0, day_type=1, hour=18) == 80
    assert model.hop("9호선", 0, "A", "B", express=0, day_type=1, hour=12) == 70


def test_too_long_or_backward_segments_are_dropped():
    events = [o for d in weekdays(6) for o in trip(d, H8, stations=("A", "B"), hop=1201)]
    assert build_b2(build_runs(events))["hops"] == {}
    assert _eta(_model(), "A", 1, H8, "없는역") is None
    assert _eta(_model(), "D", 1, H8, "A") is None  # 거꾸로는 갈 수 없다


def test_express_and_local_are_learned_apart():
    events = [o for d in weekdays(6) for o in trip(d, H8, hop=HOPS)]
    events += [
        o
        for d in weekdays(6)
        for o in trip(d, H8 + 600, stations=("A", "D"), hop=150, express=1, train_no="E1")
    ]
    model = SegmentModel(build_b2(build_runs(events)))
    assert model.eta("9호선", 0, "A", 1, H8, "D", express=1, day_type=1) == H8 + 150
    assert model.eta("9호선", 0, "A", 1, H8, "D", express=0, day_type=1) == H8 + 270
    assert model.path("9호선", 0, "A", "D", express=1) == ["A", "D"]
    assert model.path("9호선", 0, "A", "D", express=0) == ["A", "B", "C", "D"]


def test_missing_station_in_the_middle_still_links_its_neighbours():
    # B의 도착이 늘 빠지는 노선(5호선 천호 같은 경우): A→C 구간으로 배운다.
    events = []
    for d in weekdays(6):
        events += [o for o in trip(d, H8, hop=HOPS) if not (o.statn_id == "B" and o.status == 1)]
    model = SegmentModel(build_b2(build_runs(events)))
    assert _eta(model, "A", 1, H8, "C") == H8 + 150
    assert _eta(model, "A", 1, H8, "B") is None


def test_outlier_check():
    model = _model()
    assert model.is_outlier("9호선", 0, "A", "B", 60, express=0) is False
    assert model.is_outlier("9호선", 0, "A", "B", 23, express=0) is True  # 0.4배 미만
    assert model.is_outlier("9호선", 0, "A", "B", 211, express=0) is True  # 2.5배 + 60초 초과
    assert model.is_outlier("9호선", 0, "A", "B", 210, express=0) is False
    assert model.is_outlier("9호선", 0, "A", "없는역", 999, express=0) is False


def test_artifact_has_stats_and_metadata():
    events = [
        o for i, d in enumerate(weekdays(6)) for o in trip(d, H8 + 10 * i, hop=60 + i, dwell=30)
    ]
    art = build_b2(build_runs(events))
    assert (art["kind"], art["version"], art["n_dates"]) == ("b2", 1, 6)
    stat = art["hops"]["9호선|0|A|B|0"]
    assert stat == {"median": 62.5, "p10": 60.5, "p90": 64.5, "n": 6}
    assert art["dwell"]["9호선|A"]["median"] == 30
    assert build_b2([])["hops"] == {}


def test_cli_writes_artifact(tmp_path, capsys):
    events = [o for d in weekdays(6) for o in trip(d, H8)]
    db = write_db(tmp_path / "c.sqlite", events)
    out = tmp_path / "segment_times.json"
    assert main(["--db", str(db), "--out", str(out)]) == 0
    assert len(json.loads(out.read_text(encoding="utf-8"))["hops"]) > 0
    assert main(["--db", str(tmp_path / "없음.sqlite"), "--out", str(out)]) == 2


def test_b2_by_line_merges_lines(tmp_path):
    from ml.b2 import build_b2_by_line

    days = weekdays(6)
    events = []
    for day in days:
        events += trip(day, 8 * 3600, line="9호선", train_no="901")
        events += trip(day, 9 * 3600, line="5호선", train_no="501", stations=("P", "Q", "R"))
    db = write_db(tmp_path / "c.sqlite", events)
    merged = build_b2_by_line(db, date_from=days[0], date_to=days[-1])
    lines = {key.split("|")[0] for key in merged["hops"]}
    assert lines == {"9호선", "5호선"}
    whole = build_b2(build_runs(events))
    assert merged["hops"] == whole["hops"] and merged["hops_hour"] == whole["hops_hour"]
    assert merged["kind"] == "b2" and merged["n_dates"] == 6
