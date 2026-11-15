# 열차 위치 수집기 운영 안내

설계는 `docs/ml/2026-11-13-train-eta-model-plan.md` §2, 코드는 `server/ml/`에 있다. 이 문서는 수집기를 **무료 상시 VM**(리눅스)에 띄우는 방법과, VM이 준비되기 전에 **개발 PC**(Windows)에서 돌리는 방법을 적는다.

## 1. 수집기가 하는 일

- 서울시 실시간 열차 위치 API(`realtimePosition`)를 19개 호선에 대해 30초마다 부른다(05:00~다음 날 01:00 KST). 하루 약 4.6만 회.
- 결과를 SQLite 한 파일(`server/ml_data/collector.sqlite`)에 쌓는다. 표는 `raw_snapshots`(응답 원문, zlib 압축), `events`(정리한 이벤트), `station_map`(역 잇기), `key_usage`(키별 호출 수) 네 개다.
- 키는 `SEOUL_POSITION_API_KEY` → `SEOUL_SPARE_KEY_1` → `SEOUL_SPARE_KEY_2` 순서로만 쓴다. `ERROR-337`(일일 한도)이 오면 그 키는 그날 쉬고 다음 키로 넘어간다. 세 키가 모두 막히면 자정까지 쉰다.
- 서버가 쓰는 `SEOUL_REALTIME_API_KEY`와 `SEOUL_OPENAPI_KEY`는 읽지 않는다. 키 값은 로그와 DB에 남지 않는다(이름 `position`·`spare1`·`spare2`만 남는다).
- 시간 계산은 코드 안에서 KST로 한다. VM의 시간대가 달라도 수집 시간은 맞지만, 아래 백업 타이머는 VM 시간대를 따르므로 KST로 맞춘다.

## 2. VM 준비 (사용자가 할 일)

Oracle Cloud Always Free 같은 상시 VM을 기준으로 적었다. Ubuntu 24.04(Python 3.12 포함)를 권한다. 아래에서 `ubuntu`는 VM 계정 이름이고, 다르면 바꾼다.

```bash
sudo timedatectl set-timezone Asia/Seoul
sudo apt update && sudo apt install -y python3-venv git sqlite3
git clone <저장소 주소> ~/subway_easy
cd ~/subway_easy/server
python3 -m venv .venv
.venv/bin/pip install -e .
```

키를 넣는다. **값은 직접 입력한다.** 수집기에 필요한 것은 아래 세 줄뿐이고, 예비 키가 없으면 그 줄은 비워 둔다. 서버용 키(`SEOUL_REALTIME_API_KEY` 등)는 VM에 넣지 않는다.

```bash
cat > ~/subway_easy/server/.env <<'EOF'
SEOUL_POSITION_API_KEY=
SEOUL_SPARE_KEY_1=
SEOUL_SPARE_KEY_2=
EOF
nano ~/subway_easy/server/.env     # 값을 채운다
chmod 600 ~/subway_easy/server/.env
```

한 바퀴만 돌려 확인한다. 오류 없이 끝나고 행 수가 나오면 된다.

```bash
cd ~/subway_easy/server
.venv/bin/python -m ml.collector --once
sqlite3 ml_data/collector.sqlite "SELECT COUNT(*) FROM events; SELECT * FROM key_usage;"
```

## 3. `systemd` 서비스

`/etc/systemd/system/subway-collector.service`:

```ini
[Unit]
Description=지금출발 열차 위치 수집기
After=network-online.target
Wants=network-online.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/subway_easy/server
ExecStart=/home/ubuntu/subway_easy/server/.venv/bin/python -m ml.collector
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now subway-collector
systemctl status subway-collector
journalctl -u subway-collector -n 50
```

- `Restart=always`라서 죽거나 VM이 다시 켜져도 다시 뜬다. 다시 뜰 때 그날 한도에 걸린 키는 `key_usage`에서 읽어 다시 쓰지 않는다.
- 01:00~05:00에는 프로세스가 떠 있는 채로 쉰다(끄지 않는다).
- 로그는 `journalctl`과 `server/ml_data/collector.log`(5MB씩 5개까지 돌려 씀) 두 곳에 남는다. 10분마다 요약 한 줄(호출, 오류, 새 이벤트 수)이 찍힌다.
- 코드를 고친 뒤에는 `git pull && sudo systemctl restart subway-collector`.

## 4. 매일 백업

`python -m ml.backup`은 세 가지를 한다.

1. DB 전체를 `server/ml_data/backups/collector-YYYYMMDD.sqlite.gz`로 복사한다(날짜는 어제. 수집기가 돌고 있어도 된다).
2. 백업 파일은 최근 7개만 남긴다(`--keep`).
3. 수집 DB에서 14일보다 오래된 raw 응답을 지운다(`--raw-days`). `events`는 지우지 않는다. 지워진 raw는 그 전날들의 백업 파일에 남아 있다.

`/etc/systemd/system/subway-collector-backup.service`:

```ini
[Unit]
Description=지금출발 수집 DB 백업

[Service]
Type=oneshot
User=ubuntu
WorkingDirectory=/home/ubuntu/subway_easy/server
ExecStart=/home/ubuntu/subway_easy/server/.venv/bin/python -m ml.backup
```

`/etc/systemd/system/subway-collector-backup.timer`:

```ini
[Unit]
Description=지금출발 수집 DB 백업(매일 02:10 KST)

[Timer]
OnCalendar=*-*-* 02:10:00
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now subway-collector-backup.timer
systemctl list-timers subway-collector-backup.timer
```

### 백업을 VM 밖으로 옮기기 (사용자가 할 일)

