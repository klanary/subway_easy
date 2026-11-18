"""VM 운행표(설계 2026-11-18 §5.1): 공식 시간표 캐시와 실측 결과물로 열차 운행을 만든다."""

import json
from datetime import date
from pathlib import Path

import pytest

from ml.trips import TRIP_GAP_SEC, build_trips

STATIONS_DB = Path(__file__).parents[2] / "data" / "stations.sqlite"


def row(train_no, arr, dep, *, express="G", dest="방화", inout="1"):
    return {"TRAIN_NO": train_no, "ARRIVETIME": arr, "LEFTTIME": dep, "EXPRESS_YN": express,
            "SUBWAYENAME": dest, "INOUT_TAG": inout}  # fmt: skip


def write_cache(tmp_path: Path, files: dict[str, list[dict]]) -> Path:
    cache = tmp_path / "timetable_cache"
    cache.mkdir()
    for name, rows in files.items():
        (cache / f"{name}.json").write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
    return cache


def entry(secs, train_no=None, dest="문산", express=0):
    return {"secs": secs, "train_no": train_no, "n_days": 3, "dest_name": dest, "express": express}


B1 = {
    "version": 2, "kind": "b1", "as_of": "2026-11-17",
    "modes": {"경의중앙선": "train_no", "신분당선": "slots"},
    "stations": {
        "경의중앙선|K1": {"station_id": "공덕", "name": "공덕"},
        "경의중앙선|K2": {"station_id": "서강대", "name": "서강대"},
        "신분당선|S1": {"station_id": "강남", "name": "강남"},
        "신분당선|S2": {"station_id": "양재", "name": "양재"},
        "신분당선|S3": {"station_id": "양재시민의숲", "name": "양재시민의숲"},
    },
    "cells": {
        "1|경의중앙선|1|K1": [entry(28800, "K123")],
        "1|경의중앙선|1|K2": [entry(29160, "K123")],
        "1|신분당선|1|S1": [entry(25200, dest="광교"), entry(25800, dest="광교")],
    },
}  # fmt: skip
B2 = {
    "version": 1, "kind": "b2",
    "hops": {"신분당선|1|S1|S2|0": {"median": 120, "n": 9},
             "신분당선|1|S2|S3|0": {"median": 150, "n": 9}},
    "dwell": {"경의중앙선|K2": {"median": 40, "n": 9}, "신분당선|S2": {"median": 20, "n": 9}},
}  # fmt: skip


@pytest.fixture
def trips(tmp_path):
    cache = write_cache(
        tmp_path,
        {
            # 5호선 마포(2529)→여의나루(2528)→여의도(2527), 평일 상행. 5101이 세 역을 지난다.
            "2529_1_1": [row("5101", "08:00:00", "08:00:30"), row("5103", "08:10:00", "08:10:30")],
            "2528_1_1": [row("5101", "08:02:00", "08:02:30"), row("5103", "08:12:00", "08:12:30")],
            "2527_1_1": [row("5101", "08:04:00", "08:04:30"), row("5103", "08:14:00", "08:14:30")],
            # 9호선 여의도(4115)→샛강(4116)→노량진(4117)
            # 일반 9001은 셋 다, 급행 9501은 샛강을 건너뜀
            "4115_1_1": [
                row("9001", "08:00:00", "08:00:20"),
                row("9501", "08:01:00", "08:01:20", express="D"),
            ],
            "4116_1_1": [row("9001", "08:01:30", "08:01:50")],
            "4117_1_1": [
                row("9001", "08:03:00", "08:03:20"),
                row("9501", "08:03:30", "08:03:50", express="D"),
            ],
            # 2호선 을지로3가(0203)→을지로4가(0204): 같은 번호 2001이 아침·저녁 두 번(사이 10시간)
            "0203_1_1": [row("2001", "07:00:00", "07:00:30"), row("2001", "17:00:00", "17:00:30")],
            "0204_1_1": [row("2001", "07:02:00", "07:02:30"), row("2001", "17:02:00", "17:02:30")],
            # 자정 넘김
            "0203_1_2": [row("2333", "24:10:00", "24:20:00", inout="2")],
            "0204_1_2": [row("2333", "24:12:00", "24:22:00", inout="2")],
            # 모르는 역 코드
            "9999_1_1": [row("5101", "08:06:00", "08:06:30")],
        },
    )
    return build_trips(cache, STATIONS_DB, B1, B2, built_on=date(2026, 11, 18))


def find(data, line, train_no=None, day="1"):
    out = []
    for pattern in data["day_types"][day]:
        if pattern["line"] != line:
            continue
        names = [data["stations"][i] for i in pattern["stops"]]
        for trip in pattern["trips"]:
            if train_no is None or trip[0] == train_no:
                out.append((names, trip))
    return out


