# 열차 위치 수집기와 B1·B2 기준 모델 구현 계획

**Goal:** 서울시 열차 위치 API를 30초마다 모으는 수집기와, 모은 데이터로 실측 시간표(B1)·구간 시간 추정(B2)을 만들고 공식 시간표(B0)와 비교 평가하는 오프라인 파이프라인을 만든다.

**Architecture:** `server/ml/` 패키지 하나. 수집기는 동기 `httpx.Client` 루프 하나가 SQLite 한 파일에 쓴다(호선마다 한 트랜잭션). 분석·모델·평가는 SQLite `events` 표만 읽는 순수 Python(표준 라이브러리 `statistics`)이고, 결과물은 JSON으로 `server/data/`에 쓴다. 서버 코드(`app/`)는 바꾸지 않고 `mask_url`, `StationIndex`, `week_tag`, `KST`만 가져다 쓴다.

**Tech Stack:** Python 3.12+, httpx, sqlite3, holidays(기존 의존성), pytest + respx. 새 의존성 없음.

**Spec:** `docs/ml/2026-11-13-train-eta-model-plan.md` (§9가 구속, §10은 이 계획이 정함)

## Global Constraints

- 위치 API(`realtimePosition`) 하나만 쓴다. 요청: `http://swopenapi.seoul.go.kr/api/subway/{키}/json/realtimePosition/0/200/{호선명}`.
- 30초 주기, 05:00~01:00 KST. 한 주기 안에서 호선을 고르게 흩어 부른다.
- 키 순서 `position`(`SEOUL_POSITION_API_KEY`) → `spare1`(`SEOUL_SPARE_KEY_1`) → `spare2`(`SEOUL_SPARE_KEY_2`). 수집기 코드는 이 세 환경 변수 이름만 읽는다. `SEOUL_REALTIME_API_KEY`, `SEOUL_OPENAPI_KEY`는 읽지도 않는다.
- `ERROR-337`이면 그 키는 KST 자정까지 정지. 세 키가 모두 막히면 그날 수집 정지.
- SQLite 표 4개: `raw_snapshots`, `events`, `station_map`, `key_usage`. 키 값·URL은 DB·로그·커밋에 남기지 않는다. 키는 이름으로만 기록.
- `events` 중복 제거 키 `(service_date, line, train_no, statn_id, status)`, 다시 오면 가장 이른 `recptnDt`를 남긴다. 01시 이전 이벤트는 전날 서비스 날짜.
- 이상값은 `flag`만 달고 지우지 않는다: 받은 시각보다 30초 넘게 미래 → `future`, 10분(600초) 넘게 오래됨 → `stale`.
- 로그의 URL은 `app.clients.http.mask_url`을 거친다. 역 잇기는 `StationIndex.resolve(statnNm, 호선)`.
- 평가는 날짜로 나눈 시험 세트, MAE·P90·±60초·±120초 비율, B0 비교.
- 결과물은 `server/data/`. 서버는 읽기만(이번에는 서버 통합 없음).
- 테스트: `server/`에서 `.venv/Scripts/python -m pytest -q`(시작 249개). 네트워크 테스트는 기록한 응답(fixture)·respx.
- 작업마다 `docs/learning/62-…`부터 학습 노트 하나. 커밋은 `develop`, 푸시는 사용자에게 묻는다.

## §10에서 이 계획이 정한 것

