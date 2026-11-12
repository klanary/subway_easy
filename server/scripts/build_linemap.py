"""노선도 SVG에서 역 위치를 뽑아 앱이 쓰는 역 위치표를 만든다(재설계 스펙 8).

사용법 (server/에서):
    python -m scripts.build_linemap data/raw/Seoul_subway_linemap_ko.svg data/stations.sqlite \
        ../android/app/src/main/assets/linemap

- 원본: 위키미디어 공용 Seoul_subway_linemap_ko.svg(퍼블릭 도메인, IRTC1015, 2025-06-29 판).
- 역 이름 글자(<text>)의 위치를 읽어 우리 역 id에 잇고, 가장 가까운 역 표시
  (환승역 기호 <use>, 역 눈금 <line>)에 붙인다.
- 결과는 out_dir/stations.json과 out_dir/linemap.svg(원본 복사)다. 같은 입력이면 같은 결과를 낸다.
"""

import json
import math
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

from app.services.station_index import StationIndex

NS = "{http://www.w3.org/2000/svg}"
XLINK_HREF = "{http://www.w3.org/1999/xlink}href"
TICK_MAX_LEN = 20.5  # 역 눈금 선의 길이(px). 노선 선은 이보다 길다.
SNAP_MAX_DIST = 70.0  # 글자 기준점에서 이 거리 안의 역 표시에 붙인다
# 노선도와 우리 역 데이터의 이름이 다른 역(노선도 이름 → 우리 역 id). 2026-11-12 대조.
NAME_EXCEPTIONS: dict[str, str] = {
    "자양": "뚝섬유원지",
    "평택지제": "지제",
    "용인중앙시장": "김량장",
}
# 우리 데이터에는 두 역이지만 노선도에는 한 역인 곳(역 id → 같은 자리를 쓰는 역 id).
SAME_PLACE: dict[str, str] = {"총신대입구": "이수"}
_MATRIX = re.compile(r"matrix\(([^)]*)\)")


@dataclass(frozen=True)
class Label:
    name: str
    x: float
    y: float


def _translate(transform: str | None) -> tuple[float, float] | None:
    m = _MATRIX.search(transform or "")
    if not m:
        return None
    values = [float(v) for v in m.group(1).replace(",", " ").split()]
    return (values[4], values[5]) if len(values) == 6 else None


def read_svg(path: Path) -> tuple[float, float, list[Label], list[tuple[float, float]]]:
    """(너비, 높이, 역 이름 글자들, 역 표시 위치들)."""
    root = ET.parse(path).getroot()
    _, _, width, height = (float(v) for v in root.get("viewBox", "0 0 0 0").split())
    labels = []
    for text in root.iter(NS + "text"):
        name = re.sub(r"\s+", "", "".join(text.itertext()))
        at = _translate(text.get("transform"))
        if name and at:
            labels.append(Label(name, *at))
    marks: list[tuple[float, float]] = []
    for use in root.iter(NS + "use"):
        at = _translate(use.get("transform"))
        if use.get(XLINK_HREF) == "#환승역" and at:
            marks.append(at)
    for line in root.iter(NS + "line"):
        x1, y1, x2, y2 = (float(line.get(k, 0)) for k in ("x1", "y1", "x2", "y2"))
        if math.hypot(x2 - x1, y2 - y1) <= TICK_MAX_LEN:
            marks.append(((x1 + x2) / 2, (y1 + y2) / 2))
    return width, height, labels, marks


def snap(label: Label, marks: list[tuple[float, float]]) -> tuple[float, float, float]:
    """(x, y, 거리). 가까운 역 표시가 없으면 글자 기준점과 거리 무한대."""
    best = min(marks, key=lambda m: (math.hypot(m[0] - label.x, m[1] - label.y), m), default=None)
    if best is None:
        return label.x, label.y, math.inf
    dist = math.hypot(best[0] - label.x, best[1] - label.y)
    return (best[0], best[1], dist) if dist <= SNAP_MAX_DIST else (label.x, label.y, math.inf)


def build(svg: Path, index: StationIndex) -> tuple[dict, list[str]]:
    """역 위치표와 잇지 못한 글자 목록."""
    width, height, labels, marks = read_svg(svg)
    found: dict[str, list[tuple[float, float, float]]] = {}
    unmatched: list[str] = []
    by_name: dict[str, list[Label]] = {}
    for label in labels:
        name = NAME_EXCEPTIONS.get(label.name, label.name.replace("·", "."))
        by_name.setdefault(name, []).append(label)
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
            for s, lb in east_west:
                found.setdefault(s.id, []).append(snap(lb, marks))
            continue
        for lb in group:
            found.setdefault(station.id, []).append(snap(lb, marks))
    for station_id, other in SAME_PLACE.items():
        if station_id not in found and other in found:
            found[station_id] = found[other]
    stations = []
    for station_id, points in sorted(found.items()):
        x, y, _ = min(points, key=lambda p: (p[2], p[0], p[1]))  # 역 표시에 가장 가까이 붙은 글자
        stations.append({"id": station_id, "x": round(x, 1), "y": round(y, 1)})
    return {"width": width, "height": height, "stations": stations}, unmatched


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