def test_official_rows_group_into_trips_by_train_no(trips):
    ((names, trip),) = find(trips, "5호선", "5101")
    assert names == ["마포", "여의나루", "여의도"]
    assert trip[5] == [28800, 28830, 28920, 28950, 29040, 29070]
    assert trip[1] == 0 and trip[3] == 0 and trip[4] == 0  # 일반, 공식, 어림 아님
    assert trips["kind"] == "trips-server" and trips["built_on"] == "2026-11-18"


def test_express_trip_has_only_its_stops(trips):
    ((names, trip),) = find(trips, "9호선", "9501")
    assert names == ["여의도", "노량진"] and trip[1] == 1
    ((local_names, _),) = find(trips, "9호선", "9001")
    assert local_names == ["여의도", "샛강", "노량진"]


def test_same_train_no_twice_a_day_splits(trips):
    runs = find(trips, "2호선", "2001")
    assert sorted(trip[5][0] for _, trip in runs) == [25200, 61200]
    assert TRIP_GAP_SEC == 1800


def test_after_midnight_seconds_kept(trips):
    ((names, trip),) = find(trips, "2호선", "2333")
    assert trip[5][:2] == [87000, 87600]


def test_measured_train_no_line_builds_trips(trips):
    ((names, trip),) = find(trips, "경의중앙선", "K123")
    assert names == ["공덕", "서강대"]
    assert trip[3] == 1 and trip[4] == 0  # 실측, 어림 아님
    assert trip[5] == [28770, 28800, 29120, 29160]  # 도착 = 출발 - 정차(기본 30, 서강대 40)


def test_slots_line_builds_estimated_trips_from_hops(trips):
    runs = find(trips, "신분당선")
    assert len(runs) == 2
    names, trip = runs[0]
    assert names == ["강남", "양재", "양재시민의숲"]
    assert trip[0] is None and trip[3] == 1 and trip[4] == 1
    # 강남 출발 25200(도착 25170) → 양재 도착 25170+120, 출발 +20 → 시민의숲 도착 +150
    assert trip[5] == [25170, 25200, 25290, 25310, 25440, 25440]


def test_unknown_station_code_is_skipped(trips):
    ((names, _),) = find(trips, "5호선", "5101")
    assert len(names) == 3


def test_overtaking_trips_go_to_separate_patterns(tmp_path):
    cache = write_cache(
        tmp_path,
        {
            "2529_1_1": [row("A", "08:00:00", "08:00:30"), row("B", "08:01:00", "08:01:30")],
            "2528_1_1": [row("A", "08:10:00", "08:10:30"), row("B", "08:05:00", "08:05:30")],
        },
    )
    data = build_trips(cache, STATIONS_DB, None, None, built_on=date(2026, 11, 18))
    patterns = [p for p in data["day_types"]["1"] if p["line"] == "5호선"]
    assert len(patterns) == 2  # B가 A를 앞지르므로 같은 패턴에 둘 수 없다


def test_measured_trip_reaches_its_terminal(tmp_path):
    # B1에는 종착역 출발이 없다(종착역은 반대 방향에만 나온다). 행선지까지 B2 구간으로 이어 붙인다
    # (최종 리뷰 #1: 인천공항2터미널·춘천 같은 종착역으로 가는 경로가 없던 문제).
    b1 = {
        "version": 2, "kind": "b1", "as_of": "2026-11-17", "modes": {"경의중앙선": "train_no"},
        "stations": {
            "경의중앙선|K1": {"station_id": "공덕", "name": "공덕"},
            "경의중앙선|K2": {"station_id": "서강대", "name": "서강대"},
            "경의중앙선|K3": {"station_id": "홍대입구", "name": "홍대입구"},
        },
        "cells": {
            "1|경의중앙선|1|K1": [entry(28800, "K7", dest="홍대입구")],
            "1|경의중앙선|1|K2": [entry(29160, "K7", dest="홍대입구")],
        },
    }  # fmt: skip
    b2 = {"version": 1, "kind": "b2",
          "hops": {"경의중앙선|1|K2|K3|0": {"median": 150, "n": 9}}, "dwell": {}}  # fmt: skip
    data = build_trips(None, STATIONS_DB, b1, b2, built_on=date(2026, 11, 18))
    ((names, trip),) = find(data, "경의중앙선", "K7")
    assert names == ["공덕", "서강대", "홍대입구"]
    assert trip[5][-2:] == [
        29130 + 150,
        29130 + 150,
    ]  # 서강대 도착(출발-30) + 구간 → 종착 도착=출발