| 항목 | 결정 |
|---|---|
| 모듈 위치 | `server/ml/` (테스트는 `server/tests/unit/test_ml_*.py`) |
| 동기·비동기 | 동기(`httpx.Client`, 시간 제한 5초, 재시도 없음 — 다음 주기가 재시도) |
| CLI | `python -m ml.collector --db --env-file --stations-db --lines --exclude --period --once --max-minutes --log-file` |
| 호선명 | 2026-11-15 15:05 확인: `1호선`~`9호선`, `경의중앙선`, `공항철도`, `경춘선`, `수인분당선`, `신분당선`, `우이신설선`, `서해선`, `경강선`, `신림선`, `GTX-A` — 19개 모두 `INFO-000`과 열차 목록을 돌려줌. 역 DB의 `line_name`과 같은 문자열 |
| raw 저장 | 응답 본문을 zlib으로 눌러 BLOB으로 저장(하루 약 4.6만 건) |
| raw 보관 | 수집 DB에는 14일. 그 전 것은 매일 백업 파일에 남는다 |
| 백업 | `python -m ml.backup`: `Connection.backup` → gzip `collector-YYYYMMDD.sqlite.gz`, 최근 7개 보관 |
| B1 관측 일수 하한 N | 3일(`--min-days`) |
| B1 시간대 묶음 폭 | 120초(`--slot-width`), 열차 번호가 날마다 다른 노선에만 씀 |
| B1 출발 시각 | `출발`(2) 이벤트. 없고 `도착`(1)만 있으면 도착 + 30초(`--fallback-dwell`) |
| B2 시간대 | 1시간 단위. 표본이 5개 미만이면 시간대 → 요일 구분 순으로 조건을 푼다 |
| Parquet 전환 | DB가 20GB를 넘으면(약 6개월 예상) — 이번 범위 밖 |

## Review Focus

1. 네트워크 예외 메시지에 키가 든 URL이 들어 있을 때 → 로그에 키가 남지 않는다(Task 3 테스트).
2. 응답이 JSON이 아니거나(HTML 오류 페이지) 본문 모양이 다를 때 → 오류로 세고 다음 호선으로 넘어간다(Task 3).
3. 337로 키가 막힌 뒤 수집기를 다시 띄울 때 → `key_usage`에서 그날 막힌 키를 되살려 다시 쓰지 않는다(Task 3).
4. 행에 `recptnDt`·`trainNo`·`statnId`가 없거나 형식이 틀릴 때 → 그 행만 건너뛰고 나머지는 저장한다(Task 2).
5. 날짜가 하루뿐이거나 빈 DB로 B1·평가를 돌릴 때 → 죽지 않고 "날짜 부족"을 알린다. 자정을 넘긴 이벤트(00:30)는 전날 서비스 날짜의 24시 이후 초로 다룬다(Task 6·7·9).

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `server/ml/keys.py` | 허용된 키 이름 3개, `.env` 읽기, `KeyRing`(순서·그날 정지) |
| `server/ml/store.py` | SQLite 스키마와 쓰기(`Store`) |
| `server/ml/parse.py` | 응답 코드, 행 → `Event`, 서비스 날짜, 이상값 표시 |
| `server/ml/collector.py` | `Collector.poll_line`, 주기 루프, CLI |
| `server/ml/backup.py` | 매일 백업, 오래된 백업·raw 지우기, CLI |
| `server/ml/dataset.py` | `events` 읽기, 열차 운행(run)·역 방문(visit) 만들기 |
| `server/ml/analyze.py` | 첫 주 분석(§3의 다섯 항목) → Markdown |
| `server/ml/b1.py` | 실측 시간표 빌더·조회 |
| `server/ml/b2.py` | 구간 시간 추정기 |
| `server/ml/b0.py` | 공식 시간표 캐시(파일)와 받기 스크립트 |
| `server/ml/evaluate.py` | 날짜 분할, 지표, B0 비교, 보고 |
| `docs/ml/collector.md` | VM `systemd`, 백업, 작업 스케줄러 |
| `docs/ml/2026-11-15-first-run-observations.md` | 1시간 실행 관찰 |

---

### Task 1: 키 고리(`ml/keys.py`)

