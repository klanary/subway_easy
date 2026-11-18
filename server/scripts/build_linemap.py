"""노선도 SVG에서 역 위치를 뽑아 앱이 쓰는 역 위치표를 만든다(재설계 스펙 8, 2026-11-19 교체).

사용법 (server/에서):
    python -m scripts.build_linemap data/raw/linemap_atlas.svg data/stations.sqlite \
        ../android/app/src/main/assets/linemap

- 원본: 사용자가 그린 "수도권 철도 노선도 아틀라스 에디션"(2026-11-19). 역 이름이 바뀐 곳
  (서해구청, 용인중앙시장)도 이 노선도를 정답으로 본다.
- 이 노선도의 역 표시: 일반역은 노선 위의 작은 원(<circle>), 환승역은 흰 알약이나 흰 원(흰 <path>).
  역 이름은 <text>(여러 줄이면 <tspan>)이고 transform의 이동값이 글자 기준점이다.
- 순서:
  1. 글자 이름을 우리 역 id에 잇는다(NAME_EXCEPTIONS, 같은 이름 역은 동서로 나눔).
  2. 노선 이름 → 선 색: 한 노선에만 있는 역의 글자에서 가장 가까운 선의 색을 센다.
  3. 원은 그 원이 얹힌 선의 색으로 본다(선 위에 없는 원은 장식이라 뺀다).
  4. 역과 표시를 최소 비용으로 짝짓는다. 원은 그 역의 노선 색일 때만, 흰 표시는 어느 역이나
     (환승역이 아니면 벌점). 가까운 것부터 차지하면 글자가 한 칸씩 밀려 이웃 역이 붙는다
     (2026-11-19 확인: 회기·외대앞·신이문, 광운대·월계).
  5. 짝이 없는 역은 글자에서 가장 가까운 그 노선 선 위의 점, 그것도 멀면 글자 기준점.
- 결과는 out_dir/stations.json과 out_dir/linemap.svg(원본 복사)다. 같은 입력이면 같은 결과를 낸다.
"""

import json
import math
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from app.services.station_index import StationIndex

NS = "{http://www.w3.org/2000/svg}"
WHITE = "#FFFFFF"
# 노선도와 우리 역 데이터의 이름이 다른 역(노선도 이름 → 우리 역 id). 2026-11-19 대조.
NAME_EXCEPTIONS: dict[str, str] = {
    "자양": "뚝섬유원지",
    "평택지제": "지제",
    "서해구청": "서구청",  # 인천 서구 → 서해구(2026-07-01)
    "용인중앙시장": "운동장.송담대",  # 2024-01-29 역명 변경. 김량장은 다른 역이다
}
# 우리 데이터에는 두 역이지만 노선도에는 한 역인 곳(서로 같은 자리를 쓴다).
SAME_PLACE: dict[str, str] = {"총신대입구": "이수", "이수": "총신대입구"}
ON_LINE_PX = 12.0  # 원 중심이 선에서 이 안이면 그 선 위의 역 표시
COLOR_VOTE_PX = 120.0  # 노선 색을 셀 때 글자에서 선까지 이 안만 센다
MARK_MAX_PX = 200.0  # 글자에서 이보다 먼 표시와는 짝짓지 않는다
NO_MARK_COST = 220.0  # 표시 없이 남는 비용(이보다 먼 짝보다 낫다)
PILL_PENALTY = 30.0  # 환승역이 아닌 역이 흰 표시를 가질 때 더하는 비용
PROJECT_MAX_PX = 120.0  # 표시가 없을 때 이 안의 선 위로 옮긴다
COLOR_MERGE = 48  # RGB 거리 제곱이 이 이하면 같은 노선 색(#263C94 → #263C96)
_MATRIX = re.compile(r"matrix\(([^)]*)\)")
_PATH_TOKEN = re.compile(r"[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?")
_ARGS = {"M": 2, "L": 2, "C": 6, "S": 4, "Q": 4, "T": 2, "H": 1, "V": 1, "Z": 0}

Point = tuple[float, float]


@dataclass(frozen=True)
class Label:
    name: str
    x: float
    y: float


@dataclass
class MapData:
    width: float
    height: float
    labels: list[Label]
    pills: list[Point]  # 흰 환승역 표시의 중심
    dots: list[tuple[float, float, str]]  # 역 원 (x, y, 원래 색)
    segments: dict[str, list[tuple[Point, Point]]]  # 선 색 → 선분들


