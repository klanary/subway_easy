"""VM 배포 파일(systemd 단위와 설치 스크립트)이 설계 §2.3의 약속을 담고 있는지 본다."""

import re
import shutil
import subprocess
from pathlib import Path

import pytest

DEPLOY = Path(__file__).parents[2] / "ml" / "deploy"


def _read(name: str) -> str:
    return (DEPLOY / name).read_text(encoding="utf-8")


def test_collector_service_restarts_always_and_runs_the_module():
    unit = _read("subway-collector.service")
    assert "Restart=always" in unit
    assert re.search(r"^ExecStart=@SERVER_DIR@/\.venv/bin/python -m ml\.collector$", unit, re.M)
    assert "WorkingDirectory=@SERVER_DIR@" in unit and "User=@USER@" in unit
    assert "WantedBy=multi-user.target" in unit


def test_backup_runs_daily_at_0210_while_the_collector_rests():
    assert "OnCalendar=*-*-* 02:10:00" in _read("subway-collector-backup.timer")
    assert "Persistent=true" in _read("subway-collector-backup.timer")
    service = _read("subway-collector-backup.service")
    assert "Type=oneshot" in service and "-m ml.backup" in service


def test_no_unit_or_script_mentions_server_keys_or_contains_a_value():
    for path in DEPLOY.iterdir():
        text = path.read_text(encoding="utf-8")
        assert "SEOUL_REALTIME_API_KEY" not in text and "SEOUL_OPENAPI_KEY" not in text
        # 키 이름 뒤에는 값이 오지 않는다(빈 틀만 만든다).
        assert not re.search(r"SEOUL_(POSITION_API_KEY|SPARE_KEY_[12])=\S", text), path.name


def test_setup_script_sets_timezone_fills_placeholders_and_waits_for_keys():
    script = _read("setup_vm.sh")
    assert script.startswith("#!/usr/bin/env bash\nset -euo pipefail\n")
    assert "timedatectl set-timezone Asia/Seoul" in script
    assert "@SERVER_DIR@" in script and "@USER@" in script
    assert "chmod 600" in script
    assert "ml.collector --once --any-hour" in script  # 키가 있을 때만 한 바퀴 확인 뒤 서비스 시작
    assert "\r" not in script  # CRLF면 리눅스에서 실행되지 않는다


def test_setup_script_is_valid_bash():
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("bash 없음")
    done = subprocess.run([bash, "-n", str(DEPLOY / "setup_vm.sh")], capture_output=True)
    assert done.returncode == 0, done.stderr.decode(errors="replace")


def test_nightly_timer_runs_after_the_backup_and_before_collection():
    timer = _read("subway-ml-nightly.timer")
    assert "OnCalendar=*-*-* 02:40:00" in timer and "Persistent=true" in timer
    assert "WantedBy=timers.target" in timer
    assert "02:40" > "02:10"  # 백업(02:10) 뒤, 수집 시작(05:00) 전


def test_nightly_service_runs_observe_mode_with_limits():
    service = _read("subway-ml-nightly.service")
    assert re.search(r"^ExecStart=@SERVER_DIR@/\.venv/bin/python -m ml\.nightly$", service, re.M)
    assert "Type=oneshot" in service and "User=@USER@" in service
    assert "After=subway-collector-backup.service" in service
    assert "MemoryMax=500M" in service and "Nice=10" in service
    assert "--enforce" not in service  # 적용 모드는 기준값을 맞춘 뒤 사용자가 켠다


def test_setup_script_installs_the_nightly_units():
    script = _read("setup_vm.sh")
    assert "subway-ml-nightly.service" in script and "subway-ml-nightly.timer" in script
    assert "enable --quiet --now subway-ml-nightly.timer" in script
    assert "journalctl -u subway-ml-nightly" in script and "changes.md" in script


def test_official_timer_runs_after_collection_ends_and_before_the_backup():
    timer = _read("subway-ml-official.timer")
    assert "OnCalendar=*-*-* 01:30:00" in timer and "Persistent=true" in timer


def test_official_service_refreshes_with_limits_and_names_no_key():
    service = _read("subway-ml-official.service")
    assert "-m ml.b0 --refresh" in service and "Type=oneshot" in service
    assert "MemoryMax=300M" in service and "KEY" not in service
    assert "User=@USER@" in service and "WorkingDirectory=@SERVER_DIR@" in service


def test_setup_script_installs_the_official_units():
    script = _read("setup_vm.sh")
    assert "subway-ml-official.service" in script and "subway-ml-official.timer" in script
    assert "enable --quiet --now subway-ml-official.timer" in script
    assert "journalctl -u subway-ml-official" in script


def test_setup_script_units_all_exist():
    # 유닛 목록에 "\n" 같은 글자가 섞이면 bash가 없는 유닛을 찾다 set -e로 멈춘다.
    script = _read("setup_vm.sh")
    loop = re.search(r"for unit in (.*?); do", script, re.S)
    assert loop is not None
    names = [word for word in loop.group(1).split() if word != "\\"]  # 줄 이음 기호만 뺀다
    assert names and all((DEPLOY / name).is_file() for name in names), names


def test_publish_timer_runs_daily():
    # 운행표는 매일, B1·B2는 수·일만 새로 만든다(경로 설계 2026-11-18 §5.1).
    timer = _read("subway-ml-publish.timer")
    assert "OnCalendar=*-*-* 03:30:00" in timer and "Persistent=true" in timer


def test_publish_service_names_no_key():
    service = _read("subway-ml-publish.service")
    assert "-m ml.publish" in service and "Type=oneshot" in service
    assert "MemoryMax=500M" in service and "TOKEN" not in service and "KEY" not in service
    assert "User=@USER@" in service and "WorkingDirectory=@SERVER_DIR@" in service


def test_setup_script_installs_publish_units():
    script = _read("setup_vm.sh")
    assert "subway-ml-publish.service" in script and "subway-ml-publish.timer" in script
    assert "enable --quiet --now subway-ml-publish.timer" in script
    assert "journalctl -u subway-ml-publish" in script and "GITHUB_RELEASE_TOKEN" in script
