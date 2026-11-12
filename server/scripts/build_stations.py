"""서울시 역사마스터 원본으로 역 기준 데이터(stations.sqlite)를 만든다 (스펙 6.5).

사용법 (server/에서):
    python -m scripts.build_stations data/raw/station_master.json data/stations.sqlite

- 입력은 내려받은 CSV나 OpenAPI(subwayStationMaster)의 JSON이다. 열 이름은 둘 다 읽는다.
- 같은 입력이면 같은 결과를 낸다. 생성된 파일도 저장소에 커밋한다.
"""

import csv
import json
import sqlite3
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from app.services.geo import haversine_m
from app.services.normalize import normalize_line, normalize_station_name, to_chosung

# 칸마다 받아들이는 열 이름. 앞은 내려받은 파일, 뒤는 OpenAPI다
# (2026-11-11 확인, api-verification.md §2).
COLUMNS: dict[str, tuple[str, ...]] = {
    "master_id": ("역사_ID", "BLDN_ID"),
    "name": ("역사명", "BLDN_NM"),
    "line": ("호선", "ROUTE"),
    "lat": ("위도", "LAT"),
    "lng": ("경도", "LOT"),
}

# 역 id → 서울시 실시간 API에 넣을 조회 이름.
# 2026-11-12 낮에 전 역(655개)을 실제로 조회해 확인했다(api-verification.md §2).
# 괄호까지 붙인 원래 이름이어야 조회되는 역이 많고, 스펙 6.5의 공식 목록과 다른 곳도 있다
# (대모산입구는 그냥 이름으로 조회된다).
API_NAME_EXCEPTIONS: dict[str, str] = {
    "공릉": "공릉(서울산업대입구)",
    "광나루": "광나루(장신대)",
    "군자": "군자(능동)",
    "굽은다리": "굽은다리(강동구민회관앞)",
    "남한산성입구": "남한산성입구(성남법원,검찰청)",
    "대흥": "대흥(서강대앞)",
    "몽촌토성": "몽촌토성(평화의문)",
    "상도": "상도(중앙대앞)",
    "상월곡": "상월곡(한국과학기술연구원)",
    "새절": "새절(신사)",
    "숭실대입구": "숭실대입구(살피재)",
    "신정": "신정(은행정)",
    "쌍용": "쌍용(나사렛대)",
    "아차산": "아차산(어린이대공원후문)",
    "안암": "안암(고대병원앞)",
    "어린이대공원": "어린이대공원(세종대)",
    "오목교": "오목교(목동운동장앞)",
    "월곡": "월곡(동덕여대)",
    "월드컵경기장": "월드컵경기장(성산)",
    "응암": "응암순환(상선)",
    "이수": "총신대입구(이수)",
    "증산": "증산(명지대앞)",
    "천호": "천호(풍납토성)",
    "총신대입구": "총신대입구(이수)",
    "화랑대": "화랑대(서울여대입구)",
}

# (정규화 이름, 호선) → 따로 나눌 역 id. 이름은 같지만 1km 넘게 떨어진 역이다.
SEPARATE_STATIONS: dict[tuple[str, str], str] = {("양평", "경의중앙선"): "양평(경의중앙선)"}

# (원본 역명, 원본 호선) → (고친 역명, 고친 호선). 원본이 실제와 다른 행만 적는다.
# - 경원선의 용산~청량리 구간 역에는 1호선이 아니라 경의중앙선이 선다.
# - GTX-A의 "운정"은 경의중앙선 운정과 3.6km 떨어진 다른 역(운정중앙)이다.
ROW_FIXES: dict[tuple[str, str], tuple[str, str]] = {
    **{
        (name, "경원선"): (name, "경의중앙선")
        for name in (
            "이촌(국립중앙박물관)",
            "서빙고",
            "한남",
            "옥수",
            "응봉",
            "왕십리(성동구청)",
            "청량리(서울시립대입구)",
        )
    },
    ("운정", "수도권 광역급행철도"): ("운정중앙", "수도권 광역급행철도"),
}

MAX_SAME_STATION_M = 1_000


class BuildError(Exception):
    pass


@dataclass(frozen=True)
class MasterRow:
    master_id: str
    name: str
    line: str  # 정규화된 호선
    lat: float
    lng: float


@dataclass(frozen=True)
class StationRow:
    id: str
    display_name: str
    lat: float
    lng: float
    chosung: str
    api_name: str


@dataclass(frozen=True)
class StationLineRow:
    station_id: str
    line_name: str
    master_id: str


@dataclass
class BuildResult:
    stations: list[StationRow]
    lines: list[StationLineRow]
    aliases: list[tuple[str, str]]


def _to_rows(records: list[dict]) -> tuple[list[MasterRow], int]:
    """열 이름을 맞춰 행으로 바꾼다. 좌표가 빈 행은 건너뛰고 그 수를 함께 돌려준다."""
    if not records:
        return [], 0
    header = list(records[0].keys())
    names: dict[str, str] = {}
    for field, candidates in COLUMNS.items():
        found = next((c for c in candidates if c in records[0]), None)
        if found is None:
            raise BuildError(f"{field} 열을 찾지 못했어요. 원본의 열: {header}")
        names[field] = found

    rows: list[MasterRow] = []
    skipped = 0
    for record in records:
        lat, lng = str(record[names["lat"]]).strip(), str(record[names["lng"]]).strip()
        if not lat or not lng:
            skipped += 1
            continue
        name = " ".join(str(record[names["name"]]).split())
        raw_line = " ".join(str(record[names["line"]]).split())
        name, raw_line = ROW_FIXES.get((name, raw_line), (name, raw_line))
        rows.append(
            MasterRow(
                master_id=str(record[names["master_id"]]).strip(),
                name=name,
                line=normalize_line(raw_line),
                lat=float(lat),
                lng=float(lng),
            )
        )
    return rows, skipped


