#!/usr/bin/env bash
set -euo pipefail

# 리눅스 VM(Ubuntu 22.04+ 기준)에 열차 위치 수집기를 설치한다. 여러 번 돌려도 된다.
#
#   git clone <저장소 주소> ~/subway_easy
#   bash ~/subway_easy/server/ml/deploy/setup_vm.sh
#
# 하는 일: 시간대 KST, Python 가상 환경과 의존성, .env 틀(값은 넣지 않는다),
#          systemd 서비스(수집기)와 타이머(매일 02:10 백업) 등록.
# 키 값은 이 스크립트가 다루지 않는다. .env에 직접 넣는다.

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_USER="$(id -un)"
ENV_FILE="$SERVER_DIR/.env"
UNIT_DIR="/etc/systemd/system"
KEY_NAMES=(SEOUL_POSITION_API_KEY SEOUL_SPARE_KEY_1 SEOUL_SPARE_KEY_2)

echo "== 1/5 시간대와 패키지"
sudo timedatectl set-timezone Asia/Seoul
sudo apt-get update -qq
sudo apt-get install -y -qq python3 python3-venv sqlite3

if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 12))'; then
  echo "Python 3.12 이상이 필요합니다. 지금: $(python3 --version)" >&2
  echo "Ubuntu 24.04를 쓰거나 python3.12를 따로 설치한 뒤 다시 실행하세요." >&2
  exit 1
fi

echo "== 2/5 가상 환경과 의존성 ($SERVER_DIR)"
cd "$SERVER_DIR"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install --quiet --upgrade pip
.venv/bin/pip install --quiet -e .

echo "== 3/5 .env 틀"
if [ ! -f "$ENV_FILE" ]; then
  : > "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"
for name in "${KEY_NAMES[@]}"; do
  grep -q "^${name}=" "$ENV_FILE" || echo "${name}=" >> "$ENV_FILE"
done

echo "== 4/5 systemd 등록"
for unit in subway-collector.service subway-collector-backup.service subway-collector-backup.timer; do
  sed -e "s|@SERVER_DIR@|$SERVER_DIR|g" -e "s|@USER@|$RUN_USER|g" \
    "$SERVER_DIR/ml/deploy/$unit" | sudo tee "$UNIT_DIR/$unit" > /dev/null
done
sudo systemctl daemon-reload
sudo systemctl enable --quiet subway-collector.service
sudo systemctl enable --quiet --now subway-collector-backup.timer

echo "== 5/5 키 확인과 시작"
# 값은 보지 않고, 세 이름 중 값이 있는 줄이 하나라도 있는지만 본다.
has_key=no
for name in "${KEY_NAMES[@]}"; do
  if grep -Eq "^${name}=.+" "$ENV_FILE"; then
    has_key=yes
  fi
done

if [ "$has_key" = no ]; then
  cat <<EOF

아직 키가 없어 수집기를 시작하지 않았습니다.
  1) nano $ENV_FILE   # ${KEY_NAMES[0]}= 뒤에 값을 넣는다(예비 키는 있으면 넣는다)
  2) bash $SERVER_DIR/ml/deploy/setup_vm.sh   # 다시 실행하면 확인 뒤 시작한다
서버와 같은 키를 넣으면 한도를 함께 씁니다(수집기가 한도에 걸리면 앱의 실시간 도착도 멈춥니다).
EOF
  exit 0
fi

echo "한 바퀴 불러 확인합니다(약 30초)..."
.venv/bin/python -m ml.collector --once --log-file ""
sudo systemctl restart subway-collector.service
sleep 2
systemctl --no-pager --lines=5 status subway-collector.service || true

cat <<EOF

끝났습니다. 수집기는 05:00~01:00 KST에 30초마다 돌고, 죽으면 다시 뜹니다.
  상태:  systemctl status subway-collector
  로그:  journalctl -u subway-collector -n 50   /   tail $SERVER_DIR/ml_data/collector.log
  사용량: sqlite3 $SERVER_DIR/ml_data/collector.sqlite "SELECT * FROM key_usage ORDER BY day DESC LIMIT 6;"
  백업:  systemctl list-timers subway-collector-backup.timer   ($SERVER_DIR/ml_data/backups)
EOF
