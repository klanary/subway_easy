"""열차 위치 + 구간 시간으로 이 역 도착 시각 계산(설계 2026-11-22 §6)."""

from datetime import datetime, timedelta

from app.core.clock import KST
from app.services.measured import MeasuredData
from app.services.position_eta import estimate_arrival
from app.services.position_service import TrainPosition
from app.services.trips import TripsData

T = datetime(2026, 11, 18, 8, 0, 0, tzinfo=KST)  # 수요일(평일)
STATIONS = ["A", "B", "C", "D", "X"]


def trips(*patterns) -> TripsData:
    """patterns: (정차 id 목록, [(행선지, 급행)]) — 시각은 계산기가 쓰지 않아 0으로 둔다."""
    day = []
    for stops, metas in patterns:
        idx = [STATIONS.index(s) for s in stops]
        rows = [[None, express, dest, 0, False, [0] * (2 * len(idx))] for dest, express in metas]
        day.append({"line": "2호선", "direction": "내선", "stops": idx, "trips": rows})
    return TripsData.from_artifact(
        {
            "kind": "trips-server",
            "version": 1,
            "built_on": "2026-11-18",
            "stations": STATIONS,
            "day_types": {"1": day},
        }
    )


def measured(hops: dict[str, float]) -> MeasuredData:
    return MeasuredData.from_artifacts(
        {
            "kind": "b1-server",
            "version": 1,
            "as_of": "2026-11-17",
            "built_at": "",
            "modes": {},
            "dests": [],
            "cells": {},
            "stations": {f"2호선|s{s}": {"station_id": s, "name": s} for s in STATIONS},
            "scopes": {},
        },
        {"kind": "b2-server", "version": 1, "hops": hops},
    )


LOCAL = {"2호선|0|sA|sB|0": 90, "2호선|0|sB|sC|0": 100, "2호선|0|sC|sD|0": 110}
LINE = trips((["A", "B", "C", "D"], [("D", False)]))


def pos(station="A", status=1, dest="D", express=False, seen=T) -> TrainPosition:
    return TrainPosition("2호선", "내선", "2001", station, status, seen, dest, express)


def eta(p, target, *, t=LINE, m=None, now=T):
    return estimate_arrival(p, target, trips=t, measured=m or measured(LOCAL), now=now)


def test_sums_hops_from_current_station():
    assert eta(pos(), "C") == T + timedelta(seconds=190)


def test_status_leads():
    assert eta(pos(status=0), "C") == T + timedelta(seconds=20 + 190)
    assert eta(pos(status=3), "C") == T + timedelta(seconds=90 + 190)
    assert eta(pos(status=2), "C") == T + timedelta(seconds=190)


def test_train_at_target():
    assert eta(pos(station="C", status=0), "C") == T + timedelta(seconds=20)
    assert eta(pos(station="C", status=2), "C") is None


def test_missing_hop_is_none():
    hops = {k: v for k, v in LOCAL.items() if "sB|sC" not in k}
    assert eta(pos(), "C", m=measured(hops)) is None


def test_train_ending_before_target_is_none():
    t = trips((["A", "B"], [("B", False)]), (["A", "B", "C", "D"], [("D", False)]))
    assert eta(pos(dest="B"), "C", t=t) is None


def test_train_ending_at_target_is_none():
    t = trips((["A", "B", "C"], [("C", False)]))
    assert eta(pos(dest="C"), "C", t=t) is None


def test_ambiguous_branch_is_none():
    t = trips((["A", "B", "C"], [("D", False)]), (["A", "X", "C"], [("D", False)]))
    assert eta(pos(), "C", t=t) is None


def test_express_uses_express_pattern_and_hops():
    t = trips((["A", "B", "C", "D"], [("D", False)]), (["A", "C", "D"], [("D", True)]))
    m = measured({**LOCAL, "2호선|0|sA|sC|1": 150})
    assert eta(pos(express=True), "C", t=t, m=m) == T + timedelta(seconds=150)


def test_never_earlier_than_now():
    assert eta(pos(seen=T - timedelta(seconds=500)), "C", now=T) == T
