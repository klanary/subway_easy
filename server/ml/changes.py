"""시간표 변경 감지: 하루치 관측을 기준 실측 시간표(B1)와 견줘 바뀐 단위를 찾는다.

단위(scope)는 (요일 구분, 호선, 방향, 1시간대)다. 신호 다섯 가지 가운데 하나라도 기준을
넘으면 그 날 그 단위는 "의심"이고, 다음 같은 요일 구분의 날도 같은 모양이면 "확정"이다
(지연은 날마다 다르고, 바뀐 시간표는 날마다 같다).

기준값은 지연 분포를 모르는 채 정한 초안이다(계획 Task 9에서 자료로 맞춘다).
"""

from collections import defaultdict
from dataclasses import dataclass

from ml.b1 import Departure, Entry, build_b1_from_points
from ml.scopes import Scope, band_of


@dataclass(frozen=True)
class Thresholds:
    late_sec: int = 120  # 이보다 크게 어긋나면 "다른 시각"
    shifted: float = 0.30  # 같은 번호끼리 어긋난 비율
    moved: float = 0.30  # 번호 없이 시각만 짝지었을 때 짝 없는 비율
    numbers: float = 0.20  # 사라지거나 새로 생긴 번호의 비율
    tph: float = 0.20  # 시간당 출발 수의 변화
    tph_min_trains: int = 2  # 그리고 역당 이만큼 이상 차이
    express: float = 0.15  # 급행 비율의 차
    agree_max: float = 0.50  # 두 날이 "서로 맞다"고 볼 어긋남의 한도
    min_ref: int = 20  # 기준 쪽 출발이 이보다 적은 단위는 판단하지 않는다
    min_coverage: int = 96  # 시간대의 정상 응답 수(기대 120회의 80%)
    streak: int = 3  # 서로 안 맞아도 이만큼 연속 의심이면 확정
    give_up: int = 4  # 이만큼 판단 못 하면 의심을 내려놓는다


@dataclass(frozen=True)
class ScopeStats:
    scope: Scope
    n_ref: int
    n_obs: int
    n_stations: int
    shifted: float | None
    numbers: float | None
    moved: float
    tph_ratio: float
    express_delta: float


def cells_of(artifact: dict) -> dict[str, list[Entry]]:
    """B1 결과물의 cells를 Entry로 읽는다."""
    return {key: [Entry(**e) for e in entries] for key, entries in artifact["cells"].items()}


def _unmatched(ref: list[int], obs: list[int], late_sec: int) -> tuple[list[int], list[int]]:
    """시각순으로 훑으며 late_sec 안의 것끼리 짝짓고, 짝 없는 것을 돌려준다."""
    lone_ref: list[int] = []
    lone_obs: list[int] = []
    i = j = 0
    while i < len(ref) and j < len(obs):
        if abs(ref[i] - obs[j]) <= late_sec:
            i += 1
            j += 1
        elif ref[i] < obs[j]:
            lone_ref.append(ref[i])
            i += 1
        else:
            lone_obs.append(obs[j])
            j += 1
    return lone_ref + ref[i:], lone_obs + obs[j:]


