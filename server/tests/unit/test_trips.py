"""서버 운행표 색인(경로 설계 2026-11-18 §5.2): 패턴별 정수 배열, 정차에서 지나는 패턴 찾기."""

from array import array

import pytest

from app.services.trips import TripsData


def artifact() -> dict:
    # 정차 번호: 0 마포, 1 여의나루, 2 여의도, 3 샛강
    return {
        "kind": "trips-server", "version": 1, "built_on": "2026-11-18",
        "stations": ["마포", "여의나루", "여의도", "샛강"],
        "day_types": {
            "1": [
                {"line": "5호선", "direction": "상행", "stops": [0, 1, 2],
                 "trips": [["5101", 0, "방화", 0, 0, [100, 130, 220, 250, 340, 370]],
                           ["5103", 0, "방화", 0, 0, [700, 730, 820, 850, 940, 970]]]},
                {"line": "9호선", "direction": "하행", "stops": [2, 3],
                 "trips": [[None, 1, "중앙보훈병원", 1, 1, [400, 420, 500, 520]]]},
            ],
            "2": [], "3": [],
        },
    }  # fmt: skip


@pytest.fixture
def day():
    return TripsData.from_artifact(artifact()).day(1)


def test_from_artifact_indexes_patterns_by_stop(day):
    stop = day.stop_index("여의도")
    assert stop == 2 and day.station_id(stop) == "여의도"
    found = sorted((day.patterns[p].line, pos) for p, pos in day.patterns_at(stop))
    assert found == [("5호선", 2), ("9호선", 0)]
    assert day.stop_index("없는역") is None


def test_earliest_trip_binary_search(day):
    five = next(p for p in day.patterns if p.line == "5호선")
    assert five.earliest_trip(0, 0) == 0
    assert five.earliest_trip(0, 130) == 0  # 같은 초에 떠나는 열차도 탈 수 있다
    assert five.earliest_trip(0, 131) == 1
    assert five.earliest_trip(0, 800) is None
    assert five.arr(1, 2) == 940 and five.dep(1, 2) == 970


def test_trip_meta(day):
    nine = next(p for p in day.patterns if p.line == "9호선")
    meta = nine.trip_meta(0)
    assert meta.train_no is None and meta.express and meta.source == "measured"
    assert meta.estimated and meta.dest == "중앙보훈병원"


def test_times_are_compact_arrays(day):
    assert isinstance(day.patterns[0].times, array) and day.patterns[0].times.typecode == "i"


def test_missing_day_type_is_empty():
    data = TripsData.from_artifact(artifact())
    assert data.day(3).patterns == [] and str(data.built_on) == "2026-11-18"


def test_wrong_kind_is_rejected():
    with pytest.raises(ValueError):
        TripsData.from_artifact({**artifact(), "kind": "b1-server"})
