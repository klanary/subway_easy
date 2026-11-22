"""수·일 실측 결과물 내보내기(설계 2026-11-17 §4.1).

- 서버용 사본은 시간표 API가 없는 노선만, 필요한 칸만 담는다(Render 512MB, 최종 리뷰 #1).
- 데이터 파일은 빌드마다 이름이 다르고, manifest를 바꾼 뒤에야 옛 파일을 지운다(리뷰 #5).
"""

import gzip
import hashlib
import json
import logging
from datetime import datetime

import httpx
import pytest
import respx

from app.core.clock import KST
from app.services.measured_store import B1_PREFIX, B2_PREFIX, MANIFEST_FILE, TRIPS_PREFIX
from ml.publish import (
    API,
    TAG,
    UPLOADS,
    build_files,
    load_b1_for_publish,
    main,
    publish,
    server_b1,
    server_b2,
)
from tests.ml_helpers import trip, weekdays, write_db

REPO = "owner/repo"
TOKEN = "ghp_SECRET_TOKEN_VALUE"
NOW = datetime(2026, 11, 18, 3, 30, tzinfo=KST)


def entry(secs, train_no=None, dest="광교", express=0):
    return {"secs": secs, "train_no": train_no, "n_days": 3, "dest_name": dest,
            "express": express, "first_seen": "2026-11-16", "last_seen": "2026-11-17",
            "official": False}  # fmt: skip


RAW_B1 = {
    "version": 2, "kind": "b1", "as_of": "2026-11-17", "built_at": "2026-11-18T02:41:00+09:00",
    "modes": {"5호선": "train_no", "신분당선": "slots"},
    "scopes": {
        "1|신분당선|0|7": {"state": "suspect"}, "1|신분당선|0|8": {"state": "changed"},
        "1|5호선|0|7": {"state": "suspect"},
    },
    "stations": {"5호선|A": {"station_id": "마포", "name": "마포"},
                 "신분당선|B": {"station_id": "강남", "name": "강남"}},
    "cells": {"1|5호선|0|A": [entry(25000, "5101", "방화")],
              "1|신분당선|0|B": [entry(25500, None, "광교", 1), entry(25860)]},
}  # fmt: skip
RAW_B2 = {
    "version": 1, "kind": "b2",
    "hops": {"5호선|0|A|C|0": {"median": 120.0, "p10": 100, "p90": 140, "n": 9}},
    "hops_day": {}, "hops_hour": {"5호선|0|A|C|0|1|8": {"median": 110.5, "n": 9}},
    "dwell": {"5호선|A": {"median": 30, "n": 9}},
}  # fmt: skip


def test_server_b1_keeps_only_lines_without_official_timetable():
    out = server_b1(RAW_B1)
    assert out["kind"] == "b1-server" and out["version"] == 1 and out["as_of"] == "2026-11-17"
    assert list(out["cells"]) == ["1|신분당선|0|B"]
    assert out["modes"] == {"신분당선": "slots"}
    assert list(out["stations"]) == ["5호선|A", "신분당선|B"]  # 역 연결은 1~9호선도(위치 도착 계산)
    assert out["scopes"] == {"1|신분당선|0|7": "suspect"}  # 서버가 거르는 상태만


def test_server_b1_entries_are_compact():
    out = server_b1(RAW_B1)
    first, second = out["cells"]["1|신분당선|0|B"]
    assert first == [25500, None, out["dests"].index("광교"), 1]
    assert second[0] == 25860 and second[2] == first[2]  # 행선지 이름은 한 번만


def test_server_b2_keeps_only_hop_medians():
    out = server_b2(RAW_B2)
    assert out["kind"] == "b2-server" and out["version"] == 1
    assert out["hops"] == {"5호선|0|A|C|0": 120.0}
    assert out["hops_hour"] == {"5호선|0|A|C|0|1|8": 110.5} and out["hops_day"] == {}
    assert "dwell" not in out


def test_manifest_names_unique_files_with_sha256():
    files = build_files(RAW_B1, RAW_B2, now=NOW)
    names = list(files)
    assert names[-1] == MANIFEST_FILE and len(names) == 3
    manifest = json.loads(files[MANIFEST_FILE])
    assert manifest["built_at"].startswith("2026-11-18T03:30") and manifest["as_of"] == "2026-11-17"
    assert manifest["b1"]["name"].startswith(B1_PREFIX) and "20261118" in manifest["b1"]["name"]
    assert manifest["b2"]["name"].startswith(B2_PREFIX)
    for part in ("b1", "b2"):
        body = files[manifest[part]["name"]]
        assert manifest[part]["sha256"] == hashlib.sha256(body).hexdigest()
    assert json.loads(gzip.decompress(files[manifest["b1"]["name"]]))["kind"] == "b1-server"


def _release(assets):
    return {"id": 7, "assets": [{"name": n, "id": i} for n, i in assets]}