def scope_stats(
    cells: dict[str, list[Entry]],
    obs: list[Departure],
    *,
    mode: str,
    th: Thresholds = Thresholds(),
) -> dict[Scope, ScopeStats]:
    """한 호선·한 날의 출발(`obs`)을 기준표(`cells`)와 견준 단위별 수치.

    시간대는 기준 쪽은 기준 시각, 관측 쪽은 관측 시각으로 정한다.
    기준 쪽 출발이 `th.min_ref` 미만인 단위는 결과에 없다.
    """
    by_number = mode == "train_no"
    obs_by_cell: dict[tuple, list[Departure]] = defaultdict(list)
    for p in obs:
        obs_by_cell[(p.day_type, p.line, p.direction, p.statn_id)].append(p)
    if not obs_by_cell:
        return {}
    day_types = {key[0] for key in obs_by_cell}
    lines = {key[1] for key in obs_by_cell}

    count: dict[Scope, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    stations: dict[Scope, set[str]] = defaultdict(set)
    ref_cells: dict[tuple, list[Entry]] = {}
    for key, entries in cells.items():
        day_type, line, direction, statn_id = key.split("|")
        if int(day_type) in day_types and line in lines:
            ref_cells[(int(day_type), line, int(direction), statn_id)] = entries

    for cell in set(ref_cells) | set(obs_by_cell):
        day_type, line, direction, statn_id = cell
        ref = sorted(ref_cells.get(cell, []), key=lambda e: e.secs)
        seen = sorted(obs_by_cell.get(cell, []), key=lambda p: p.secs)

        def scope_at(secs: int, _cell=cell) -> Scope:
            return (_cell[0], _cell[1], _cell[2], band_of(secs))

        for e in ref:
            c = count[scope_at(e.secs)]
            c["n_ref"] += 1
            c["ref_express"] += e.express > 0
            stations[scope_at(e.secs)].add(statn_id)
        for p in seen:
            c = count[scope_at(p.secs)]
            c["n_obs"] += 1
            c["obs_express"] += p.express > 0

        if by_number:
            ref_no = {e.train_no: e for e in ref if e.train_no is not None}
            obs_no: dict[str, Departure] = {}
            for p in seen:
                obs_no.setdefault(p.train_no, p)
            for number, e in ref_no.items():
                c = count[scope_at(e.secs)]
                c["numbered_ref"] += 1
                p = obs_no.get(number)
                if p is None:
                    c["gone"] += 1
                else:
                    c["pairs"] += 1
                    c["late"] += abs(p.secs - e.secs) > th.late_sec
            for number, p in obs_no.items():
                if number not in ref_no:
                    count[scope_at(p.secs)]["new"] += 1

        lone_ref, lone_obs = _unmatched([e.secs for e in ref], [p.secs for p in seen], th.late_sec)
        for secs in lone_ref:
            count[scope_at(secs)]["lone"] += 1
        for secs in lone_obs:
            count[scope_at(secs)]["lone"] += 1

    out: dict[Scope, ScopeStats] = {}
    for scope, c in count.items():
        n_ref, n_obs = int(c["n_ref"]), int(c["n_obs"])
        if n_ref < th.min_ref:
            continue
        number_base = c["numbered_ref"] + c["new"]
        out[scope] = ScopeStats(
            scope=scope,
            n_ref=n_ref,
            n_obs=n_obs,
            n_stations=len(stations[scope]),
            shifted=c["late"] / c["pairs"] if by_number and c["pairs"] else None,
            numbers=(c["gone"] + c["new"]) / number_base if by_number and number_base else None,
            moved=c["lone"] / (n_ref + n_obs),
            tph_ratio=n_obs / n_ref,
            express_delta=(c["obs_express"] / n_obs if n_obs else 0.0) - c["ref_express"] / n_ref,
        )
    return out


def _tph_over(stats: ScopeStats, th: Thresholds) -> bool:
    diff = abs(stats.n_obs - stats.n_ref)
    # 비율 비교는 정수로 한다(0.2 × 30 같은 값이 부동소수 오차를 타지 않게).
    return (
        diff * 100 >= round(th.tph * 100) * stats.n_ref
        and diff >= th.tph_min_trains * stats.n_stations
    )


def triggers(stats: ScopeStats, th: Thresholds = Thresholds()) -> tuple[str, ...]:
    """기준을 넘은 신호 이름(순서: shifted, numbers, moved, tph, express)."""
    names: list[str] = []
    if stats.shifted is not None and stats.shifted >= th.shifted:
        names.append("shifted")
    if stats.numbers is not None and stats.numbers >= th.numbers:
        names.append("numbers")
    if stats.moved >= th.moved:
        names.append("moved")
    if _tph_over(stats, th):
        names.append("tph")
    if abs(stats.express_delta) >= th.express - 1e-9:
        names.append("express")
    return tuple(names)


def kind_of(names: tuple[str, ...]) -> str:
    """변경의 종류: frequency(배차), pattern(급행 비율), renumber(번호만), retime(시각)."""
    if "tph" in names:
        return "frequency"
    if "express" in names:
        return "pattern"
    if "numbers" in names and "moved" not in names:
        return "renumber"
    return "retime"


def agree(
    first_day: list[Departure],
    today: list[Departure],
    *,
    mode: str,
    th: Thresholds = Thresholds(),
) -> dict[Scope, bool]:
    """첫 의심일 하루치를 기준표로 삼아 오늘과 견준다. 단위별로 "서로 맞는가"."""
    if not first_day:
        return {}
    lines = {p.line for p in first_day}
    reference = build_b1_from_points(first_day, modes={line: mode for line in lines}, min_days=1)
    result: dict[Scope, bool] = {}
    for scope, stats in scope_stats(cells_of(reference), today, mode=mode, th=th).items():
        result[scope] = (
            (stats.shifted is None or stats.shifted < th.agree_max)
            and stats.moved < th.agree_max
            and (stats.numbers is None or stats.numbers < th.numbers)
            and not _tph_over(stats, th)
            and abs(stats.express_delta) < th.express - 1e-9
        )
    return result