def path_points(d: str) -> list[list[Point]]:
    """<path d>의 하위 경로마다 끝점들(곡선의 조절점은 뺀다)."""
    subs: list[list[Point]] = []
    cur: list[Point] = []
    x = y = sx = sy = 0.0
    cmd = ""
    nums: list[float] = []

    def flush() -> None:
        nonlocal x, y, sx, sy, nums, cur
        up = cmd.upper()
        rel = cmd.islower()
        if up == "Z":
            cur.append((sx, sy))
            x, y = sx, sy
        n = _ARGS.get(up, 0)
        while n and len(nums) >= n:
            args, nums = nums[:n], nums[n:]
            if up == "H":
                x = args[0] + (x if rel else 0)
            elif up == "V":
                y = args[0] + (y if rel else 0)
            else:
                x, y = (args[-2] + x, args[-1] + y) if rel else (args[-2], args[-1])
            if up == "M":
                if cur:
                    subs.append(cur)
                cur = []
                sx, sy = x, y
            cur.append((x, y))
        nums = []

    for token in _PATH_TOKEN.findall(d):
        if token.isalpha():
            flush()
            cmd = token
        else:
            nums.append(float(token))
    flush()
    if cur:
        subs.append(cur)
    return subs


def path_center(d: str) -> Point | None:
    """경로 끝점들의 경계 상자 가운데."""
    pts = [p for sub in path_points(d) for p in sub]
    if not pts:
        return None
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2


def is_pill(d: str) -> bool:
    """흰 환승역 표시 모양인지: 알약(한 변 약 18px, 다른 변 30~90px)이나 큰 원(21~40px).

    이름표 안의 흰 글자·숫자(작다)는 빠진다.
    """
    pts = [p for sub in path_points(d) for p in sub]
    if not pts:
        return False
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    short, long = sorted((max(xs) - min(xs), max(ys) - min(ys)))
    return (16 <= short <= 20 and 30 <= long <= 90) or (21 <= short and long <= 40)


def _translate(transform: str | None) -> Point | None:
    m = _MATRIX.search(transform or "")
    if not m:
        return None
    values = [float(v) for v in m.group(1).replace(",", " ").split()]
    return (values[4], values[5]) if len(values) == 6 else None


def read_svg(path: Path) -> MapData:
    root = ET.parse(path).getroot()
    _, _, width, height = (float(v) for v in root.get("viewBox", "0 0 0 0").split())
    labels = []
    for text in root.iter(NS + "text"):
        name = re.sub(r"\s+", "", "".join(text.itertext()))
        at = _translate(text.get("transform"))
        if name and at:
            labels.append(Label(name, *at))
    pills: list[Point] = []
    dots: list[tuple[float, float, str]] = []
    segments: dict[str, list[tuple[Point, Point]]] = defaultdict(list)
    for el in root.iter():
        tag = el.tag.replace(NS, "")
        stroke = (el.get("stroke") or "").upper()
        fill = (el.get("fill") or "").upper()
        if tag == "circle":
            color = stroke if fill == WHITE else fill
            dots.append((float(el.get("cx", 0)), float(el.get("cy", 0)), color))
        elif tag == "path" and fill == WHITE:
            d = el.get("d", "")
            center = path_center(d)
            if center and is_pill(d):
                pills.append(center)
        elif tag in ("line", "polyline", "path") and stroke.startswith("#") and stroke != WHITE:
            if tag == "line":
                k = ("x1", "y1", "x2", "y2")
                x1, y1, x2, y2 = (float(el.get(a, 0)) for a in k)
                lines = [[(x1, y1), (x2, y2)]]
            elif tag == "polyline":
                n = [float(v) for v in re.findall(r"-?\d*\.?\d+", el.get("points", ""))]
                lines = [list(zip(n[0::2], n[1::2], strict=False))]
            else:
                lines = path_points(el.get("d", ""))
            for pts in lines:
                segments[stroke].extend(zip(pts, pts[1:], strict=False))
    pills = list(dict.fromkeys((round(x, 1), round(y, 1)) for x, y in pills))
    return MapData(width, height, labels, pills, dots, dict(segments))


