"""수·일 실측 결과물을 GitHub 릴리스로 내보낸다(설계 2026-11-17 §4.1).

사용법 (server/에서, 밤 작업 02:40 뒤):
    python -m ml.publish

1. 최근 7일로 B2(구간 시간)를 호선마다 만든다(메모리 1GB).
2. 밤 작업이 만든 B1과 함께 gzip하고, 두 파일의 sha256을 담은 manifest를 만든다.
3. 릴리스 `measured-timetable`의 자산을 바꾼다. manifest는 마지막에 올린다
   (서버는 manifest를 보고 받으므로 반쯤 바뀐 상태를 쓰지 않는다).

토큰은 `.env`의 GITHUB_RELEASE_TOKEN(이 저장소만, Contents 읽기·쓰기). 값은 어디에도 찍지 않는다.
"""

import argparse
import gzip
import hashlib
import json
import logging
import os
import sys
from collections.abc import Iterable
from datetime import date, datetime, timedelta
from pathlib import Path

import httpx
from dotenv import dotenv_values

from app.core.clock import KST
from app.services.measured import B1_KIND, B1_VERSION, B2_KIND, B2_VERSION
from app.services.measured_store import B1_PREFIX, B2_PREFIX, MANIFEST_FILE, TRIPS_PREFIX
from app.services.timetable import OFFICIAL_LINES
from ml.alerts import run_with_alert
from ml.b2 import build_b2_by_line
from ml.trips import build_trips

logger = logging.getLogger("ml.publish")

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TAG = "measured-timetable"
REPO = "klanary/subway_easy"
TOKEN_ENV = "GITHUB_RELEASE_TOKEN"
PUBLISH_B2_DAYS = 7
REBUILD_WEEKDAYS = (2, 6)  # 수·일에 B1·B2를 새로 만든다(운행표는 매일)
DATA_PREFIXES = (B1_PREFIX, B2_PREFIX, TRIPS_PREFIX)


_SERVER_STATES = ("suspect", "retraining")  # 서버가 칸을 거르는 데 쓰는 단위 상태
_HOP_TABLES = ("hops", "hops_day", "hops_hour")


def _line_of(key: str) -> str:
    return key.split("|")[1]


def server_b1(b1: dict) -> dict:
    """서버용 B1: 시간표 API가 없는 노선만, 칸은 [초, 열차 번호, 행선지 번호, 급행] 목록.

    1~9호선은 서버가 공식 시간표를 쓰므로 뺀다. 행선지 이름은 `dests`에 한 번만 둔다.
    Render 무료 서버(512MB)에서 전체 B1을 그대로 올리면 메모리를 넘는다(최종 리뷰 #1).
    """
    dests: list[str] = []
    index: dict[str, int] = {}

    def dest_of(name: str | None) -> int:
        if not name:
            return -1
        if name not in index:
            index[name] = len(dests)
            dests.append(name)
        return index[name]

    cells = {
        key: [
            [
                int(e["secs"]),
                e.get("train_no"),
                dest_of(e.get("dest_name")),
                1 if e.get("express") else 0,
            ]
            for e in entries
        ]
        for key, entries in (b1.get("cells") or {}).items()
        if _line_of(key) not in OFFICIAL_LINES
    }
    return {
        "kind": B1_KIND,
        "version": B1_VERSION,
        "as_of": b1["as_of"],
        "built_at": b1.get("built_at"),
        "modes": {
            line: m for line, m in (b1.get("modes") or {}).items() if line not in OFFICIAL_LINES
        },
        "scopes": {
            key: scope.get("state")
            for key, scope in (b1.get("scopes") or {}).items()
            if _line_of(key) not in OFFICIAL_LINES and scope.get("state") in _SERVER_STATES
        },
        # 역 연결은 1~9호선도 보낸다: 서버가 열차 위치로 도착을 계산할 때 구간 시간을 찾는다
        # (설계 2026-11-22 열차 위치 도착 계산 §4).
        "stations": dict(b1.get("stations") or {}),
        "dests": dests,
        "cells": cells,
    }


def server_b2(b2: dict) -> dict:
    """서버용 B2: 구간 시간 표 세 개의 중앙값만(정차·도착 전 시간 등은 서버가 쓰지 않는다)."""
    out: dict = {"kind": B2_KIND, "version": B2_VERSION, "built_at": b2.get("built_at")}
    for table in _HOP_TABLES:
        out[table] = {key: stats["median"] for key, stats in (b2.get(table) or {}).items()}
    return out


def _pack(data: dict) -> bytes:
    body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return gzip.compress(body, mtime=0)