**Produces:**
- `KEY_ENV: dict[str, str]` = `{"position": "SEOUL_POSITION_API_KEY", "spare1": "SEOUL_SPARE_KEY_1", "spare2": "SEOUL_SPARE_KEY_2"}`(순서 있음)
- `load_keys(env_file: str | Path | None, environ: Mapping[str, str]) -> dict[str, str]` — 값이 있는 키만, 위 순서. 환경 변수가 `.env`보다 먼저.
- `class KeyRing(keys: dict[str, str], blocked: dict[str, date] | None = None)`: `current(today: date) -> tuple[str, str] | None`, `block(name: str, today: date) -> None`, `names -> tuple[str, ...]`. `KEY_ENV`에 없는 이름이 오면 `ValueError`.

- [ ] 테스트(`tests/unit/test_ml_keys.py`): `.env`에 다섯 키가 다 있어도 `load_keys`는 세 이름만 돌려준다(실시간 키 값이 결과에 없음) / 빈 값은 빠짐 / 순서 position→spare1→spare2 / `block("position", d)` 뒤 `current(d)`는 spare1, `current(d+1)`은 다시 position / 모두 막히면 `None` / `KeyRing({"realtime": "x"})`는 `ValueError`.
- [ ] 실패 확인 → 구현 → 통과 → 학습 노트 62 → 커밋.

### Task 2: 저장소와 파싱(`ml/store.py`, `ml/parse.py`)

**Produces:**
- `parse.result_code(body: object) -> str | None` — `errorMessage.code` 또는 최상위 `code`.
- `parse.service_date(at: datetime) -> date` — KST 01:00 전이면 전날.
- `parse.Event`(frozen dataclass): `service_date: date, day_type: int(1 평일·2 토·3 휴일, app.services.timetable.week_tag), line: str, direction: int(0·1), train_no: str, statn_id: str, statn_nm: str, status: int(0~3), event_at: datetime, dest_statn_id: str | None, dest_name: str | None, express: int(directAt 그대로), last_train: int, flag: str | None`
- `parse.parse_rows(body: dict, line: str, received_at: datetime) -> tuple[list[Event], int]` — (이벤트, 건너뛴 행 수). `FUTURE_SEC = 30`, `STALE_SEC = 600`.
- `Store(path)`: WAL, `busy_timeout`. `add_snapshot(line, received_at, code, body_text)`, `upsert_events(events, station_ids: dict[str, str | None], first_seen_at) -> int`(새로 들어간 행 수), `map_station(line, statn_id, statn_nm, station_id, first_seen: date) -> bool`, `known_stations(line) -> dict[statn_id, station_id | None]`, `unmapped_stations() -> list[tuple[line, statn_id, statn_nm]]`, `record_call(day, key_name, *, error: bool)`, `mark_quota_hit(day, key_name, at)`, `blocked_keys(day) -> list[str]`, `commit()`, `close()`.

스키마:

```sql
CREATE TABLE raw_snapshots(id INTEGER PRIMARY KEY, line TEXT NOT NULL, received_at TEXT NOT NULL,
  code TEXT, body BLOB NOT NULL);          -- body = zlib.compress(응답 본문 UTF-8)
CREATE INDEX raw_received ON raw_snapshots(received_at);
CREATE TABLE events(service_date TEXT NOT NULL, day_type INTEGER NOT NULL, line TEXT NOT NULL,
  direction INTEGER NOT NULL, train_no TEXT NOT NULL, station_id TEXT, statn_id TEXT NOT NULL,
  status INTEGER NOT NULL, event_at TEXT NOT NULL, first_seen_at TEXT NOT NULL,
  dest_statn_id TEXT, dest_name TEXT, express INTEGER NOT NULL, last_train INTEGER NOT NULL, flag TEXT,
  PRIMARY KEY(service_date, line, train_no, statn_id, status));
CREATE TABLE station_map(line TEXT NOT NULL, statn_id TEXT NOT NULL, statn_nm TEXT NOT NULL,
  station_id TEXT, first_seen TEXT NOT NULL, PRIMARY KEY(line, statn_id));
CREATE TABLE key_usage(day TEXT NOT NULL, key_name TEXT NOT NULL, calls INTEGER NOT NULL DEFAULT 0,
  errors INTEGER NOT NULL DEFAULT 0, quota_hit_at TEXT, PRIMARY KEY(day, key_name));
```