def _to_segment(p: Point, a: Point, b: Point) -> tuple[float, Point]:
    dx, dy = b[0] - a[0], b[1] - a[1]
    length2 = dx * dx + dy * dy
    t = (
        0.0
        if length2 == 0
        else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2))
    )
    q = (a[0] + t * dx, a[1] + t * dy)
    return math.hypot(p[0] - q[0], p[1] - q[1]), q


def nearest_on(p: Point, segments: list[tuple[Point, Point]]) -> tuple[float, Point | None]:
    """선분들 위에서 p에 가장 가까운 점과 거리."""
    return min((_to_segment(p, a, b) for a, b in segments), default=(math.inf, None))


def _rgb(color: str) -> tuple[int, int, int]:
    return int(color[1:3], 16), int(color[3:5], 16), int(color[5:7], 16)


def merge_colors(segments: dict[str, list], known: set[str]) -> dict[str, list]:
    """노선 색과 거의 같은 색의 선은 그 노선 색으로 합친다."""
    merged: dict[str, list] = defaultdict(list)
    for color, segs in segments.items():
        merged[canon_color(color, known)].extend(segs)
    return dict(merged)


def canon_color(color: str, known: set[str]) -> str:
    if color in known or not re.fullmatch(r"#[0-9A-F]{6}", color) or not known:
        return color
    best = min(
        sorted(known),
        key=lambda k: sum((a - b) ** 2 for a, b in zip(_rgb(k), _rgb(color), strict=True)),
    )
    far = sum((a - b) ** 2 for a, b in zip(_rgb(best), _rgb(color), strict=True))
    return best if far <= COLOR_MERGE else color


def line_colors(
    labeled: list[tuple[str, Label]], index: StationIndex, segments: dict[str, list]
) -> dict[str, str]:
    """노선 이름 → 선 색. 한 노선에만 있는 역의 글자에서 가장 가까운 선의 색을 센다."""
    votes: dict[str, Counter] = defaultdict(Counter)
    for station_id, label in labeled:
        lines = index.get(station_id).lines
        if len(lines) != 1:
            continue
        dist, color = min(
            ((nearest_on((label.x, label.y), segs)[0], c) for c, segs in sorted(segments.items())),
            default=(math.inf, ""),
        )
        if dist <= COLOR_VOTE_PX:
            votes[lines[0]][color] += 1
    return {line: c.most_common(1)[0][0] for line, c in sorted(votes.items())}


