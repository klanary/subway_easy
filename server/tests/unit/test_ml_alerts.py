import logging

import httpx
import respx

from ml.alerts import (
    ENV_NAME,
    MAX_LEN,
    discord_notifier,
    failure_text,
    notifier_from_env,
    report_exit,
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


def test_httpx_request_log_is_silenced():
    """httpx는 INFO로 요청 URL 전체를 찍는다. 밤 작업은 INFO로 돌므로 막아야 한다."""
    logging.getLogger("httpx").setLevel(logging.NOTSET)
    discord_notifier(URL)
    assert logging.getLogger("httpx").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("httpcore").getEffectiveLevel() >= logging.WARNING


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
