import logging

import httpx
import respx

from ml import alerts
from ml.alerts import (
    ENV_NAME,
    MAX_LEN,
    chunk_lines,
    discord_notifier,
    failure_text,
    notifier_from_env,
    report_exit,
    run_with_alert,
    send_lines,
)

URL = "https://discord.com/api/webhooks/123/secret-token"


@respx.mock
def test_discord_posts_content_body():
    route = respx.post(URL).mock(return_value=httpx.Response(204))
    discord_notifier(URL)("안녕")
    assert route.call_count == 1
    assert route.calls[0].request.read() == '{"content":"안녕"}'.encode()


@respx.mock
def test_long_text_is_cut_to_discord_limit():
    route = respx.post(URL).mock(return_value=httpx.Response(204))
    discord_notifier(URL)("가" * (MAX_LEN + 50))
    body = route.calls[0].request.read().decode()
    assert len(body) == len('{"content":""}') + MAX_LEN


@respx.mock
def test_server_error_does_not_raise_and_url_is_not_logged(caplog):
    respx.post(URL).mock(return_value=httpx.Response(500))
    with caplog.at_level(logging.WARNING):
        discord_notifier(URL)("x")
    assert "500" in caplog.text
    assert "secret-token" not in caplog.text and "webhooks" not in caplog.text


@respx.mock
def test_connection_error_does_not_raise_and_url_is_not_logged(caplog):
    respx.post(URL).mock(side_effect=httpx.ConnectError(f"cannot reach {URL}"))
    with caplog.at_level(logging.WARNING):
        discord_notifier(URL)("x")
    assert "ConnectError" in caplog.text
    assert "secret-token" not in caplog.text


@respx.mock
def test_success_at_info_level_does_not_log_url(caplog):
    respx.post(URL).mock(return_value=httpx.Response(204))
    with caplog.at_level(logging.DEBUG):
        discord_notifier(URL)("x")
    assert "secret-token" not in caplog.text


def test_run_with_alert_silences_httpx_request_log(http_log_levels):
    """httpx는 INFO로 요청 URL 전체(웹훅 토큰, 서울시 키)를 찍는다. 작업 시작 때 막는다."""
    logging.getLogger("httpx").setLevel(logging.NOTSET)
    logging.getLogger("httpcore").setLevel(logging.NOTSET)
    assert run_with_alert("작업", "unit", None, lambda notify: 0) == 0
    assert logging.getLogger("httpx").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("httpcore").getEffectiveLevel() >= logging.WARNING


def test_run_with_alert_passes_notifier_and_reports_failure(monkeypatch):
    sent: list[str] = []
    monkeypatch.setattr(alerts, "notifier_from_env", lambda *_: sent.append)
    got: list[object] = []

    def job(notify):
        got.append(notify)
        return 2

    assert run_with_alert("밤 작업", "subway-ml-nightly", None, job) == 2
    assert got == [sent.append] and len(sent) == 1 and "subway-ml-nightly" in sent[0]


def test_run_with_alert_turns_crash_into_exit_1_and_alerts(monkeypatch):
    sent: list[str] = []
    monkeypatch.setattr(alerts, "notifier_from_env", lambda *_: sent.append)

    def job(_notify):
        raise RuntimeError("boom")

    assert run_with_alert("밤 작업", "subway-ml-nightly", None, job) == 1
    assert len(sent) == 1


def test_chunk_lines_joins_lines_under_the_limit():
    lines = [f"- 줄 {i} " + "가" * 90 for i in range(60)]
    chunks = chunk_lines(lines)
    assert 1 < len(chunks) < len(lines)
    assert all(len(c) <= MAX_LEN for c in chunks)
    assert "\n".join(chunks).split("\n") == lines  # 순서와 내용을 그대로 지킨다


def test_chunk_lines_cuts_a_single_overlong_line():
    assert chunk_lines(["가" * (MAX_LEN + 10)]) == ["가" * MAX_LEN]
    assert chunk_lines([]) == []


def test_send_lines_sends_few_messages_and_survives_errors():
    sent: list[str] = []
    send_lines(sent.append, ["a"] * 500)
    assert len(sent) == 1

    def boom(_text):
        raise RuntimeError("x")

    send_lines(boom, ["a", "b"])  # 예외를 올리지 않는다
    send_lines(None, ["a"])


def test_no_notifier_without_env(tmp_path):
    env = tmp_path / ".env"
    env.write_text("SEOUL_POSITION_API_KEY=abc\n", encoding="utf-8")
    assert notifier_from_env(env, {}) is None
    assert notifier_from_env(tmp_path / "missing.env", {}) is None


@respx.mock
def test_notifier_reads_url_from_env_file_or_environ(tmp_path):
    route = respx.post(URL).mock(return_value=httpx.Response(204))
    env = tmp_path / ".env"
    env.write_text(f"{ENV_NAME}={URL}\n", encoding="utf-8")
    notifier_from_env(env, {})("a")
    notifier_from_env(None, {ENV_NAME: URL})("b")
    assert route.call_count == 2


def test_non_https_value_is_ignored(tmp_path):
    assert notifier_from_env(None, {ENV_NAME: "not-a-url"}) is None


def test_failure_text_names_job_code_and_where_to_look():
    text = failure_text("밤 작업", "subway-ml-nightly", 1)
    assert "밤 작업" in text and "1" in text and "journalctl -u subway-ml-nightly" in text


def test_report_exit_sends_only_on_failure():
    sent: list[str] = []
    assert report_exit(0, sent.append, "밤 작업", "subway-ml-nightly") == 0
    assert sent == []
    assert report_exit(2, sent.append, "밤 작업", "subway-ml-nightly") == 2
    assert len(sent) == 1 and "2" in sent[0]


def test_report_exit_without_notifier_and_with_broken_notifier():
    assert report_exit(1, None, "밤 작업", "subway-ml-nightly") == 1

    def broken(_text):
        raise RuntimeError("boom")

    assert report_exit(1, broken, "밤 작업", "subway-ml-nightly") == 1