def hungarian(cost: list[list[float]]) -> list[int | None]:
    """행 수 <= 열 수인 비용 행렬의 최소 비용 배정. 행 i → 열 result[i]."""
    n, m = len(cost), len(cost[0]) if cost else 0
    inf = math.inf
    u, v = [0.0] * (n + 1), [0.0] * (m + 1)
    p, way = [0] * (m + 1), [0] * (m + 1)
    for i in range(1, n + 1):
        p[0], j0 = i, 0
        minv, used = [inf] * (m + 1), [False] * (m + 1)
        while True:
            used[j0] = True
            i0, delta, j1 = p[j0], inf, 0
            row = cost[i0 - 1]
            for j in range(1, m + 1):
                if not used[j]:
                    cur = row[j - 1] - u[i0] - v[j]
                    if cur < minv[j]:
                        minv[j], way[j] = cur, j0
                    if minv[j] < delta:
                        delta, j1 = minv[j], j
            for j in range(m + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while j0:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
    result: list[int | None] = [None] * n
    for j in range(1, m + 1):
        if p[j]:
            result[p[j] - 1] = j - 1
    return result


def link_labels(
    labels: list[Label], index: StationIndex
) -> tuple[list[tuple[str, Label]], list[str]]:
    """(역 id, 글자) 목록과 잇지 못한 글자 이름들."""
    by_name: dict[str, list[Label]] = defaultdict(list)
    for label in labels:
        by_name[NAME_EXCEPTIONS.get(label.name, label.name.replace("·", "."))].append(label)
    labeled: list[tuple[str, Label]] = []
    unmatched: list[str] = []
    for name, group in sorted(by_name.items()):
        station = index.resolve(name)
        if station is None:
            unmatched.append(name)
            continue
        # 같은 이름의 다른 역(양평)은 노선도에서도 따로 있다: 동쪽 역부터 x가 큰 글자에 잇는다.
        same = [s for s in index.all() if s.name.split("(")[0] == station.name.split("(")[0]]
        far = [lb for lb in group if any(math.hypot(lb.x - o.x, lb.y - o.y) > 500 for o in group)]
        if len(same) > 1 and len(far) == len(group):
            east_west = zip(
                sorted(same, key=lambda s: s.lng), sorted(group, key=lambda lb: lb.x), strict=False
            )
            labeled.extend((s.id, lb) for s, lb in east_west)
            continue
        labeled.extend((station.id, lb) for lb in group)
    return labeled, unmatched


def assign_points(
    labeled: list[tuple[str, Label]], index: StationIndex, data: MapData
) -> dict[str, Point]:
    """역마다 노선도 위 한 점(역 표시, 아니면 선 위, 아니면 글자 기준점)."""
    raw_colors = line_colors(labeled, index, data.segments)
    known = set(raw_colors.values())
    segments = merge_colors(data.segments, known)
    colors = {line: canon_color(c, known) for line, c in raw_colors.items()}

    def line_at(x: float, y: float) -> str | None:
        dist, color = min(
            ((nearest_on((x, y), segs)[0], c) for c, segs in sorted(segments.items())),
            default=(math.inf, None),
        )
        return color if dist <= ON_LINE_PX else None

    marks: list[tuple[float, float, str | None]] = [(x, y, None) for x, y in data.pills]
    marks += [(x, y, c) for x, y, _ in data.dots if (c := line_at(x, y))]
    by_station: dict[str, list[Label]] = defaultdict(list)
    for station_id, label in labeled:
        by_station[station_id].append(label)
    rows = sorted(by_station)
    big = 1e9
    cost: list[list[float]] = []
    for i, station_id in enumerate(rows):
        lines = index.get(station_id).lines
        wanted = {colors.get(line) for line in lines}
        penalty = 0.0 if len(lines) > 1 else PILL_PENALTY
        row = []
        for x, y, color in marks:
            dist = min(math.hypot(x - lb.x, y - lb.y) for lb in by_station[station_id])
            if dist > MARK_MAX_PX or (color is not None and color not in wanted):
                row.append(big)
            else:
                row.append(dist + (penalty if color is None else 0.0))
        row += [NO_MARK_COST if k == i else big for k in range(len(rows))]
        cost.append(row)
    points: dict[str, Point] = {}
    for i, j in enumerate(hungarian(cost)):
        if j is not None and j < len(marks) and cost[i][j] < big:
            points[rows[i]] = marks[j][:2]
    for station_id in rows:
        if station_id in points:
            continue
        label = by_station[station_id][0]
        lines = index.get(station_id).lines
        dist, q = min(
            (
                nearest_on((label.x, label.y), segments.get(colors.get(line, ""), []))
                for line in lines
            ),
            key=lambda r: r[0],
        )
        points[station_id] = q if q is not None and dist <= PROJECT_MAX_PX else (label.x, label.y)
    return points


def build(svg: Path, index: StationIndex) -> tuple[dict, list[str]]:
    """역 위치표와 잇지 못한 글자 목록."""
    data = read_svg(svg)
    labeled, unmatched = link_labels(data.labels, index)
    points = assign_points(labeled, index, data)
    for station_id, other in SAME_PLACE.items():
        if station_id not in points and other in points:
            points[station_id] = points[other]
    stations = [
        {"id": station_id, "x": round(x, 1), "y": round(y, 1)}
        for station_id, (x, y) in sorted(points.items())
    ]
    return {"width": data.width, "height": data.height, "stations": stations}, unmatched


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 3:
        print("사용법: python -m scripts.build_linemap <노선도 SVG> <stations.sqlite> <출력 폴더>")
        return 2
    svg, db, out = Path(args[0]), args[1], Path(args[2])
    index = StationIndex.load(db)
    table, unmatched = build(svg, index)
    out.mkdir(parents=True, exist_ok=True)
    (out / "stations.json").write_text(
        json.dumps(table, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    shutil.copyfile(svg, out / "linemap.svg")
    total = len(index.all())
    linked = len(table["stations"])
    missing = sorted(s.id for s in index.all() if s.id not in {x["id"] for x in table["stations"]})
    print(f"역 {linked}개 연결 / 우리 역 {total}개 중 {linked / total:.1%}")
    print(f"노선도에 없는 우리 역: {', '.join(missing)}")
    print(f"잇지 못한 글자: {', '.join(unmatched)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