시각은 모두 KST ISO 8601(`2026-11-15T15:03:41+09:00`).

- [ ] fixture: `tests/fixtures/seoul_position_line5.json`(2026-11-15 실제 응답), `seoul_position_quota.json`(337 모양), 기존 `seoul_realtime_nodata.json`의 `body` 모양 재사용.
- [ ] 테스트(`test_ml_parse.py`): 실제 응답 35행 → 이벤트 35개, 첫 행 `train_no="5603"`, `statn_id="1005000510"`, `direction=1`, `status=0` / `service_date`: 00:59 → 전날, 01:00 → 그날 / 일요일 → `day_type=3` / 받은 시각 +31초 → `future`, +30초 → `None`, −601초 → `stale` / `recptnDt` 없음·`trainNo` 빈 값·`trainSttus="9"` 행은 건너뛰고 수를 셈 / `result_code` 두 모양.
- [ ] 테스트(`test_ml_store.py`): 같은 키가 더 이른 `event_at`으로 오면 바뀌고 `first_seen_at`은 그대로, 더 늦으면 안 바뀜 / raw는 압축을 풀면 원문과 같음 / `record_call` 누적, `mark_quota_hit` 뒤 `blocked_keys` / `map_station`은 처음만 True / DB 파일 어디에도 키 문자열이 없음(Task 3에서 통합 확인).
- [ ] 구현 → 통과 → 학습 노트 63 → 커밋.

### Task 3: 수집기(`ml/collector.py`)

**Consumes:** Task 1·2 전부, `StationIndex.load`, `mask_url`, `SEOUL_REALTIME_BASE`, `KST`.

**Produces:**
- `LINES: tuple[str, ...]`(19개, 위 표 순서), `PERIOD_SEC = 30`
- `in_service_hours(now: datetime) -> bool` — KST 시 ≥ 5 또는 < 1.
- `class Collector(http, store, keys: KeyRing, index: StationIndex, clock=time.time, lines=LINES)`:
  - `poll_line(line: str) -> str` — 결과 코드(`INFO-000`, `INFO-200`, `ERROR-…`, `NO_KEY`, `NETWORK`, `HTTP_503`, `BAD_BODY`).
  - `run(*, period=PERIOD_SEC, sleep=time.sleep, should_stop=lambda: False, max_cycles=None) -> None`
- `main(argv=None) -> int`

동작:
- `poll_line`: 키가 없으면 `NO_KEY`. 호출마다 `record_call`. 예외·5xx·JSON 아님은 `error=True`로 세고 raw는 남기지 않는다. 337이면 `keys.block` + `mark_quota_hit` 뒤 **같은 호선을 다음 키로 바로 다시** 부른다. 그 밖의 코드는 raw 저장, `INFO-000`만 이벤트로 푼다. 새 `statn_id`는 `index.resolve(statn_nm, line)`로 잇고, 못 찾으면 NULL. 다시 띄울 때 NULL인 역을 다시 찾아 `station_map`과 `events`에 반영한다(`remap_stations`).
- 로그: 예외는 종류 이름과 `mask_url(url)`만. 예외 메시지 문자열은 찍지 않는다.
- `run`: 운행 시간 밖이면 30초씩 자며 기다림. 키가 다 막혔으면 한 번 알리고 자정까지 기다림. 주기 시작 뒤 i번째 호선은 `i × period / len(lines)`초에 부른다. 서비스 날짜가 바뀌면 잇지 못한 역 목록을 로그에 남긴다.
- 시작할 때 `store.blocked_keys(오늘)`로 `KeyRing`을 되살린다.

