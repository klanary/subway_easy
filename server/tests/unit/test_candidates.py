"""구간별 후보 열차(열차 고르기 설계 2026-11-18 §5.1)."""

from app.services.candidates import candidates
from app.services.raptor import search
from tests.unit.test_raptor import DAY, at, data, hhmm, hm, pattern, trip


def local(no, a, b, c):
    """A-B-C를 a, b, c(운행일 초)에 서는 일반 열차."""
    return trip(no, [(a, a), (b, b), (c, c)])


def ride_of(trips, origin, dest, depart):
    (journey,) = search(trips, origin, dest, depart)
    (ride,) = journey.rides
    return ride


def test_includes_chosen_and_excludes_express_skipping_alight():
    locals_ = pattern("9호선", ["A", "B", "C"], [
        local("9001", hm(8, 0), hm(8, 5), hm(8, 9)),
        local("9003", hm(8, 10), hm(8, 15), hm(8, 19)),
        local("9005", hm(8, 20), hm(8, 25), hm(8, 29)),
    ])  # fmt: skip
    express = pattern("9호선", ["A", "C"], [
        trip("9501", [(hm(8, 5), hm(8, 5)), (hm(8, 12), hm(8, 12))], express=1),
    ])  # fmt: skip
    trips = data(locals_, express)
    ride = ride_of(trips, "A", "B", at("2026-11-18T08:08"))
    found = candidates(trips, ride)
    assert [hhmm(c.departs_at) for c in found] == ["11-18 08:00", "11-18 08:10", "11-18 08:20"]
    assert ride.departs_at in [c.departs_at for c in found]
    assert hhmm(found[1].arrives_at) == "11-18 08:15"
    assert [c.train_no for c in found] == ["9001", "9003", "9005"]


def test_window_is_30_min_before_to_2h_after():
    p = pattern("1호선", ["A", "B"], [
        trip(str(i), [(t, t), (t + 300, t + 300)])
        for i, t in enumerate([hm(7, 20), hm(7, 35), hm(8, 0), hm(10, 0), hm(10, 1)])
    ])  # fmt: skip
    trips = data(p)
    ride = ride_of(trips, "A", "B", at("2026-11-18T07:59"))
    found = candidates(trips, ride)
    assert [hhmm(c.departs_at) for c in found] == ["11-18 07:35", "11-18 08:00", "11-18 10:00"]


def test_at_most_40_and_chosen_kept():
    times = [hm(7, 0) + 120 * i for i in range(100)]  # 07:00부터 2분 간격
    p = pattern("1호선", ["A", "B"], [trip(str(i), [(t, t), (t + 60, t + 60)]) for i, t in
                                      enumerate(times)])  # fmt: skip
    trips = data(p)
    ride = ride_of(trips, "A", "B", at("2026-11-18T08:00"))
    found = candidates(trips, ride)
    departs = [c.departs_at for c in found]
    assert len(found) == 40 and ride.departs_at in departs
    assert departs.index(ride.departs_at) == 10  # 앞은 가까운 10대까지


def test_window_crosses_midnight():
    p = pattern("1호선", ["A", "B"], [
        trip("1", [(hm(23, 45), hm(23, 45)), (hm(23, 50), hm(23, 50))]),
        trip("2", [(hm(23, 50), hm(23, 50)), (hm(23, 55), hm(23, 55))]),
        trip("3", [(DAY + hm(0, 10), DAY + hm(0, 10)), (DAY + hm(0, 15), DAY + hm(0, 15))]),
        trip("4", [(DAY + hm(0, 20), DAY + hm(0, 20)), (DAY + hm(0, 25), DAY + hm(0, 25))]),
    ])  # fmt: skip
    trips = data(p)
    ride = ride_of(trips, "A", "B", at("2026-11-18T23:48"))
    assert "11-19 00:20" in [hhmm(c.departs_at) for c in candidates(trips, ride)]
    ride = ride_of(trips, "A", "B", at("2026-11-19T00:05"))
    assert hhmm(ride.departs_at) == "11-19 00:10"
    assert "11-18 23:45" in [hhmm(c.departs_at) for c in candidates(trips, ride)]


def test_merged_continuation_keeps_only_chosen():
    t1 = trip("2001", [(hm(8, 0), hm(8, 0)), (hm(8, 5), hm(8, 5))])
    first = pattern("2호선", ["A", "B"], [t1], direction="내선")
    t2 = trip("2101", [(hm(8, 6), hm(8, 6)), (hm(8, 10), hm(8, 10))])
    second = pattern("2호선", ["B", "C"], [t2], direction="내선")
    trips = data(first, second)
    ride = ride_of(trips, "A", "C", at("2026-11-18T07:55"))
    (only,) = candidates(trips, ride)
    assert only.departs_at == ride.departs_at and only.train_no == "2001"
    assert only.arrives_at == ride.arrives_at


def test_candidate_carries_source():
    p = pattern("1호선", ["A", "B"], [trip("1", [(hm(8, 0), hm(8, 0)), (hm(8, 5), hm(8, 5))])])
    trips = data(p)
    ride = ride_of(trips, "A", "B", at("2026-11-18T07:55"))
    assert candidates(trips, ride)[0].source == "official"