@respx.mock
def test_publish_uploads_new_then_manifest_then_deletes_old():
    old = [(f"{B1_PREFIX}20261115T033000.json.gz", 1), (f"{B2_PREFIX}20261115T033000.json.gz", 2),
           (MANIFEST_FILE, 3), ("other.txt", 4)]  # fmt: skip
    respx.get(f"{API}/repos/{REPO}/releases/tags/{TAG}").mock(
        return_value=httpx.Response(200, json=_release(old))
    )
    respx.delete(url__regex=r".*/releases/assets/\d+$").mock(return_value=httpx.Response(204))
    respx.post(url__startswith=f"{UPLOADS}/repos/{REPO}/releases/7/assets").mock(
        return_value=httpx.Response(201, json={})
    )
    new_b1, new_b2 = f"{B1_PREFIX}20261118T033000.json.gz", f"{B2_PREFIX}20261118T033000.json.gz"
    files = {new_b1: b"a", new_b2: b"b", MANIFEST_FILE: b"{}"}
    with httpx.Client() as http:
        publish(http, TOKEN, REPO, files)
    steps = [
        (
            c.request.method,
            c.request.url.params.get("name") or c.request.url.path.rsplit("/", 1)[-1],
        )
        for c in respx.calls
        if c.request.method != "GET"
    ]
    assert steps == [("POST", new_b1), ("POST", new_b2), ("DELETE", "3"), ("POST", MANIFEST_FILE),
                     ("DELETE", "1"), ("DELETE", "2")]  # fmt: skip
    assert respx.calls[1].request.headers["Authorization"] == f"Bearer {TOKEN}"


@respx.mock
def test_failed_upload_leaves_old_assets_in_place():
    respx.get(f"{API}/repos/{REPO}/releases/tags/{TAG}").mock(
        return_value=httpx.Response(
            200, json=_release([(f"{B1_PREFIX}old.json.gz", 1), (MANIFEST_FILE, 3)])
        )
    )
    deletes = respx.delete(url__regex=r".*/releases/assets/\d+$").mock(
        return_value=httpx.Response(204)
    )
    respx.post(url__startswith=f"{UPLOADS}/").mock(return_value=httpx.Response(500))
    with httpx.Client() as http, pytest.raises(httpx.HTTPStatusError):
        publish(http, TOKEN, REPO, {f"{B1_PREFIX}new.json.gz": b"a", MANIFEST_FILE: b"{}"})
    assert deletes.call_count == 0


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
    b1.write_text(json.dumps(RAW_B1, ensure_ascii=False), encoding="utf-8")
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


TRIPS = {"kind": "trips-server", "version": 1, "built_on": "2026-11-18", "stations": [],
         "day_types": {"1": [], "2": [], "3": []}}  # fmt: skip
PREVIOUS = {
    "built_at": "2026-11-16T03:30:00+09:00", "as_of": "2026-11-15",
    "b1": {"name": f"{B1_PREFIX}20261116T033000.json.gz", "sha256": "a" * 64},
    "b2": {"name": f"{B2_PREFIX}20261116T033000.json.gz", "sha256": "b" * 64},
    "trips": {"name": f"{TRIPS_PREFIX}20261116T033000.json.gz", "sha256": "c" * 64,
              "built_on": "2026-11-16"},
}  # fmt: skip


def test_manifest_includes_trips():
    files = build_files(RAW_B1, RAW_B2, now=NOW, trips=TRIPS)
    manifest = json.loads(files[MANIFEST_FILE])
    name = manifest["trips"]["name"]
    assert name.startswith(TRIPS_PREFIX) and manifest["trips"]["built_on"] == "2026-11-18"
    assert manifest["trips"]["sha256"] == hashlib.sha256(files[name]).hexdigest()
    assert list(files)[-1] == MANIFEST_FILE


def test_reuse_keeps_previous_b1_b2_entries():
    files = build_files(None, None, now=NOW, trips=TRIPS, previous=PREVIOUS)
    manifest = json.loads(files[MANIFEST_FILE])
    assert manifest["b1"] == PREVIOUS["b1"] and manifest["b2"] == PREVIOUS["b2"]
    assert manifest["as_of"] == "2026-11-15"
    assert [n for n in files if n != MANIFEST_FILE] == [manifest["trips"]["name"]]


def _mock_release(old_names):
    respx.get(f"https://github.com/{REPO}/releases/download/{TAG}/{MANIFEST_FILE}").mock(
        return_value=httpx.Response(200, json=PREVIOUS)
    )
    respx.get(f"{API}/repos/{REPO}/releases/tags/{TAG}").mock(
        return_value=httpx.Response(200, json=_release([(n, i) for i, n in enumerate(old_names)]))
    )
    deletes = respx.delete(url__regex=r".*/releases/assets/\d+$").mock(
        return_value=httpx.Response(204)
    )
    uploads = respx.post(url__startswith=f"{UPLOADS}/").mock(
        return_value=httpx.Response(201, json={})
    )
    return deletes, uploads


