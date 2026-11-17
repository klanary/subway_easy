"""수·일 실측 결과물 내보내기(설계 2026-11-17 §4.1): GitHub 릴리스 자산 교체, manifest는 마지막."""

import gzip
import hashlib
import json
import logging
from datetime import datetime

import httpx
import pytest
import respx

from app.core.clock import KST
from app.services.measured_store import B1_FILE, B2_FILE, MANIFEST_FILE
from ml.publish import API, TAG, UPLOADS, build_files, main, publish
from tests.ml_helpers import trip, weekdays, write_db

REPO = "owner/repo"
TOKEN = "ghp_SECRET_TOKEN_VALUE"
NOW = datetime(2026, 11, 18, 3, 30, tzinfo=KST)
B1 = {"version": 2, "kind": "b1", "as_of": "2026-11-17", "cells": {}, "stations": {}}


def test_manifest_lists_sha256_and_dates(tmp_path):
    b1_path = tmp_path / "b1.json"
    b1_path.write_text(json.dumps(B1), encoding="utf-8")
    files = build_files(b1_path, {"version": 1, "kind": "b2"}, now=NOW)
    assert list(files) == [B1_FILE, B2_FILE, MANIFEST_FILE]
    manifest = json.loads(files[MANIFEST_FILE])
    assert manifest["as_of"] == "2026-11-17" and manifest["built_at"].startswith("2026-11-18T03:30")
    for name in (B1_FILE, B2_FILE):
        assert manifest["files"][name] == hashlib.sha256(files[name]).hexdigest()
    assert json.loads(gzip.decompress(files[B1_FILE])) == B1


def _release(assets):
    return {"id": 7, "assets": [{"name": n, "id": i} for n, i in assets]}


@respx.mock
def test_publish_replaces_assets_manifest_last():
    respx.get(f"{API}/repos/{REPO}/releases/tags/{TAG}").mock(
        return_value=httpx.Response(200, json=_release([(B1_FILE, 1), (MANIFEST_FILE, 3)]))
    )
    deletes = respx.delete(url__regex=r".*/releases/assets/\d+$").mock(
        return_value=httpx.Response(204)
    )
    uploads = respx.post(url__startswith=f"{UPLOADS}/repos/{REPO}/releases/7/assets").mock(
        return_value=httpx.Response(201, json={})
    )
    files = {B1_FILE: b"a", B2_FILE: b"b", MANIFEST_FILE: b"{}"}
    with httpx.Client() as http:
        publish(http, TOKEN, REPO, files)
    assert deletes.call_count == 2
    names = [c.request.url.params["name"] for c in uploads.calls]
    assert names == [B1_FILE, B2_FILE, MANIFEST_FILE]
    assert uploads.calls[0].request.headers["Authorization"] == f"Bearer {TOKEN}"
    last = respx.calls.last.request
    assert last.method == "POST" and last.url.params["name"] == MANIFEST_FILE


@respx.mock
def test_publish_creates_release_when_missing():
    respx.get(f"{API}/repos/{REPO}/releases/tags/{TAG}").mock(return_value=httpx.Response(404))
    created = respx.post(f"{API}/repos/{REPO}/releases").mock(
        return_value=httpx.Response(201, json=_release([]))
    )
    respx.post(url__startswith=f"{UPLOADS}/").mock(return_value=httpx.Response(201, json={}))
    with httpx.Client() as http:
        publish(http, TOKEN, REPO, {MANIFEST_FILE: b"{}"})
    body = json.loads(created.calls.last.request.content)
    assert body["tag_name"] == TAG and body["prerelease"] is True


@pytest.fixture
def workdir(tmp_path, monkeypatch):
    monkeypatch.delenv("GITHUB_RELEASE_TOKEN", raising=False)
    days = weekdays(6)
    events = [e for day in days for e in trip(day, 8 * 3600)]
    db = write_db(tmp_path / "c.sqlite", events)
    b1 = tmp_path / "b1.json"
    b1.write_text(json.dumps(B1), encoding="utf-8")
    base = ["--db", str(db), "--b1", str(b1), "--repo", REPO, "--day", "2026-11-18"]
    return tmp_path, base


def test_missing_token_exits_2(workdir):
    tmp_path, base = workdir
    env = tmp_path / ".env"
    env.write_text("SEOUL_POSITION_API_KEY=x\n", encoding="utf-8")
    assert main([*base, "--env-file", str(env)]) == 2


@respx.mock
def test_token_never_logged(workdir, caplog, capsys):
    tmp_path, base = workdir
    env = tmp_path / ".env"
    env.write_text(f"GITHUB_RELEASE_TOKEN={TOKEN}\n", encoding="utf-8")
    respx.get(url__startswith=API).mock(return_value=httpx.Response(401, json={}))
    with caplog.at_level(logging.DEBUG):
        assert main([*base, "--env-file", str(env)]) == 1
    out = capsys.readouterr()
    assert TOKEN not in caplog.text and TOKEN not in out.out and TOKEN not in out.err
