"""카카오 경로 변환. 샘플은 2026-11-12 본인 키로 받은 실제 응답이다."""

import pytest

from app.core.metrics import Metrics
from app.models.schemas import Leg, Step
from app.services.route_convert import (
    build_first_boarding,
    bus_route_name,
    combine_via,
    convert_routes,
    make_subway_step,
    rank,
    route_id,
    route_type,
    subway_only,
)
from tests.helpers import load_fixture

SUCCESS = [
    "kakao_route_subway_only.json",
    "kakao_route_transfer.json",
    "kakao_route_bus_subway.json",
]


@pytest.fixture
def fresh_metrics() -> Metrics:
    return Metrics(since=None)


@pytest.fixture(params=SUCCESS)
def converted(request, real_index, fresh_metrics):
    _, body = load_fixture(request.param)
    return convert_routes(body, real_index, fresh_metrics), body


def kakao_summary(body: dict, i: int) -> tuple[int, int, int, int | None]:
    p = body["routes"][i]["properties"]
    return p["totalTime"], p["totalDistance"], p["transfers"], (p.get("fare") or {}).get("value")


def test_routes_present_and_limited(converted):
    assert 1 <= len(converted[0]) <= 5


def test_paths_are_lat_lng_in_korea(converted):
    for r in converted[0]:
        for s in r.legs[0].steps:
            assert s.path
            assert all(33 <= lat <= 39 and 124 <= lng <= 132 for lat, lng in s.path)


def test_step_fields_by_mode(converted):
    for s in (s for r in converted[0] for s in r.legs[0].steps):
        if s.mode == "SUBWAY":
            assert s.line_name and len(s.stops) >= 2 and s.route_name is None
        elif s.mode == "BUS":
            assert s.route_name and s.stops and s.line_name is None
        else:
            assert s.line_name is None and s.stops is None


def test_totals_match_kakao_summary(converted):
    routes, body = converted
    for i, r in enumerate(routes):
        assert (r.total_sec, r.distance_m, r.transfers, r.fare) == kakao_summary(body, i)


def test_real_routes_match_every_subway_station(real_index, fresh_metrics):
    """실제 샘플 세 개의 지하철 정차역이 모두 역 데이터로 풀린다(괄호 이름 포함)."""
    for name in SUCCESS:
        for r in convert_routes(load_fixture(name)[1], real_index, fresh_metrics):
            for s in r.legs[0].steps:
                if s.mode == "SUBWAY":
                    assert s.board_station_id and s.alight_station_id, (name, s.stops)
    assert fresh_metrics.unmatched_station_names == set()


def test_bus_route_name():
    assert bus_route_name([{"name": "421"}]) == "421"
    assert bus_route_name([{"name": "5515"}, {"name": "6515"}, {"name": "5511"}]) == "5515 외 2대"


def step(mode, sec, **kw):
    return Step(mode=mode, duration_sec=sec, distance_m=0, guidance="", path=[], **kw)


def test_first_boarding_rules(real_index):
    walk, bus = step("WALK", 420), step("BUS", 300, route_name="7016", stops=["a", "b"])
    sub = step(
        "SUBWAY",
        600,
        line_name="2호선",
        stops=["홍대입구", "합정", "당산"],
        board_station_id="홍대입구",
        alight_station_id="당산",
    )
    fb = build_first_boarding([walk, sub], real_index)
    got = (fb.station_id, fb.line_name, fb.towards, fb.sec_to_boarding, fb.all_walk_before_boarding)
    assert got == ("홍대입구", "2호선", "합정", 420, True)
    mixed = build_first_boarding([walk, bus, sub], real_index)
    assert (mixed.sec_to_boarding, mixed.all_walk_before_boarding) == (720, False)
    assert build_first_boarding([walk, bus], real_index) is None
    one_stop = sub.model_copy(update={"stops": ["홍대입구"]})
    assert build_first_boarding([one_stop], real_index).towards is None
    to_seoul = sub.model_copy(update={"stops": ["시청", "서울역"]})
    assert build_first_boarding([to_seoul], real_index).towards == "서울"


@pytest.mark.parametrize(
    "modes, expected",
    [
        (["WALK", "SUBWAY"], "SUBWAY"),
        (["BUS"], "BUS"),
        (["BUS", "WALK", "SUBWAY"], "MIXED"),
        (["WALK"], "WALK"),
    ],
)
def test_route_type(modes, expected):
    assert route_type([step(m, 60) for m in modes]) == expected


def test_route_id_stable_and_distinct():
    a, b = [Leg(steps=[step("WALK", 60)])], [Leg(steps=[step("WALK", 61)])]
    assert route_id(a) == route_id(a) != route_id(b) and route_id(a).startswith("r_")


def test_unmatched_station_recorded(real_index, fresh_metrics, caplog):
    s = make_subway_step(
        duration_sec=60,
        distance_m=1000,
        guidance="",
        path=[],
        line_raw="2호선",
        stops=["없는역", "홍대입구"],
        index=real_index,
        metrics=fresh_metrics,
    )
    assert (s.board_station_id, s.alight_station_id) == (None, "홍대입구")
    assert fresh_metrics.unmatched_station_names == {"없는역"} and "없는역" in caplog.text


def test_subway_only_and_rank(real_index, fresh_metrics):
    _, body = load_fixture("kakao_route_subway_only.json")
    routes = convert_routes(body, real_index, fresh_metrics, limit=None)
    assert len(routes) == 15  # 카카오가 준 경로 전부(버스 포함)
    kept = rank(subway_only(routes))
    assert [r.type for r in kept] == ["SUBWAY"] * len(kept) and len(kept) == 4
    assert [(r.total_sec, r.transfers) for r in kept] == sorted(
        (r.total_sec, r.transfers) for r in kept
    )
    assert len(rank(routes * 2)) == 5


def test_combine_via_sums_and_orders(real_index, fresh_metrics):
    _, body = load_fixture("kakao_route_subway_only.json")
    first = rank(subway_only(convert_routes(body, real_index, fresh_metrics, limit=None)))
    _, body = load_fixture("kakao_route_transfer.json")
    second = rank(subway_only(convert_routes(body, real_index, fresh_metrics, limit=None)))
    out = combine_via(first, second)
    assert len(out) == 5  # 3 × 3 = 9개 조합 중 빠른 5개
    assert [r.total_sec for r in out] == sorted(r.total_sec for r in out)
    assert all(len(r.legs) == 2 for r in out)
    best = out[0]
    assert best.total_sec == first[0].total_sec + second[0].total_sec
    assert best.transfers == first[0].transfers + second[0].transfers  # 경유역 하차는 환승 아님
    assert best.first_boarding == first[0].first_boarding
    assert len({r.id for r in out}) == 5


def test_combine_via_empty_side():
    assert combine_via([], []) == []


def test_combine_via_walk_only_first_leg_counts_walk_before_boarding(real_index, fresh_metrics):
    """경유 첫 구간이 걷기뿐이면 탑승 전 시간에 그 걷기를 더한다(리뷰 사소한 문제 1)."""
    _, body = load_fixture("kakao_route_subway_only.json")
    subway = rank(subway_only(convert_routes(body, real_index, fresh_metrics, limit=None)))
    walk = subway[0].model_copy(
        update={"type": "WALK", "first_boarding": None, "total_sec": 300, "transfers": 0}
    )
    out = combine_via([walk], subway[:1])
    fb = out[0].first_boarding
    assert fb.sec_to_boarding == 300 + subway[0].first_boarding.sec_to_boarding
    assert fb.all_walk_before_boarding == subway[0].first_boarding.all_walk_before_boarding
