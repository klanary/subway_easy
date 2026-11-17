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
from app.services.measured_store import B1_FILE, B2_FILE, MANIFEST_FILE
from ml.alerts import run_with_alert
from ml.b2 import build_b2_by_line

logger = logging.getLogger("ml.publish")

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TAG = "measured-timetable"
REPO = "klanary/subway_easy"
TOKEN_ENV = "GITHUB_RELEASE_TOKEN"
PUBLISH_B2_DAYS = 7


def build_files(b1_path: Path, b2: dict, *, now: datetime) -> dict[str, bytes]:
    """올릴 파일들. 순서가 곧 올리는 순서이고 manifest가 마지막이다."""
    raw_b1 = Path(b1_path).read_bytes()
    as_of = json.loads(raw_b1)["as_of"]
    files = {
        B1_FILE: gzip.compress(raw_b1, mtime=0),
        B2_FILE: gzip.compress(
            json.dumps(b2, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), mtime=0
        ),
    }
    manifest = {
        "built_at": now.astimezone(KST).isoformat(timespec="seconds"),
        "as_of": as_of,
        "files": {name: hashlib.sha256(body).hexdigest() for name, body in files.items()},
    }
    files[MANIFEST_FILE] = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
    return files


def _check(response: httpx.Response, what: str) -> httpx.Response:
    if response.is_error:
        logger.error("GitHub %s 실패: HTTP %d", what, response.status_code)
        response.raise_for_status()
    return response


def publish(http: httpx.Client, token: str, repo: str, files: dict[str, bytes]) -> None:
    """릴리스(없으면 만든다)의 같은 이름 자산을 지우고 `files` 순서대로 올린다."""
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
    for name, body in files.items():
        if name in existing:
            _check(
                http.delete(
                    f"{API}/repos/{repo}/releases/assets/{existing[name]}", headers=headers
                ),
                f"{name} 지우기",
            )
        content_type = "application/json" if name.endswith(".json") else "application/gzip"
        _check(
            http.post(
                f"{UPLOADS}/repos/{repo}/releases/{release['id']}/assets",
                params={"name": name},
                headers={**headers, "Content-Type": content_type},
                content=body,
            ),
            f"{name} 올리기",
        )
        logger.info("올림: %s (%d바이트)", name, len(body))


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
    files = build_files(b1, b2, now=now)
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
