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
from datetime import date, datetime, timedelta
from pathlib import Path

import httpx
from dotenv import dotenv_values

from app.core.clock import KST
from app.services.measured import B1_KIND, B1_VERSION, B2_KIND, B2_VERSION
from app.services.measured_store import B1_PREFIX, B2_PREFIX, MANIFEST_FILE
from app.services.timetable import OFFICIAL_LINES
from ml.alerts import run_with_alert
from ml.b2 import build_b2_by_line

logger = logging.getLogger("ml.publish")

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TAG = "measured-timetable"
REPO = "klanary/subway_easy"
TOKEN_ENV = "GITHUB_RELEASE_TOKEN"
PUBLISH_B2_DAYS = 7


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
        "stations": {
            key: value
            for key, value in (b1.get("stations") or {}).items()
            if key.split("|")[0] not in OFFICIAL_LINES
        },
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


def build_files(b1: dict, b2: dict, *, now: datetime) -> dict[str, bytes]:
    """올릴 파일들. 데이터 두 개는 빌드마다 다른 이름이고, manifest가 마지막이다."""
    stamp = now.astimezone(KST).strftime("%Y%m%dT%H%M%S")
    names = {"b1": f"{B1_PREFIX}{stamp}.json.gz", "b2": f"{B2_PREFIX}{stamp}.json.gz"}
    bodies = {"b1": _pack(server_b1(b1)), "b2": _pack(server_b2(b2))}
    manifest = {
        "built_at": now.astimezone(KST).isoformat(timespec="seconds"),
        "as_of": b1["as_of"],
        **{
            part: {"name": names[part], "sha256": hashlib.sha256(bodies[part]).hexdigest()}
            for part in ("b1", "b2")
        },
    }
    files = {names[part]: bodies[part] for part in ("b1", "b2")}
    files[MANIFEST_FILE] = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
    return files


def _check(response: httpx.Response, what: str) -> httpx.Response:
    if response.is_error:
        logger.error("GitHub %s 실패: HTTP %d", what, response.status_code)
        response.raise_for_status()
    return response


def publish(http: httpx.Client, token: str, repo: str, files: dict[str, bytes]) -> None:
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
    for name in existing:
        if name.startswith((B1_PREFIX, B2_PREFIX)) and name not in files:
            delete(name)


def _run(args: argparse.Namespace, now: datetime) -> int:
    token = (os.environ.get(TOKEN_ENV) or dotenv_values(args.env_file).get(TOKEN_ENV) or "").strip()
    if not token:
        print(f"{args.env_file}에 {TOKEN_ENV}가 없어 내보내지 않습니다.", file=sys.stderr)
        return 2
    db, b1 = Path(args.db), Path(args.b1)
    if not db.is_file() or not b1.is_file():
        print(f"수집 DB나 B1 결과물이 없습니다: {db}, {b1}", file=sys.stderr)
        return 2
    today = date.fromisoformat(args.day) if args.day else now.astimezone(KST).date()
    b2 = build_b2_by_line(
        db, date_from=today - timedelta(days=args.days), date_to=today - timedelta(days=1), now=now
    )
    files = build_files(json.loads(b1.read_text(encoding="utf-8")), b2, now=now)
    with httpx.Client(timeout=60) as http:
        publish(http, token, args.repo, files)
    print(f"내보냄: {', '.join(files)} → {args.repo} 릴리스 {TAG} (B2 구간 {len(b2['hops'])}개)")
    return 0


def main(argv: list[str] | None = None, *, now: datetime | None = None) -> int:
    parser = argparse.ArgumentParser(description="실측 결과물을 GitHub 릴리스로 내보내기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--b1", default="ml_data/artifacts/measured_timetable.json")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--repo", default=REPO)
    parser.add_argument("--days", type=int, default=PUBLISH_B2_DAYS, help="B2에 쓰는 최근 날짜 수")
    parser.add_argument("--day", default="", help="오늘 날짜 대신 쓸 날짜(YYYY-MM-DD)")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    when = now or datetime.now(KST)
    return run_with_alert(
        "실측 내보내기", "subway-ml-publish", args.env_file, lambda _notify: _run(args, when)
    )


if __name__ == "__main__":
    raise SystemExit(main())