def read_master_csv(path: Path) -> tuple[list[MasterRow], int]:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError:
        text = path.read_text(encoding="cp949")
    return _to_rows(list(csv.DictReader(text.splitlines())))


def read_master_json(path: Path) -> tuple[list[MasterRow], int]:
    """OpenAPI 응답 그대로({"subwayStationMaster": {"row": [...]}})이거나 행 목록만 있는 JSON."""
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict):
        data = data.get("subwayStationMaster", data).get("row", [])
    return _to_rows(data)


def read_master(path: Path) -> tuple[list[MasterRow], int]:
    return read_master_json(path) if path.suffix.lower() == ".json" else read_master_csv(path)


def build(
    rows: list[MasterRow],
    separate: dict[tuple[str, str], str] = SEPARATE_STATIONS,
    api_names: dict[str, str] = API_NAME_EXCEPTIONS,
) -> BuildResult:
    groups: dict[str, list[MasterRow]] = defaultdict(list)
    base_names: dict[str, str] = {}
    candidate_aliases: list[tuple[str, str]] = []
    for row in rows:
        normalized = normalize_station_name(row.name)
        station_id = separate.get((normalized.id, row.line), normalized.id)
        groups[station_id].append(row)
        base_names.setdefault(station_id, normalized.id)
        candidate_aliases.extend((alias, station_id) for alias in normalized.aliases)

    stations: list[StationRow] = []
    lines: set[StationLineRow] = set()
    for station_id, members in groups.items():
        for i, a in enumerate(members):
            for b in members[i + 1 :]:
                if haversine_m(a.lat, a.lng, b.lat, b.lng) > MAX_SAME_STATION_M:
                    raise BuildError(
                        f"'{station_id}'의 {a.line}과 {b.line}이 1km 넘게 떨어져 있어요. "
                        "SEPARATE_STATIONS에 더해 다른 역으로 나누세요."
                    )
        base = base_names[station_id]
        stations.append(
            StationRow(
                id=station_id,
                display_name=members[0].name,
                lat=round(sum(m.lat for m in members) / len(members), 6),
                lng=round(sum(m.lng for m in members) / len(members), 6),
                chosung=to_chosung(base),
                api_name=api_names.get(station_id, base),
            )
        )
        lines.update(StationLineRow(station_id, m.line, m.master_id) for m in members)

    # 별칭이 어떤 역의 id와 같으면 만들지 않는다(id가 우선). 같은 별칭은 먼저 나온 것이 이긴다.
    aliases: dict[str, str] = {}
    for alias, station_id in candidate_aliases:
        if alias not in groups:
            aliases.setdefault(alias, station_id)

    return BuildResult(
        stations=sorted(stations, key=lambda s: s.id),
        lines=sorted(lines, key=lambda r: (r.station_id, r.line_name, r.master_id)),
        aliases=sorted(aliases.items()),
    )


SCHEMA = """
CREATE TABLE stations (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, lat REAL NOT NULL,
                       lng REAL NOT NULL, chosung TEXT NOT NULL, api_name TEXT NOT NULL);
CREATE TABLE station_lines (station_id TEXT NOT NULL, line_name TEXT NOT NULL,
                            master_id TEXT NOT NULL,
                            PRIMARY KEY (station_id, line_name, master_id));
CREATE TABLE aliases (alias TEXT PRIMARY KEY, station_id TEXT NOT NULL);
"""


def write_sqlite(result: BuildResult, out: Path) -> None:
    out.unlink(missing_ok=True)
    con = sqlite3.connect(out)
    try:
        con.executescript(SCHEMA)
        con.executemany(
            "INSERT INTO stations VALUES (?, ?, ?, ?, ?, ?)",
            [(s.id, s.display_name, s.lat, s.lng, s.chosung, s.api_name) for s in result.stations],
        )
        con.executemany(
            "INSERT INTO station_lines VALUES (?, ?, ?)",
            [(r.station_id, r.line_name, r.master_id) for r in result.lines],
        )
        con.executemany("INSERT INTO aliases VALUES (?, ?)", result.aliases)
        con.commit()
    finally:
        con.close()


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print("사용법: python -m scripts.build_stations <원본 CSV 또는 JSON> <stations.sqlite>")
        return 2
    rows, skipped = read_master(Path(args[0]))
    try:
        result = build(rows)
    except BuildError as e:
        print(f"실패: {e}")
        return 1
    write_sqlite(result, Path(args[1]))
    print(
        f"역 {len(result.stations)}개, 호선 연결 {len(result.lines)}개, "
        f"별칭 {len(result.aliases)}개, 좌표가 없어 건너뛴 행 {skipped}개"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