def build_files(
    b1: dict | None,
    b2: dict | None,
    *,
    now: datetime,
    trips: dict | None = None,
    previous: dict | None = None,
) -> dict[str, bytes]:
    """올릴 파일들. 데이터는 빌드마다 다른 이름이고, manifest가 마지막이다.

    `b1`이 None이면(수·일이 아닌 날) B1·B2를 새로 만들지 않고
    `previous` manifest의 항목을 그대로 쓴다.
    """
    stamp = now.astimezone(KST).strftime("%Y%m%dT%H%M%S")
    manifest: dict = {"built_at": now.astimezone(KST).isoformat(timespec="seconds")}
    files: dict[str, bytes] = {}

    def add(part: str, name: str, body: bytes, **extra) -> None:
        files[name] = body
        manifest[part] = {"name": name, "sha256": hashlib.sha256(body).hexdigest(), **extra}

    if b1 is not None and b2 is not None:
        manifest["as_of"] = b1["as_of"]
        add("b1", f"{B1_PREFIX}{stamp}.json.gz", _pack(server_b1(b1)))
        add("b2", f"{B2_PREFIX}{stamp}.json.gz", _pack(server_b2(b2)))
    elif previous is not None:
        manifest["as_of"] = previous["as_of"]
        manifest["b1"], manifest["b2"] = previous["b1"], previous["b2"]
    else:
        raise ValueError("B1·B2를 새로 만들지 않으려면 지난 manifest가 필요합니다")
    if trips is not None:
        add("trips", f"{TRIPS_PREFIX}{stamp}.json.gz", _pack(trips), built_on=trips["built_on"])
    files[MANIFEST_FILE] = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
    return files


def load_b1_for_publish(path: Path) -> dict:
    """B1을 읽자마자 1~9호선 칸·역·단위를 덜어 낸다(VM 메모리 1GB, 최종 리뷰 #5).

    서버용 사본(`server_b1`)도 운행표(`build_trips`)도 1~9호선 실측은 쓰지 않는다.
    """
    b1 = json.loads(Path(path).read_text(encoding="utf-8"))
    b1["cells"] = {
        k: v for k, v in (b1.get("cells") or {}).items() if _line_of(k) not in OFFICIAL_LINES
    }
    # 역 연결(stations)은 작으므로 1~9호선도 둔다: 서버가 구간 시간을 찾는 데 쓴다.
    b1["scopes"] = {
        k: v for k, v in (b1.get("scopes") or {}).items() if _line_of(k) not in OFFICIAL_LINES
    }
    return b1


def official_trip_count(trips: dict) -> int:
    """운행표 안의 1~9호선 운행 수. 0이면 시간표 캐시를 읽지 못한 것이다."""
    return sum(
        len(p["trips"])
        for patterns in trips["day_types"].values()
        for p in patterns
        if p["line"] in OFFICIAL_LINES
    )


def fetch_manifest(http: httpx.Client, repo: str) -> dict | None:
    """릴리스의 지금 manifest(공개 주소). 없거나 읽지 못하면 None."""
    try:
        response = http.get(
            f"https://github.com/{repo}/releases/download/{TAG}/{MANIFEST_FILE}",
            follow_redirects=True,
        )
        if response.status_code != 200:
            return None
        manifest = response.json()
        return manifest if "b1" in manifest and "b2" in manifest else None
    except (httpx.HTTPError, ValueError):
        return None


def _check(response: httpx.Response, what: str) -> httpx.Response:
    if response.is_error:
        logger.error("GitHub %s 실패: HTTP %d", what, response.status_code)
        response.raise_for_status()
    return response


def publish(
    http: httpx.Client,
    token: str,
    repo: str,
    files: dict[str, bytes],
    keep: Iterable[str] = (),
) -> None:
    """새 데이터 파일을 올리고 → manifest를 바꾸고 → 옛 데이터 파일을 지운다.

    올리다 실패하면 옛 manifest와 옛 데이터가 그대로 남아 서버는 지난 것을 계속 받는다.
    """
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    found = http.get(f"{API}/repos/{repo}/releases/tags/{TAG}", headers=headers)
    if found.status_code == 404:
        found = http.post(
            f"{API}/repos/{repo}/releases",
            headers=headers,
            json={
                "tag_name": TAG,
                "name": TAG,
                "body": "VM 위치 수집으로 만든 실측 시간표(B1)·구간 시간(B2). 수·일 자동 갱신.",
                "prerelease": True,
                "make_latest": "false",
            },
        )
    release = _check(found, "릴리스 찾기").json()
    existing = {asset["name"]: asset["id"] for asset in release.get("assets") or []}

    def upload(name: str) -> None:
        content_type = "application/json" if name.endswith(".json") else "application/gzip"
        _check(
            http.post(
                f"{UPLOADS}/repos/{repo}/releases/{release['id']}/assets",
                params={"name": name},
                headers={**headers, "Content-Type": content_type},
                content=files[name],
            ),
            f"{name} 올리기",
        )
        logger.info("올림: %s (%d바이트)", name, len(files[name]))

    def delete(name: str) -> None:
        url = f"{API}/repos/{repo}/releases/assets/{existing[name]}"
        _check(http.delete(url, headers=headers), f"{name} 지우기")

    for name in files:
        if name != MANIFEST_FILE:
            upload(name)
    if MANIFEST_FILE in files:
        if MANIFEST_FILE in existing:
            delete(MANIFEST_FILE)
        upload(MANIFEST_FILE)
    kept = set(keep)
    for name in existing:
        if name.startswith(DATA_PREFIXES) and name not in files and name not in kept:
            delete(name)