@respx.mock
def test_weekday_reuses_previous_b1_b2_and_uploads_only_trips(workdir):
    tmp_path, base = workdir
    env = tmp_path / ".env"
    env.write_text(f"GITHUB_RELEASE_TOKEN={TOKEN}\n", encoding="utf-8")
    old = [PREVIOUS["b1"]["name"], PREVIOUS["b2"]["name"], PREVIOUS["trips"]["name"], MANIFEST_FILE]
    deletes, uploads = _mock_release(old)
    args = [*base, "--env-file", str(env), "--day", "2026-11-17",  # 화요일
            "--timetable-cache", str(official_cache(tmp_path)),
            "--b2-cache", str(tmp_path / "b2.json")]  # fmt: skip
    assert main(args) == 0
    names = [c.request.url.params["name"] for c in uploads.calls]
    assert len(names) == 2 and names[0].startswith(TRIPS_PREFIX) and names[1] == MANIFEST_FILE
    deleted = {c.request.url.path.rsplit("/", 1)[-1] for c in deletes.calls}
    assert deleted == {"2", "3"}  # 옛 trips와 옛 manifest만, 옛 B1·B2(0, 1)는 그대로


@respx.mock
def test_wednesday_rebuilds_all(workdir):
    tmp_path, base = workdir
    env = tmp_path / ".env"
    env.write_text(f"GITHUB_RELEASE_TOKEN={TOKEN}\n", encoding="utf-8")
    _, uploads = _mock_release([MANIFEST_FILE])
    args = [*base, "--env-file", str(env), "--day", "2026-11-18",  # 수요일
            "--timetable-cache", str(official_cache(tmp_path)),
            "--b2-cache", str(tmp_path / "b2.json")]  # fmt: skip
    assert main(args) == 0
    names = [c.request.url.params["name"] for c in uploads.calls]
    prefixes = [n.split("-")[0] for n in names[:-1]]
    assert sorted(prefixes) == ["measured_timetable", "segment_times", "trips"]
    assert (tmp_path / "b2.json").is_file()  # 평일 운행표 만들기에 다시 쓴다


def official_cache(tmp_path):
    """5호선 마포(2529)→여의나루(2528) 평일 상행 열차 하나뿐인 공식 시간표 캐시."""
    cache = tmp_path / "timetable_cache"
    cache.mkdir(exist_ok=True)
    for code, (arr, dep) in {
        "2529": ("08:00:00", "08:00:30"),
        "2528": ("08:02:00", "08:02:30"),
    }.items():
        row = {"TRAIN_NO": "5101", "ARRIVETIME": arr, "LEFTTIME": dep, "EXPRESS_YN": "G",
               "SUBWAYENAME": "방화", "INOUT_TAG": "1"}  # fmt: skip
        (cache / f"{code}_1_1.json").write_text(json.dumps([row], ensure_ascii=False), "utf-8")
    return cache


@respx.mock
def test_refuses_to_publish_without_official_trips(workdir):
    # 캐시가 비었거나 경로가 틀리면 1~9호선이 없는 운행표가 올라간다(최종 리뷰 #6).
    tmp_path, base = workdir
    env = tmp_path / ".env"
    env.write_text(f"GITHUB_RELEASE_TOKEN={TOKEN}\n", encoding="utf-8")
    _, uploads = _mock_release([MANIFEST_FILE])
    args = [*base, "--env-file", str(env), "--day", "2026-11-18",
            "--timetable-cache", str(tmp_path / "none"),
            "--b2-cache", str(tmp_path / "b2.json")]  # fmt: skip
    assert main(args) == 1
    assert uploads.call_count == 0


def test_load_b1_for_publish_drops_official_lines(tmp_path):
    # VM 메모리(1GB): 서버용 사본도 운행표도 1~9호선 칸은 쓰지 않으므로 읽자마자 덜어 낸다(리뷰 #5).
    path = tmp_path / "b1.json"
    path.write_text(json.dumps(RAW_B1, ensure_ascii=False), encoding="utf-8")
    b1 = load_b1_for_publish(path)
    assert list(b1["cells"]) == ["1|신분당선|0|B"]
    assert list(b1["stations"]) == ["5호선|A", "신분당선|B"]  # 역 연결은 작아서 둔다
    assert server_b1(b1) == server_b1(RAW_B1)


def test_server_b1_keeps_stations_of_official_lines():
    """1~9호선도 역 연결은 서버에 보낸다(열차 위치로 도착 계산할 때 구간 시간을 찾는다)."""
    from ml.publish import server_b1

    b1 = {
        "kind": "b1",
        "version": 2,
        "as_of": "2026-11-21",
        "modes": {"2호선": "slots"},
        "scopes": {},
        "cells": {"1|2호선|0|1002000201": [{"secs": 30000, "n_days": 3, "dest_name": "성수"}]},
        "stations": {"2호선|1002000201": {"station_id": "시청", "name": "시청"}},
    }
    out = server_b1(b1)
    assert out["stations"]["2호선|1002000201"] == {"station_id": "시청", "name": "시청"}
    assert not any("2호선" in key for key in out["cells"])
