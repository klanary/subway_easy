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
    assert "ml.collector --once" in script  # 키가 있을 때만 한 바퀴 확인 뒤 서비스 시작
    assert "\r" not in script  # CRLF면 리눅스에서 실행되지 않는다


def test_setup_script_is_valid_bash():
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("bash 없음")
    done = subprocess.run([bash, "-n", str(DEPLOY / "setup_vm.sh")], capture_output=True)
    assert done.returncode == 0, done.stderr.decode(errors="replace")
