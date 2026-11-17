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
            # 9호선 여의도(4115)→샛강(4116)→노량진(4117): 일반 9001은 셋 다, 급행 9501은 샛강을 건너뜀
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
    assert TRIP_GAP_SEC == 10800


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