- [ ] 테스트(`test_ml_collector.py`, respx): 정상 응답 → events 35행·raw 1행·`key_usage.calls=1` / `INFO-200` → raw만 / 337 → position 정지 후 spare1로 같은 호선 재호출, `quota_hit_at` 기록 / 세 키 모두 337 → `NO_KEY`류로 끝나고 무한 반복 없음 / `httpx.ConnectError("... /api/subway/SECRETKEY/json ...")` → 로그(caplog)에 `SECRETKEY` 없음, `errors=1` / HTML 본문 → `BAD_BODY` / 호출 URL에 실시간 키가 절대 없음 / `in_service_hours`: 04:59 False, 05:00 True, 00:59 True, 01:00 False / `run(max_cycles=1)`에서 `sleep` 간격 합이 30초이고 호선 수만큼 호출 / 다시 띄우면 막힌 키를 건너뜀 / 끝난 뒤 DB 파일 바이트에 키 문자열 없음.
- [ ] 구현 → 통과 → `.gitignore`에 `server/ml_data/` → 학습 노트 64 → 커밋.
- [ ] 개발 PC에서 백그라운드 실행 시작(`--max-minutes 75`).

### Task 4: 백업(`ml/backup.py`)과 `docs/ml/collector.md`

**Produces:** `backup_db(db_path, out_dir, day: date) -> Path`, `prune_backups(out_dir, keep: int) -> list[Path]`, `prune_raw(db_path, keep_days: int, today: date) -> int`, `main`.

- [ ] 테스트(`test_ml_backup.py`): 백업 파일을 gunzip해 열면 같은 행 수 / `keep=2`면 오래된 것만 지움 / `prune_raw`는 14일 넘은 raw만 지우고 events는 그대로.
- [ ] `collector.md`: VM 준비(시간대 KST, Python 3.12+, 저장소 받기, venv, `.env`에 키 세 개), `systemd` 서비스(`Restart=always`)와 백업 타이머(매일 02:10), 상태 확인 SQL, 백업을 PC로 옮기는 `scp`(사용자가 할 일), Windows 작업 스케줄러 대안, 문제 해결.
- [ ] 학습 노트 65 → 커밋.

### Task 5: 데이터 읽기(`ml/dataset.py`)

**Produces:**
- `Obs`(frozen): `service_date: date, day_type: int, line: str, direction: int, train_no: str, statn_id: str, station_id: str | None, status: int, secs: int(서비스 날짜 0시부터 초, 자정 넘으면 86400 이상), dest_name: str | None, express: int, last_train: int, flag: str | None`
- `load_events(db_path, *, include_flagged: bool = False) -> list[Obs]`
- `Visit`(frozen): `statn_id, arr: int | None, dep: int | None, enter: int | None, prev_dep: int | None` — `ref`(= arr, 없으면 None)
- `Run`(frozen): `service_date, day_type, line, direction, train_no, express, visits: tuple[Visit, ...]`(시간순)
- `build_runs(events: list[Obs]) -> list[Run]`
- `station_names(db_path) -> dict[tuple[line, statn_id], str]`

- [ ] 테스트: 00:30 이벤트의 `secs`가 88200 / 한 열차의 이벤트가 역 방문 순서로 묶임(상태 0·1·2가 한 방문) / flag 달린 이벤트는 기본 제외 / 같은 `train_no`가 방향이 다르면 다른 run.
- [ ] 학습 노트 66 → 커밋.

### Task 6: B1 실측 시간표(`ml/b1.py`)

