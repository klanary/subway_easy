"""Discord 웹훅 알림(계획 2026-11-16 Task 10, 사용자 결정 2026-11-17).

- 웹훅 URL은 `.env`의 `DISCORD_WEBHOOK_URL`에서 읽는다. 없으면 알림을 붙이지 않는다.
- URL에는 비밀 토큰이 들어 있으므로 로그·예외 메시지에 절대 남기지 않는다.
  실패하면 상태 코드나 예외 종류 이름만 남기고, 부른 쪽에 예외를 올리지 않는다.
"""

import json
import logging
import os
from collections.abc import Callable, Iterable, Mapping
from pathlib import Path

import httpx
from dotenv import dotenv_values

from app.core.logging import quiet_http_loggers

logger = logging.getLogger("ml.alerts")

ENV_NAME = "DISCORD_WEBHOOK_URL"
MAX_LEN = 2000  # Discord 메시지 한 개의 글자 수 한도
TIMEOUT_SEC = 10.0

Notify = Callable[[str], None]


def discord_notifier(url: str) -> Notify:
    """text를 Discord 채널에 보낸다. 실패해도 예외를 올리지 않는다.

    httpx 요청 로그(URL 전체)는 작업 시작 때 `run_with_alert`가 끈다.
    """

    def send(text: str) -> None:
        body = json.dumps({"content": text[:MAX_LEN]}, ensure_ascii=False, separators=(",", ":"))
        try:
            response = httpx.post(
                url,
                content=body.encode("utf-8"),
                headers={"Content-Type": "application/json"},
                timeout=TIMEOUT_SEC,
            )
        except httpx.HTTPError as e:
            logger.warning("Discord 알림 실패: %s", type(e).__name__)
            return
        if not 200 <= response.status_code < 300:
            logger.warning("Discord 알림 실패: HTTP %d", response.status_code)

    return send


def notifier_from_env(env_file: str | Path | None, environ: Mapping[str, str]) -> Notify | None:
    """웹훅 URL이 있으면 알림 함수, 없으면 None. 환경 변수가 `.env`보다 먼저다."""
    from_file = dotenv_values(env_file) if env_file and Path(env_file).is_file() else {}
    url = (environ.get(ENV_NAME) or from_file.get(ENV_NAME) or "").strip()
    if not url.startswith("https://"):
        return None
    return discord_notifier(url)


def chunk_lines(lines: Iterable[str], limit: int = MAX_LEN) -> list[str]:
    """줄들을 줄바꿈으로 이어 limit 글자 이하의 메시지로 나눈다. 너무 긴 한 줄은 자른다.

    Discord 웹훅은 약 2초에 5건만 받는다. 줄마다 보내면 확정이 여럿인 밤에 대부분이 429로 사라진다.
    """
    chunks: list[str] = []
    current = ""
    for line in lines:
        line = line[:limit]
        if current and len(current) + 1 + len(line) <= limit:
            current += "\n" + line
        else:
            if current:
                chunks.append(current)
            current = line
    if current:
        chunks.append(current)
    return chunks


def send_lines(notify: Notify | None, lines: Iterable[str]) -> None:
    """줄들을 몇 개의 메시지로 묶어 보낸다. 실패해도 예외를 올리지 않는다."""
    if notify is None:
        return
    for chunk in chunk_lines(lines):
        try:
            notify(chunk)
        except Exception as e:
            logger.warning("알림 실패: %s", type(e).__name__)


def failure_text(job: str, unit: str, code: int) -> str:
    return (
        f"⚠️ 지금출발 {job} 실패(종료 코드 {code})."
        f" VM에서 `journalctl -u {unit} -n 50`으로 확인하세요."
    )


def report_exit(code: int, notify: Notify | None, job: str, unit: str) -> int:
    """종료 코드가 0이 아니면 알리고, 코드는 그대로 돌려준다."""
    if code and notify is not None:
        try:
            notify(failure_text(job, unit, code))
        except Exception as e:
            logger.warning("알림 실패: %s", type(e).__name__)
    return code


def run_with_alert(
    job: str, unit: str, env_file: str | Path | None, run: Callable[[Notify | None], int]
) -> int:
    """작업 하나를 돌리고, 실패(종료 코드 1·2, 예기치 않은 예외)면 Discord로 알린다.

    httpx 요청 로그를 먼저 끄고, 알림 함수(웹훅이 없으면 None)를 작업에 넘긴다.
    """
    quiet_http_loggers()
    notify = notifier_from_env(env_file, os.environ)
    try:
        code = run(notify)
    except Exception:
        logger.exception("%s이(가) 예기치 않게 멈췄습니다", job)
        code = 1
    return report_exit(code, notify, job, unit)
