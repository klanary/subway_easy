import pytest

from ml.b1 import build_b1_from_points, departure_points
from ml.changes import Thresholds, agree, cells_of, kind_of, scope_stats, triggers
from ml.dataset import build_runs
from tests.ml_helpers import trip, weekdays

H6 = 6 * 3600
DAYS = weekdays(6)
SCOPE = (1, "9호선", 0, 6)
TH = Thresholds()


def day_events(day, *, n=10, gap=360, shift=0, first_no=9001, express=frozenset(), start=H6):
    """그 날 06:00부터 gap초 간격으로 n대. 4역 노선이라 열차마다 출발역은 3곳이다."""
    events = []
    for i in range(n):
        events += trip(
            day, start + gap * i + shift, train_no=str(first_no + i), express=int(i in express)
        )
    return events


def day_points(day, **kw):
    return departure_points(build_runs(day_events(day, **kw)))


def reference(**kw):
    points = [p for day in DAYS[:5] for p in day_points(day, **kw)]
    return cells_of(build_b1_from_points(points))


def stats_for(obs, *, mode="train_no", ref=None):
    return scope_stats(ref or reference(), obs, mode=mode, th=TH)


def test_thresholds_defaults():
    assert (TH.late_sec, TH.shifted, TH.moved, TH.numbers, TH.tph) == (120, 0.30, 0.30, 0.20, 0.20)
    assert (TH.tph_min_trains, TH.express, TH.agree_max, TH.min_ref) == (2, 0.15, 0.50, 20)
    assert (TH.min_coverage, TH.streak, TH.give_up) == (96, 3, 4)


def test_same_timetable_has_no_triggers():
    stats = stats_for(day_points(DAYS[5]))[SCOPE]
    assert (stats.n_ref, stats.n_obs, stats.n_stations) == (30, 30, 3)
    assert (stats.shifted, stats.numbers, stats.moved) == (0.0, 0.0, 0.0)
    assert triggers(stats, TH) == ()


def test_every_train_three_minutes_late_is_a_retime():
    stats = stats_for(day_points(DAYS[5], shift=180))[SCOPE]
    assert (stats.shifted, stats.moved, stats.numbers) == (1.0, 1.0, 0.0)
    names = triggers(stats, TH)
    assert names == ("shifted", "moved")
    assert kind_of(names) == "retime"


def test_one_minute_late_is_within_tolerance():
    assert triggers(stats_for(day_points(DAYS[5], shift=60))[SCOPE], TH) == ()


def test_renumbering_keeps_times_but_changes_numbers():
    stats = stats_for(day_points(DAYS[5], first_no=8001))[SCOPE]
    assert (stats.numbers, stats.moved, stats.shifted) == (1.0, 0.0, None)
    assert triggers(stats, TH) == ("numbers",)
    assert kind_of(("numbers",)) == "renumber"


def test_more_trains_per_hour_is_a_frequency_change():
    stats = stats_for(day_points(DAYS[5], n=15, gap=240))[SCOPE]
    names = triggers(stats, TH)
    assert "tph" in names and kind_of(names) == "frequency"
    assert stats.tph_ratio == pytest.approx(44 / 30)  # 마지막 열차의 셋째 역은 7시대로 넘어간다
    small = stats_for(day_points(DAYS[5], n=11, gap=327))[SCOPE]
    assert small.tph_ratio == pytest.approx(1.1)
    assert "tph" not in triggers(small, TH)


def test_tph_needs_both_ratio_and_absolute_difference():
    from dataclasses import replace

    base = stats_for(day_points(DAYS[5]))[SCOPE]
    # 30 → 36: 정확히 +20%이고 역당 2대 차이 → 넘음(부동소수 오차를 타지 않는다).
    assert "tph" in triggers(replace(base, n_obs=36, tph_ratio=1.2), TH)
    assert "tph" not in triggers(replace(base, n_obs=35, tph_ratio=35 / 30), TH)
    # 비율은 넘지만 역당 2대(6대)가 안 되는 작은 단위.
    tiny = replace(base, n_ref=20, n_obs=25, n_stations=3, tph_ratio=1.25)
    assert "tph" not in triggers(tiny, TH)


def test_express_share_change_is_a_pattern_change():
    ref = reference(express=frozenset(range(5)))
    stats = scope_stats(ref, day_points(DAYS[5], express=frozenset(range(7))), mode="train_no")[
        SCOPE
    ]
    assert stats.express_delta == pytest.approx(0.2)
    names = triggers(stats, TH)
    assert names == ("express",) and kind_of(names) == "pattern"


def test_slots_mode_uses_times_only():
    stats = stats_for(day_points(DAYS[5], shift=180), mode="slots")[SCOPE]
    assert stats.shifted is None and stats.numbers is None
    assert triggers(stats, TH) == ("moved",)


def test_scope_with_too_few_reference_departures_is_left_out():
    ref_points = [
        p for day in DAYS[:5] for p in day_points(day) + day_points(day, n=3, start=10 * 3600,
                                                                     first_no=7001)
    ]  # fmt: skip
    ref = cells_of(build_b1_from_points(ref_points))
    obs = day_points(DAYS[5]) + day_points(DAYS[5], n=3, start=10 * 3600, first_no=7001)
    stats = scope_stats(ref, obs, mode="train_no", th=TH)
    assert SCOPE in stats
    assert (1, "9호선", 0, 10) not in stats  # 3대 × 3역 = 9 < 20


def test_missing_trains_count_as_number_and_time_changes():
    stats = stats_for(day_points(DAYS[5], n=5))[SCOPE]  # 10대 가운데 앞 5대만 다님
    assert stats.numbers == 0.5 and stats.moved == pytest.approx(15 / 45)
    assert stats.shifted == 0.0
    assert "tph" in triggers(stats, TH)


def test_agree_compares_two_days_directly():
    late = day_points(DAYS[4], shift=180)
    same = day_points(DAYS[5], shift=180)
    other = day_points(DAYS[5], shift=400)
    assert agree(late, same, mode="train_no", th=TH)[SCOPE] is True
    assert agree(late, other, mode="train_no", th=TH)[SCOPE] is False
    assert (
        agree(late, day_points(DAYS[5], shift=180, first_no=8001), mode="train_no", th=TH)[SCOPE]
        is False
    )  # 번호가 다르면 같은 시간표가 아니다
    assert agree(late, same, mode="slots", th=TH)[SCOPE] is True
    assert agree([], same, mode="train_no", th=TH) == {}
