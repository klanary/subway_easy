"""RAPTOR 경로 검색(경로 설계 2026-11-18 §5.2). 손으로 만든 작은 운행표로 확인한다."""

from datetime import datetime

from app.core.clock import KST
from app.services.raptor import search
from app.services.trips import TripsData

STATIONS = ["A", "B", "C", "D"]
DAY = 86_400  # 운행일 다음 날 0시


def hm(h: int, m: int, s: int = 0) -> int:
    return h * 3600 + m * 60 + s


def trip(no, times, *, express=0):
    """times: [(도착, 출발), ...] 운행일 0시부터 초."""
    return [no, express, "끝", 0, 0, [x for pair in times for x in pair]]


def pattern(line, stops, trips, direction="하행"):
    return {"line": line, "direction": direction, "stops": [STATIONS.index(s) for s in stops],
            "trips": trips}  # fmt: skip


def data(*patterns) -> TripsData:
    return TripsData.from_artifact(
        {
            "kind": "trips-server",
            "version": 1,
            "built_on": "2026-11-18",
            "stations": STATIONS,
            "day_types": {"1": list(patterns), "2": [], "3": []},
        }  # fmt: skip
    )


def at(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=KST)


def hhmm(t: datetime) -> str:
    return t.strftime("%m-%d %H:%M")


DIRECT = pattern("1호선", ["A", "B", "C"], [
    trip("101", [(hm(8, 0), hm(8, 0)), (hm(8, 20), hm(8, 21)), (hm(8, 40), hm(8, 40))]),
])  # fmt: skip
TO_B = pattern("2호선", ["A", "B"], [trip("201", [(hm(8, 5), hm(8, 5)), (hm(8, 10), hm(8, 10))])])
B_TO_C = pattern(
    "3호선", ["B", "C"], [trip("301", [(hm(8, 10), hm(8, 10)), (hm(8, 30), hm(8, 30))])]
)


def test_direct_trip():
    (journey,) = search(data(DIRECT), "A", "C", at("2026-11-18T07:55"))
    assert journey.transfers == 0 and hhmm(journey.arrives_at) == "11-18 08:40"
    (ride,) = journey.rides
    assert ride.train_no == "101" and ride.stops == ["A", "B", "C"] and ride.line == "1호선"
    assert hhmm(ride.departs_at) == "11-18 08:00"


def test_pareto_keeps_faster_transfer_and_direct():
    found = search(data(DIRECT, TO_B, B_TO_C), "A", "C", at("2026-11-18T07:55"))
    assert [(j.transfers, hhmm(j.arrives_at)) for j in found] == [
        (0, "11-18 08:40"),
        (1, "11-18 08:30"),
    ]
    assert [r.line for r in found[1].rides] == ["2호선", "3호선"]


def test_more_transfers_not_faster_are_dropped():
    c_to_d = pattern(
        "4호선", ["B", "D"], [trip("401", [(hm(8, 11), hm(8, 11)), (hm(8, 15), hm(8, 15))])]
    )
    d_to_c = pattern(
        "5호선", ["D", "C"], [trip("501", [(hm(8, 16), hm(8, 16)), (hm(8, 35), hm(8, 35))])]
    )
    found = search(data(DIRECT, TO_B, B_TO_C, c_to_d, d_to_c), "A", "C", at("2026-11-18T07:55"))
    assert [j.transfers for j in found] == [0, 1]


def test_transfer_same_second_is_allowed():
    # 2호선이 B에 08:10:00에 닿고 3호선이 B를 08:10:00에 떠난다(걷는 시간 0).
    found = search(data(TO_B, B_TO_C), "A", "C", at("2026-11-18T08:00"))
    assert [hhmm(j.arrives_at) for j in found] == ["11-18 08:30"]


def test_express_skipping_destination_is_not_used():
    express = pattern("9호선", ["A", "C"], [
        trip("9501", [(hm(8, 0), hm(8, 0)), (hm(8, 10), hm(8, 10))], express=1),
    ])  # fmt: skip
    local = pattern("9호선", ["A", "B", "C"], [
        trip("9001", [(hm(8, 2), hm(8, 2)), (hm(8, 12), hm(8, 12)), (hm(8, 20), hm(8, 20))]),
    ])  # fmt: skip
    (journey,) = search(data(express, local), "A", "B", at("2026-11-18T07:55"))
    assert [r.train_no for r in journey.rides] == ["9001"]
    assert hhmm(journey.arrives_at) == "11-18 08:12"


def test_after_midnight_trip_is_reachable():
    late = pattern("1호선", ["A", "B"], [
        trip("199", [(DAY + hm(0, 20), DAY + hm(0, 20)), (DAY + hm(0, 30), DAY + hm(0, 30))]),
    ])  # fmt: skip
    (journey,) = search(data(late), "A", "B", at("2026-11-18T23:50"))
    assert hhmm(journey.rides[0].departs_at) == "11-19 00:20"


def test_after_last_train_uses_next_service_day():
    (journey,) = search(data(DIRECT), "A", "C", at("2026-11-18T23:59"))
    assert hhmm(journey.rides[0].departs_at) == "11-19 08:00"


def test_before_5am_considers_previous_service_day():
    late = pattern("1호선", ["A", "B"], [
        trip("199", [(DAY + hm(0, 30), DAY + hm(0, 30)), (DAY + hm(0, 40), DAY + hm(0, 40))]),
    ])  # fmt: skip
    (journey,) = search(data(late), "A", "B", at("2026-11-19T00:10"))
    assert hhmm(journey.rides[0].departs_at) == "11-19 00:30"


def test_unknown_station_returns_empty():
    assert search(data(DIRECT), "A", "없음", at("2026-11-18T07:55")) == []
    assert search(data(DIRECT), "A", "A", at("2026-11-18T07:55")) == []


def test_at_most_five_results():
    assert len(search(data(DIRECT, TO_B, B_TO_C), "A", "C", at("2026-11-18T07:55"))) <= 5


def test_backtrack_uses_previous_round_record_of_boarding_stop():
    # 1라운드: A→B 느린 직행(08:30 도착). 2라운드에 C를 거쳐 B에 더 일찍(08:12) 닿지만,
    # 같은 2라운드에 B에서 D로 가는 열차는 1라운드 도착(08:30)으로 탄 것이다.
    slow = pattern("1호선", ["A", "B"], [trip("1", [(hm(8, 0), hm(8, 0)), (hm(8, 30), hm(8, 30))])])
    a_c = pattern("2호선", ["A", "C"], [trip("2", [(hm(8, 1), hm(8, 1)), (hm(8, 5), hm(8, 5))])])
    c_b = pattern("3호선", ["C", "B"], [trip("3", [(hm(8, 6), hm(8, 6)), (hm(8, 12), hm(8, 12))])])
    b_d = pattern(
        "4호선", ["B", "D"], [trip("4", [(hm(8, 31), hm(8, 31)), (hm(8, 40), hm(8, 40))])]
    )
    found = search(data(slow, a_c, c_b, b_d), "A", "D", at("2026-11-18T07:55"))
    for journey in found:
        assert journey.rides[0].board_station == "A"
        for before, after in zip(journey.rides, journey.rides[1:], strict=False):
            assert before.alight_station == after.board_station
            assert before.arrives_at <= after.departs_at