**Produces:**
- `Entry`(frozen): `secs: int, train_no: str | None, n_days: int, dest_name: str | None, express: int`
- `build_b1(runs, *, min_days=3, slot_width=120, fallback_dwell=30, mode="auto") -> dict` — 결과물(`version`, `kind="b1"`, `built_at`, `date_from`, `date_to`, `n_dates`, `min_days`, `modes: {line: "train_no"|"slots"}`, `cells: {"day_type|line|direction|statn_id": [entry…]}`)
- `class MeasuredTimetable(artifact)`: `departures(day_type, line, direction, statn_id, after_secs, count) -> list[Entry]`, `entry_for(day_type, line, direction, statn_id, train_no) -> Entry | None`
- `train_no_stability(runs) -> dict[line, float]` — 같은 요일 구분에서 2일 이상 본 (호선, 열차 번호, 역) 가운데 출발 시각 차이가 300초 안인 비율. `auto`는 0.8 이상이면 `train_no`.
- CLI `python -m ml.b1 --db --out data/measured_timetable.json`

- [ ] 테스트: 3일 08:00:00/08:00:20/08:01:00 → 중앙값 08:00:20, `n_days=3` / 2일뿐이면 칸이 없음 / 출발이 없고 도착만 있으면 +30초 / slots: 날마다 번호가 달라도 120초 안이면 한 칸, 같은 날 두 번 들어오면 칸을 나눔 / `departures(after)` 정렬·개수 / 빈 입력 → `cells == {}`.
- [ ] 학습 노트 67 → 커밋.

### Task 7: B2 구간 시간(`ml/b2.py`)

**Produces:**
- `build_b2(runs, *, min_n=5, max_hop_sec=1200) -> dict` — `hops`(도착→다음 역 도착), `dwell`(도착→출발), `lead`(상태별 도착까지 남은 초의 중앙값, 호선별), 각 값은 `{median, p10, p90, n}`.
- `class SegmentModel(artifact)`: `hop(line, direction, a, b, *, express, day_type, hour) -> float | None`(조건 풀기: 전체 → 시간대 없이 → 요일 구분 없이), `path(line, direction, a, b, express) -> list[str] | None`(관측된 이웃 그래프에서 최소 정거장), `eta(line, direction, statn_id, status, secs, target, *, express, day_type) -> float | None`, `is_outlier(…, seconds) -> bool`(중앙값의 0.4배 미만 또는 2.5배+60초 초과)
- CLI `python -m ml.b2 --db --out data/segment_times.json`

- [ ] 테스트: A→B 60초·B→C 90초를 6일 → `eta(A 도착 t, C) == t+150` / 출발 상태에서 시작하면 정차 중앙값을 뺌 / 표본 4개면 None, 5개면 값 / 시간대 표본이 모자라면 요일 구분 값으로 / 1200초 넘는 구간은 버림 / 갈 수 없는 역 → None / `is_outlier`.
- [ ] 학습 노트 68 → 커밋.

### Task 8: B0 공식 시간표(`ml/b0.py`)

**Produces:**
- `class TimetableCache(dir)`: `rows(station_cd, week_tag, inout_tag) -> list[dict] | None`, `save(...)`
- `class B0(cache, index, station_ids: dict[(line, statn_id), station_id])`: `departure(day_type, line, direction, statn_id, *, train_no, near_secs) -> int | None`(같은 열차 번호의 `LEFTTIME`, 없으면 가장 가까운 것), `arrival(…) -> int | None`
- CLI `python -m ml.b0 --db --cache ml_data/timetable_cache [--lines 2호선,5호선]` — `SEOUL_OPENAPI_KEY`로 받는다(수집기와 별개인 오프라인 도구, 서버 `TimetableService`와 같은 출처). 수집기 키 세 개는 쓰지 않는다.

- [ ] 테스트: 기존 fixture `seoul_timetable_yeongdeungpo_weekday_up.json`으로 열차 번호 일치·가장 가까운 시각 / 캐시에 없으면 None / 받기는 respx.
- [ ] 학습 노트 69 → 커밋.

### Task 9: 평가(`ml/evaluate.py`)와 첫 주 분석(`ml/analyze.py`)