VM이 지워지면 백업도 함께 사라진다. 일주일에 한 번 이상 개발 PC로 가져온다. PC의 PowerShell에서:

```powershell
scp -i <개인 키 파일> "ubuntu@<VM 주소>:subway_easy/server/ml_data/backups/collector-*.sqlite.gz" "$HOME\subway-backups\"
```

- 백업은 최근 7개만 VM에 남으므로, 7일 안에 가져오지 않으면 그 사이 raw는 14일 보관 기간이 지난 뒤 사라진다(`events`는 수집 DB에 계속 남는다).
- 가져온 파일은 `gzip -d` 또는 7-Zip으로 풀면 그대로 SQLite 파일이다. 분석 스크립트에 `--db <푼 파일>`로 넘긴다.

### 디스크

- 2026-11-15 일요일 낮 1시간 실측(§7 관찰 기록)으로 어림한 값을 `docs/ml/2026-11-15-first-run-observations.md`에 적었다. 그 문서의 "크기" 절을 본다.
- `df -h`로 남은 공간을 가끔 본다. DB가 20GB를 넘으면 날짜별 Parquet로 옮기는 작업을 한다(이번 범위 밖).

## 5. 상태 확인

```bash
cd ~/subway_easy/server
sqlite3 ml_data/collector.sqlite "SELECT * FROM key_usage ORDER BY day DESC LIMIT 6;"
sqlite3 ml_data/collector.sqlite "SELECT service_date, COUNT(*) FROM events GROUP BY 1 ORDER BY 1 DESC LIMIT 7;"
sqlite3 ml_data/collector.sqlite "SELECT line, MAX(received_at) FROM raw_snapshots GROUP BY 1;"
sqlite3 ml_data/collector.sqlite "SELECT line, statn_id, statn_nm FROM station_map WHERE station_id IS NULL;"
```

- `key_usage.quota_hit_at`에 값이 있으면 그 키가 그날 한도에 걸린 것이다. **그 시각과 그때까지의 `calls`가 그 키의 실제 일일 한도**이므로 설계 문서 §0에 적는다.
- 마지막 질의에 나오는 역은 우리 역 표와 잇지 못한 이름이다. 서버의 별칭 표(`stations.sqlite`의 `aliases`)에 더한 뒤 수집기를 다시 띄우면, 이미 쌓인 이벤트까지 이어진다.

## 6. 개발 PC(Windows)에서 돌리기 — VM 준비 전의 대안

### 한 번 돌려 보기

```powershell
cd "<저장소>\server"
.venv\Scripts\python -m ml.collector --max-minutes 75
```

`Ctrl+C`로 멈춘다(돌던 호출을 끝내고 닫는다).

### 작업 스케줄러

PC가 켜져 있는 동안 계속 돌게 한다. 로그온할 때와 매일 04:55에 시작하고, 이미 돌고 있으면 새로 띄우지 않는다. PowerShell에서 한 번 실행한다(`<저장소>`를 실제 경로로 바꾼다. 경로에 한글·공백이 있어도 된다).

```powershell
$server  = "<저장소>\server"
$action  = New-ScheduledTaskAction -Execute "$server\.venv\Scripts\python.exe" -Argument "-m ml.collector" -WorkingDirectory $server
$trigger = @((New-ScheduledTaskTrigger -AtLogOn), (New-ScheduledTaskTrigger -Daily -At 04:55))
$set     = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable
Register-ScheduledTask -TaskName "SubwayCollector" -Action $action -Trigger $trigger -Settings $set

$backup  = New-ScheduledTaskAction -Execute "$server\.venv\Scripts\python.exe" -Argument "-m ml.backup" -WorkingDirectory $server
Register-ScheduledTask -TaskName "SubwayCollectorBackup" -Action $backup -Trigger (New-ScheduledTaskTrigger -Daily -At 02:10) -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable)
```

- 멈추기: `Stop-ScheduledTask SubwayCollector`. 지우기: `Unregister-ScheduledTask SubwayCollector`.
- PC가 절전에 들어가면 그동안은 수집이 빈다. 절전을 끄거나, 비는 시간을 감수한다. 분석은 "관측 일수 하한"으로 빈 칸을 걸러 낸다.
- PC와 VM에서 **동시에 돌리지 않는다**. 같은 키로 호출이 두 배가 된다. VM이 준비되면 PC 작업을 지운다. PC에서 모은 DB는 따로 보관하고, 합치려면 `events`를 같은 규칙(가장 이른 `event_at`)으로 넣어야 한다(합치는 스크립트는 아직 없다).

## 7. 문제 해결

| 증상 | 볼 곳 | 조치 |
|---|---|---|
| `수집기 키가 없습니다` | `server/.env` | 세 키 이름 중 하나 이상에 값을 넣는다 |
| 로그에 `쉬는 중: 쓸 수 있는 키가 없음` | `key_usage.quota_hit_at` | 세 키가 모두 한도에 걸렸다. 자정에 다시 시작한다. 예비 키를 더하거나 주기·호선을 줄인다(`--period 60`, `--exclude`) |
| `위치 API 호출 실패: ConnectTimeout` | 네트워크 | 다음 주기에 다시 부른다. 계속되면 VM의 외부 접속을 확인한다 |
| `위치 API 본문을 읽을 수 없음` | 서울시 서버 점검 | 기다린다. `key_usage.errors`에 세어진다 |
| `역을 잇지 못함` | `station_map` | §5의 별칭 추가 |
| 디스크가 찬다 | `du -sh ml_data` | 백업을 PC로 옮기고 `--keep`·`--raw-days`를 줄인다 |
