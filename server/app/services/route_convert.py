"""카카오 대중교통 경로를 서버 응답 형식(스펙 6.2)으로 바꾼다 (스펙 6.6, api-verification.md §3)."""

import hashlib
import json
import logging

from app.core.metrics import Metrics
from app.models.schemas import FirstBoarding, Leg, Route, Step
from app.services.normalize import normalize_line, normalize_station_name
from app.services.station_index import StationIndex

logger = logging.getLogger("app.routes")

MAX_ROUTES = 5
_MODES = {"SUBWAY": "SUBWAY", "BUS": "BUS", "WALKING": "WALK"}


def _path(step: dict) -> list[tuple[float, float]]:
    """카카오 좌표는 [x(경도), y(위도)]다. 앱은 (위도, 경도)를 쓴다."""
    return [(y, x) for x, y in (step.get("path") or {}).get("points") or []]


def bus_route_name(vehicles: list[dict]) -> str:
    """같은 구간을 가는 버스가 여럿이면 "5515 외 2대"처럼 첫 번호만 보인다."""
    names = [v["name"] for v in vehicles if v.get("name")]
    if not names:
        return "버스"
    return names[0] if len(names) == 1 else f"{names[0]} 외 {len(names) - 1}대"


def make_subway_step(
    *,
    duration_sec: int,
    distance_m: int,
    guidance: str,
    path: list[tuple[float, float]],
    line_raw: str,
    stops: list[str],
    index: StationIndex,
    metrics: Metrics,
) -> Step:
    line_name = normalize_line(line_raw)

    def match(name: str) -> str | None:
        station = index.resolve(name, line_name)
        if station is None:
            metrics.unmatched_station_names.add(name)
            logger.warning("역 매칭 실패: %s (%s)", name, line_name)
            return None
        return station.id

    return Step(
        mode="SUBWAY",
        duration_sec=duration_sec,
        distance_m=distance_m,
        guidance=guidance,
        path=path,
        line_name=line_name,
        stops=stops,
        board_station_id=match(stops[0]) if stops else None,
        alight_station_id=match(stops[-1]) if stops else None,
    )


def build_first_boarding(steps: list[Step], index: StationIndex) -> FirstBoarding | None:
    """첫 지하철 탑승 정보(스펙 6.6). 지하철 단계가 없으면 None."""
    for i, step in enumerate(steps):
        if step.mode != "SUBWAY":
            continue
        before = steps[:i]
        stops = step.stops or []
        return FirstBoarding(
            station_id=step.board_station_id,
            line_name=step.line_name or "",
            towards=normalize_station_name(stops[1]).id if len(stops) > 1 else None,
            sec_to_boarding=sum(s.duration_sec for s in before),
            all_walk_before_boarding=all(s.mode == "WALK" for s in before),
        )
    return None


def route_type(steps: list[Step]) -> str:
    modes = {s.mode for s in steps} - {"WALK"}
    if not modes:
        return "WALK"
    if modes == {"SUBWAY"}:
        return "SUBWAY"
    if modes == {"BUS"}:
        return "BUS"
    return "MIXED"


def route_id(legs: list[Leg]) -> str:
    key = [
        [s.mode, s.line_name, s.route_name, s.duration_sec, s.stops]
        for leg in legs
        for s in leg.steps
    ]
    digest = hashlib.sha1(json.dumps(key, ensure_ascii=False).encode()).hexdigest()
    return "r_" + digest[:8]


def _step(raw: dict, index: StationIndex, metrics: Metrics) -> Step:
    p = raw["properties"]
    mode = _MODES.get(p.get("type"), "WALK")
    common = {
        "duration_sec": int(p.get("time") or 0),
        "distance_m": int(p.get("distance") or 0),
        "guidance": p.get("guidance") or "",
        "path": _path(raw),
    }
    stops = [s["name"] for s in p.get("stops") or [] if s.get("name")]
    vehicles = p.get("vehicles") or []
    if mode == "SUBWAY":
        line_raw = vehicles[0]["name"] if vehicles else ""
        return make_subway_step(
            **common, line_raw=line_raw, stops=stops, index=index, metrics=metrics
        )
    if mode == "BUS":
        return Step(mode="BUS", **common, route_name=bus_route_name(vehicles), stops=stops)
    # 환승 도보에도 stops가 오지만 스펙상 도보 단계에는 정차역을 두지 않는다.
    return Step(mode="WALK", **common)


def subway_only(routes: list[Route]) -> list[Route]:
    """버스가 들어간 경로를 뺀다(도보와 지하철만, 재설계 스펙 5.3)."""
    return [r for r in routes if r.type in ("SUBWAY", "WALK")]


def rank(routes: list[Route], limit: int = MAX_ROUTES) -> list[Route]:
    """빠른 순, 같으면 환승 적은 순으로 `limit`개."""
    return sorted(routes, key=lambda r: (r.total_sec, r.transfers))[:limit]


VIA_TOP = 3  # 경유 구간마다 조합에 쓰는 경로 수(3 × 3 = 9개 조합)


def combine_via(first: list[Route], second: list[Route], limit: int = MAX_ROUTES) -> list[Route]:
    """출발→경유, 경유→도착 경로를 이어 붙인다. 경유역에서 내리는 것은 환승으로 세지 않는다."""
    combined = []
    for a in first[:VIA_TOP]:
        for b in second[:VIA_TOP]:
            legs = [*a.legs, *b.legs]
            steps = [s for leg in legs for s in leg.steps]
            fares = (a.fare, b.fare)
            combined.append(
                Route(
                    id=route_id(legs),
                    type=route_type(steps),
                    total_sec=a.total_sec + b.total_sec,
                    distance_m=a.distance_m + b.distance_m,
                    transfers=a.transfers + b.transfers,
                    fare=None if None in fares else sum(fares),
                    legs=legs,
                    first_boarding=a.first_boarding or b.first_boarding,
                )
            )
    return rank(combined, limit)


def convert_routes(
    raw: dict, index: StationIndex, metrics: Metrics, limit: int | None = MAX_ROUTES
) -> list[Route]:
    routes = []
    for raw_route in (raw.get("routes") or [])[:limit]:
        p = raw_route["properties"]
        steps = [_step(s, index, metrics) for s in raw_route.get("steps") or []]
        legs = [Leg(steps=steps)]
        routes.append(
            Route(
                id=route_id(legs),
                type=route_type(steps),
                total_sec=int(p.get("totalTime") or 0),
                distance_m=int(p.get("totalDistance") or 0),
                transfers=int(p.get("transfers") or 0),
                fare=(p.get("fare") or {}).get("value"),
                legs=legs,
                first_boarding=build_first_boarding(steps, index),
            )
        )
    return routes