def _run(args: argparse.Namespace, now: datetime) -> int:
    token = (os.environ.get(TOKEN_ENV) or dotenv_values(args.env_file).get(TOKEN_ENV) or "").strip()
    if not token:
        print(f"{args.env_file}에 {TOKEN_ENV}가 없어 내보내지 않습니다.", file=sys.stderr)
        return 2
    db, b1_path = Path(args.db), Path(args.b1)
    if not db.is_file() or not b1_path.is_file():
        print(f"수집 DB나 B1 결과물이 없습니다: {db}, {b1_path}", file=sys.stderr)
        return 2
    today = date.fromisoformat(args.day) if args.day else now.astimezone(KST).date()
    b2_cache = Path(args.b2_cache)
    with httpx.Client(timeout=60) as http:
        previous = fetch_manifest(http, args.repo)
        rebuild = today.weekday() in REBUILD_WEEKDAYS or previous is None
        if rebuild or not b2_cache.is_file():
            b2 = build_b2_by_line(
                db,
                date_from=today - timedelta(days=args.days),
                date_to=today - timedelta(days=1),
                now=now,
            )
            b2_cache.parent.mkdir(parents=True, exist_ok=True)
            b2_cache.write_text(json.dumps(b2, ensure_ascii=False), encoding="utf-8")
        else:
            b2 = json.loads(b2_cache.read_text(encoding="utf-8"))
        # B2를 다 만든 뒤에 B1을 읽는다(둘이 함께 메모리에 오래 있지 않게).
        b1 = load_b1_for_publish(b1_path)
        cache_dir = Path(args.timetable_cache)
        trips = build_trips(
            cache_dir if cache_dir.is_dir() else None,
            Path(args.stations_db),
            b1,
            b2,
            built_on=today,
        )
        if official_trip_count(trips) == 0:
            # 캐시 경로가 틀리거나 비었다.
            # 1~9호선 없는 운행표를 올리면 아침에 경로가 사라진다(리뷰 #6).
            print(f"운행표에 1~9호선 운행이 없어 올리지 않습니다: {cache_dir}", file=sys.stderr)
            return 1
        files = build_files(
            b1 if rebuild else None,
            b2 if rebuild else None,
            now=now,
            trips=trips,
            previous=previous,
        )
        keep = () if rebuild else (previous["b1"]["name"], previous["b2"]["name"])
        publish(http, token, args.repo, files, keep=keep)
    print(
        f"내보냄: {', '.join(files)} → {args.repo} 릴리스 {TAG}"
        f" (B1·B2 {'새로' if rebuild else '그대로'}, 운행 패턴 "
        f"{sum(len(v) for v in trips['day_types'].values())}개)"
    )
    return 0


def main(argv: list[str] | None = None, *, now: datetime | None = None) -> int:
    parser = argparse.ArgumentParser(description="실측 결과물을 GitHub 릴리스로 내보내기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--b1", default="ml_data/artifacts/measured_timetable.json")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--repo", default=REPO)
    parser.add_argument("--days", type=int, default=PUBLISH_B2_DAYS, help="B2에 쓰는 최근 날짜 수")
    parser.add_argument("--day", default="", help="오늘 날짜 대신 쓸 날짜(YYYY-MM-DD)")
    parser.add_argument("--timetable-cache", default="ml_data/timetable_cache")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    parser.add_argument(
        "--b2-cache", default="ml_data/artifacts/segment_times.json", help="평일 운행표용 B2"
    )
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    when = now or datetime.now(KST)
    return run_with_alert(
        "실측 내보내기", "subway-ml-publish", args.env_file, lambda _notify: _run(args, when)
    )


if __name__ == "__main__":
    raise SystemExit(main())