def test_terminal_hop_falls_back_to_reverse_direction(tmp_path):
    # 종착역에 들어오는 구간은 관측이 거의 없다(종착 도착이 잘 안 찍힘).
    # 반대 방향 같은 구간 시간을 쓴다.
    b1 = {
        "version": 2, "kind": "b1", "as_of": "2026-11-17", "modes": {"경의중앙선": "train_no"},
        "stations": {
            "경의중앙선|K1": {"station_id": "공덕", "name": "공덕"},
            "경의중앙선|K2": {"station_id": "서강대", "name": "서강대"},
            "경의중앙선|K3": {"station_id": "홍대입구", "name": "홍대입구"},
        },
        "cells": {
            "1|경의중앙선|1|K1": [entry(28800, "K7", dest="홍대입구")],
            "1|경의중앙선|1|K2": [entry(29160, "K7", dest="홍대입구")],
        },
    }  # fmt: skip
    b2 = {"version": 1, "kind": "b2",
          "hops": {"경의중앙선|0|K3|K2|0": {"median": 140, "n": 9}}, "dwell": {}}  # fmt: skip
    data = build_trips(None, STATIONS_DB, b1, b2, built_on=date(2026, 11, 18))
    ((names, trip),) = find(data, "경의중앙선", "K7")
    assert names == ["공덕", "서강대", "홍대입구"] and trip[5][-1] == 29130 + 140


def test_terminal_without_any_hop_uses_distance(tmp_path):
    # 공항철도처럼 종착역이 든 구간이 양방향 모두 관측되지 않으면 직선거리 ÷ 평균 속도로 어림한다.
    b1 = {
        "version": 2, "kind": "b1", "as_of": "2026-11-17", "modes": {"경의중앙선": "train_no"},
        "stations": {
            "경의중앙선|K1": {"station_id": "공덕", "name": "공덕"},
            "경의중앙선|K2": {"station_id": "서강대", "name": "서강대"},
            "경의중앙선|K3": {"station_id": "홍대입구", "name": "홍대입구"},
        },
        "cells": {
            "1|경의중앙선|1|K1": [entry(28800, "K7", dest="홍대입구")],
            "1|경의중앙선|1|K2": [entry(29160, "K7", dest="홍대입구")],
        },
    }  # fmt: skip
    data = build_trips(None, STATIONS_DB, b1, {"version": 1, "kind": "b2", "hops": {}, "dwell": {}},
                       built_on=date(2026, 11, 18))  # fmt: skip
    ((names, trip),) = find(data, "경의중앙선", "K7")
    assert names == ["공덕", "서강대", "홍대입구"]
    assert 29130 + 60 <= trip[5][-1] <= 29130 + 600


def test_same_train_no_an_hour_later_is_another_trip(tmp_path):
    # 정차 사이가 30분 넘게 비면 같은 번호라도 다른 운행이다(역 사이는 길어야 몇 분).
    cache = write_cache(
        tmp_path,
        {
            "2529_1_1": [row("A", "08:00:00", "08:00:30"), row("A", "09:00:00", "09:00:30")],
            "2528_1_1": [row("A", "08:02:00", "08:02:30"), row("A", "09:02:00", "09:02:30")],
        },
    )
    data = build_trips(cache, STATIONS_DB, None, None, built_on=date(2026, 11, 18))
    assert len(find(data, "5호선", "A")) == 2


SLOTS_B1 = {
    "version": 2, "kind": "b1", "as_of": "2026-11-17", "modes": {"신분당선": "slots"},
    "stations": {
        "신분당선|S1": {"station_id": "강남", "name": "강남"},
        "신분당선|S2": {"station_id": "양재", "name": "양재"},
        "신분당선|S3": {"station_id": "양재시민의숲", "name": "양재시민의숲"},
    },
}  # fmt: skip


def slots_b2(**extra):
    hops = {"신분당선|1|S1|S2|0": {"median": 400, "n": 9},  # 출발역 오래 서 있던 것이 섞임
            "신분당선|1|S2|S3|0": {"median": 150, "n": 9}, **extra}  # fmt: skip
    return {"version": 1, "kind": "b2", "hops": hops, "dwell": {"신분당선|S2": {"median": 20}}}


def test_slots_skips_express_departures():
    b1 = {**SLOTS_B1, "cells": {"1|신분당선|1|S1": [entry(25200, dest="양재시민의숲", express=1)]}}
    data = build_trips(None, STATIONS_DB, b1, slots_b2(), built_on=date(2026, 11, 18))
    assert find(data, "신분당선") == []


def test_slots_stops_at_train_destination():
    b1 = {**SLOTS_B1, "cells": {"1|신분당선|1|S1": [entry(25200, dest="양재")]}}
    data = build_trips(None, STATIONS_DB, b1, slots_b2(), built_on=date(2026, 11, 18))
    ((names, _),) = find(data, "신분당선")
    assert names == ["강남", "양재"]


def test_slots_first_hop_uses_reverse_to_avoid_origin_dwell():
    # 반대 방향 양재→강남 130초(양재 정차 20초 포함) → 강남 출발 뒤 110초에 양재 도착.
    b1 = {**SLOTS_B1, "cells": {"1|신분당선|1|S1": [entry(25200, dest="양재시민의숲")]}}
    b2 = slots_b2(**{"신분당선|0|S2|S1|0": {"median": 130, "n": 9}})
    data = build_trips(None, STATIONS_DB, b1, b2, built_on=date(2026, 11, 18))
    ((_, trip),) = find(data, "신분당선")
    assert trip[5][2] == 25200 + 110