**Produces:**
- `split_by_date(runs, *, test_days: int | None = None, cutoff: date | None = None) -> tuple[train, test]` — 기본은 뒤 20%(최소 1일). 날짜가 2개 미만이면 `NotEnoughDates`.
- `metrics(errors: list[float]) -> dict` — `n, mae, p90, within60, within120`(절대 오차 기준).
- `evaluate_b1(train, test, *, b0=None, **b1_opts) -> Report`, `evaluate_b2(train, test, *, b0=None, max_stops=10) -> Report` — `Report.overall`, `.by_line`, `.by_day_type`, 각각 `{"b1"|"b2": metrics, "b0": metrics | None}`, `coverage`.
- `evaluate.main`: `python -m ml.evaluate --db [--test-days N] [--timetable-cache DIR] [--same-day-cutoff HH:MM] --out report.md`. `--same-day-cutoff`는 날짜가 하루뿐일 때 파이프라인 점검용(보고서에 "날짜 분할 아님"을 적는다).
- `analyze.main`: `python -m ml.analyze --db --out report.md` — §3의 다섯 항목: 열차 번호 안정성, 정차·이동 시간 분포, `recptnDt` 오류 비율과 역별 순위, 실측−공식 차이(캐시가 있을 때), 노선별 열차 수와 시간대별 결측.

- [ ] 테스트: `metrics([10, -70, 130])` → mae 70, within60 1/3, within120 2/3 / 날짜 분할이 날짜를 섞지 않음 / 하루뿐이면 `NotEnoughDates` / 완벽한 합성 데이터에서 B1·B2 MAE 0 / B0가 있으면 같은 관측에 대해 둘 다 보고 / 분석 보고서에 다섯 제목이 모두 있음, 빈 DB에서도 죽지 않음.
- [ ] 학습 노트 70 → 커밋.

### Task 10: 1시간 실행 기록과 기록 데이터로 점검

- [ ] 수집기가 60분 넘게 돈 뒤 DB 사본으로 `ml.analyze`, `ml.b1 --min-days 1`, `ml.b2`, `ml.evaluate --same-day-cutoff`를 돌린다.
- [ ] `docs/ml/2026-11-15-first-run-observations.md`: 나온 노선, `trainSttus`·`recptnDt` 모양, 비서울 노선, 키 사용량, 잇지 못한 역, DB 크기, 파이프라인 출력 요약, 한계.
- [ ] 전체 테스트 → 학습 노트 71 → 커밋. 푸시는 사용자에게 묻는다.

---

## 이번에 하지 않는 것(계획만)

### 서버 통합(평가 숫자가 나온 뒤)

- `app/services/prediction_service.py`: `server/data/measured_timetable.json`·`segment_times.json`을 시작할 때 읽기만 한다. `TimetableService` 옆에 두고 순서는 실시간 → 공식 시간표 → 예측 → 없음.
- 응답 `source`에 `"predicted"`. 관측 수가 하한 미만이거나 평가에서 그 칸이 B0보다 나쁘면 내지 않는다(결과물에 칸별 `n_days`가 있으므로 서버에서 거를 수 있다).
- 탄 열차 예측은 `SegmentModel.eta`를 쓰는 별도 엔드포인트 또는 경로 응답 확장 — 통합 때 정한다.
- 주 1회 재학습: VM에서 `ml.b1`·`ml.b2`를 돌려 결과물을 저장소에 넣고 배포.
- B2로 `events.flag`에 `outlier`를 써 넣는 일괄 작업(`SegmentModel.is_outlier` 사용).

### M1(LightGBM 보정)

- 데이터 4주 이상, B1·B2 평가 뒤. B1·B2의 오차를 목표로 하는 회귀. 특징: 요일 구분, 시간대(분), 역 순서, 남은 정거장 수, 마지막 관측 뒤 지난 시간, 그날 그 호선의 최근 지연, 급행·막차.
- 같은 날짜 분할에서 B1·B2보다 MAE·P90이 확실히 나을 때만 채택.
