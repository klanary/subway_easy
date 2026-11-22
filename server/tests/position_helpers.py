"""열차 위치 도착 계산 테스트가 함께 쓰는 준비물(1호선 상행 남영 → 서울 → 시청 → 종각)."""

from datetime import datetime, timedelta

import httpx

from app.core.clock import KST
from app.services.measured import MeasuredData
from app.services.trips import TripsData

NOW = datetime(2026, 11, 3, 8, 0, 0, tzinfo=KST)  # conftest 시계(화요일)
STAMP = "%Y-%m-%d %H:%M:%S"


def install_position_data(services) -> None:
    """구간 시간(남영→서울 300초, 서울→시청 100초)과 운행 패턴을 서버에 넣는다."""
    services.measured.replace(
        MeasuredData.from_artifacts(
            {
                "kind": "b1-server", "version": 1, "as_of": "2026-11-02", "built_at": "",
                "modes": {}, "dests": [], "cells": {}, "scopes": {},
                "stations": {
                    "1호선|S0": {"station_id": "남영", "name": "남영"},
                    "1호선|S1": {"station_id": "서울", "name": "서울"},
                    "1호선|S2": {"station_id": "시청", "name": "시청"},
                },
            },
            {"kind": "b2-server", "version": 1,
             "hops": {"1호선|0|S0|S1|0": 300, "1호선|0|S1|S2|0": 100}},
        )
    )  # fmt: skip
    services.measured.replace_trips(
        TripsData.from_artifact(
            {
                "kind": "trips-server", "version": 1, "built_on": "2026-11-02",
                "stations": ["남영", "서울", "시청", "종각"],
                "day_types": {"1": [{"line": "1호선", "direction": "상행", "stops": [0, 1, 2, 3],
                                     "trips": [["0145", False, "광운대", 0, False, [0] * 8]]}]},
            }
        )
    )  # fmt: skip


def arrival(subway="1001", updn="상행", barvl="0", msg="[1]번째 전역 (서울)", no="0145"):
    return {
        "subwayId": subway, "updnLine": updn, "trainLineNm": "광운대행 - 종각방면",
        "statnNm": "시청", "btrainNo": no, "bstatnNm": "광운대",
        "recptnDt": (NOW - timedelta(seconds=5)).strftime(STAMP),
        "arvlMsg2": msg, "arvlMsg3": "서울", "barvlDt": barvl,
        "lstcarAt": "0", "btrainSttus": "일반",
    }  # fmt: skip


def answer_arrivals(respx_mock, items):
    body = {"errorMessage": {"status": 200, "code": "INFO-000"}, "realtimeArrivalList": items}
    return respx_mock.get(url__regex=r".*/realtimeStationArrival/.*").mock(
        return_value=httpx.Response(200, json=body)
    )


def answer_positions(respx_mock, rows=None, *, status=200, code="INFO-000"):
    if status != 200:
        response = httpx.Response(status)
    elif code == "INFO-200":
        response = httpx.Response(200, json={"status": 500, "code": "INFO-200"})
    else:
        body = {"errorMessage": {"status": 200, "code": code}, "realtimePositionList": rows or []}
        response = httpx.Response(200, json=body)
    return respx_mock.get(url__regex=r".*/realtimePosition/.*").mock(return_value=response)


def position_row(no="0145", station="서울", ago=10):
    return {
        "statnNm": station, "trainNo": no, "updnLine": "0", "trainSttus": "1", "directAt": "0",
        "statnTnm": "광운대", "recptnDt": (NOW - timedelta(seconds=ago)).strftime(STAMP),
    }  # fmt: skip


def timetable_body(times: list[str]) -> dict:
    """시청 1호선 상행 공식 시간표 응답(서울시 형식). times는 "HH:MM:SS" 출발 시각."""
    rows = [
        {"TRAIN_NO": f"K{i}", "ARRIVETIME": t, "LEFTTIME": t, "DESTSTATION": "1907",
         "SUBWAYENAME": "광운대", "EXPRESS_YN": "G"}
        for i, t in enumerate(times)
    ]  # fmt: skip
    return {
        "SearchSTNTimeTableByIDService": {
            "list_total_count": len(rows), "RESULT": {"CODE": "INFO-000"}, "row": rows,
        }
    }  # fmt: skip
