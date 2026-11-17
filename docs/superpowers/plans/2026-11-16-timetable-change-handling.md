# 시간표 변경 감지와 실측 시간표(B1) 갱신 구현 계획

**Goal:** 어느 호선·방향·시간대의 시간표가 바뀌었는지 수집 데이터로 알아채고, 실측 시간표(B1)가 바뀌기 전 열차("유령 열차")를 계속 보여 주지도, 새 열차를 너무 늦게 보여 주지도 않게 한다.

**Architecture:** 매일 밤 한 번 도는 작업(`ml.nightly`)이 전날 하루치 출발 시각을 작은 파생 DB(`points.sqlite`)에 뽑아 두고, 그것으로 (요일 구분, 호선, 방향, 1시간대) 단위의 변경을 판단해 상태 파일(`change_state.json`)에 적은 뒤 B1을 다시 만든다. B1은 세 겹으로 지킨다: ① 최근 날짜만 쓰는 창, ② 최근 며칠 안 보인 열차를 빼는 규칙(감지 없이도 작동), ③ 확정된 변경일 이후 날짜만 쓰는 기준일(감지가 확정했을 때). 서버 코드(`app/`)는 건드리지 않는다.

**Tech Stack:** Python 3.12+, sqlite3, 표준 라이브러리 `statistics`, httpx(공식 시간표 받기·선택 알림), pytest + respx. 새 의존성 없음.

**Spec:** `docs/ml/2026-11-13-train-eta-model-plan.md`(§9가 구속). 이 계획은 그 문서 §5 "주 1회 다시 학습"을 "매일 밤"으로 바꾸자고 제안한다(아래 "사용자가 정할 것" 1번).

## 지금 확인한 사실 (2026-11-16)

- VM 수집 DB에는 2026-11-16 하루치만 있다(이벤트 43만 건, DB 133MB/일). 평일 기준 시간표는 11-18 밤에 처음 생기고(3일), 토요일은 12-05, 일요일은 12-06이다. **그 전에는 감지가 판단할 것이 없다.**
- 9호선 하행(중앙보훈병원 방면) 당산 05:30~07:30 관측: `9502 9010 9504 9012 9506 9508 9014 9510 9512 9016 …` — 06:00 무렵부터 급행(`95xx`) 2대 뒤 일반(`90xx`) 1대. 지금 데이터는 바뀐 뒤의 것이다.
- VM은 메모리 836MB(여유 약 520MB), CPU 2개, 디스크 29GB다. 기존 `load_events`는 DB 전체를 메모리에 올리므로 28일치를 VM에서 그대로 돌릴 수 없다 → Task 1.
- VM `.env`에는 수집기 키 이름 세 개만 있다. 공식 시간표를 받는 `SEOUL_OPENAPI_KEY`가 없다 → Task 6은 개발 PC에서 돌리거나 사용자가 VM에 키를 더해야 한다.
- 2호선은 하루에 열차 번호가 926개 나온다(다른 호선은 300~500개). 번호가 바퀴마다 바뀌는 것으로 보이고, 공식 시간표 번호와도 49~59%만 맞는다.

## Global Constraints

- 수집기(`ml/collector.py`)와 수집 DB의 표는 바꾸지 않는다. 수집 DB는 읽기 전용(`mode=ro`)으로만 연다.
- 키 값은 출력·기록·커밋하지 않는다. 알림 웹훅 URL도 키로 다룬다(`mask_url`, 로그에 URL을 찍지 않음).
- 수집기 키 세 개(`SEOUL_POSITION_API_KEY`, `SEOUL_SPARE_KEY_1`, `SEOUL_SPARE_KEY_2`)는 수집기만 쓴다. 공식 시간표 받기는 `SEOUL_OPENAPI_KEY`만 쓴다.
- 이상값은 지우지 않는다. 변경 판단도 `events`를 고치지 않고 따로 적는다.
- 시각은 "서비스 날짜 0시부터 지난 초"(01시 이전은 전날, 86400 이상 가능). 시간대(band) = `secs // 3600`(5~24).
- 요일 구분 `day_type`: 1 평일, 2 토요일, 3 휴일(`app.services.timetable.week_tag`).
- VM에서 도는 코드는 한 번에 한 호선만 메모리에 올린다.
- 결과물과 상태는 `server/ml_data/` 아래(커밋하지 않음). `server/data/`로 옮기는 일은 서버 통합 계획의 몫이다.
- 테스트: `server/`에서 `.venv/Scripts/python -m pytest -q`(시작 379개 통과). 합성 데이터는 `tests/ml_helpers.py`로 만든다. 네트워크는 respx.
- 작업마다 `docs/learning/73-…`부터 학습 노트 하나. 커밋은 `develop`, 푸시는 사용자에게 묻는다. 사용자에게 보이는 글은 한국어.
- VM의 서비스·타이머를 새로 켜거나 다시 띄우는 일은 사용자에게 묻고 한다.

## 설계

### 판단 단위와 신호

판단 단위(scope)는 `(day_type, line, direction, band)`. 하루치 관측 출발을, 그 전날까지의 자료로 만든 기준 B1(같은 요일 구분)과 견준다. 9호선 예처럼 05:40~07:00만 바뀌면 5·6시대(하류 역은 7시대까지)만 걸린다. 기록할 때는 이어진 시간대를 한 줄로 합친다.

| 신호 | 뜻 | 기준(초안) | 쓰는 노선 |
|---|---|---|---|
| `shifted` | 같은 열차 번호끼리 견줘 120초 넘게 어긋난 비율 | ≥ 0.30 | 번호가 날마다 같은 노선 |
| `numbers` | (사라진 번호 + 새 번호) ÷ (기준 + 새 번호) | ≥ 0.20 | 번호가 날마다 같은 노선 |
| `moved` | 번호를 보지 않고 시각만 120초 안에서 짝지었을 때 짝 없는 비율 | ≥ 0.30 | 전부 |
| `tph` | 시간당 출발 수 비 | ±20% **그리고** 역당 2대 이상 차이 | 전부 |
| `express` | 급행 비율의 차 | ±0.15 | 전부 |

- 신호가 하나라도 넘으면 그 날 그 단위는 **의심**이다.
- **수집이 빈 시간대는 판단하지 않는다.** 그 호선·시간대의 정상 응답(`INFO-000`) 수가 기대값 120회의 80%(96회) 미만이면 건너뛴다. 기준 쪽 출발이 20건 미만인 단위도 건너뛴다.
- 종류(`kind`): `tph`가 넘으면 `frequency`, 아니고 `express`가 넘으면 `pattern`, 아니고 `numbers`만 넘고 `moved`는 안 넘으면 `renumber`(시각은 그대로, 번호만 바뀜), 그 밖은 `retime`.

### 의심 → 확정

지연은 `shifted`·`moved`를 쉽게 넘긴다. 그래서 지연과 변경을 **"다음 날도 같은 모양인가"**로 가른다. 지연은 날마다 다르고, 바뀐 시간표는 날마다 같다.

| 오늘(같은 요일 구분의 다음 날) | 결과 |
|---|---|
| 의심이 아님 | 어제의 의심을 **해제**(하루짜리, 사고·지연으로 봄) |
| 의심이고, 첫 의심일의 관측과 서로 맞음(오늘을 첫 의심일 하루치와 견줘 `shifted`·`moved` < 0.50, `numbers` < 0.20, `tph`·`express` 안 넘음) | **확정**, 변경일 = 첫 의심일 |
| 의심이지만 서로 맞지 않음 | 의심 유지. **3일 연속** 의심이면 확정(지연 변동이 커서 날끼리 잘 안 맞는 노선용) |
| 수집이 비어 판단 못 함 | 그대로 둠. 같은 요일 구분으로 4일이 지나도록 판단 못 하면 해제 |

- 공식 시간표(2~9호선)를 다시 받아 그 단위가 바뀐 것으로 나왔으면 의심 하루 만에 확정한다.
- 사용자가 공지·기사를 보고 손으로 확정(`mark`)하거나 잘못된 확정을 되돌릴(`clear`) 수 있다. 공지만으로 자동으로 바꾸지는 않는다.
- 토요일·휴일은 다음 같은 요일 구분이 일주일 뒤라 확정도 일주일 걸린다. 공식 시간표가 있는 노선은 위 규칙으로 줄어든다.

### B1을 지키는 세 겹

1. **창(window):** 요일 구분별로 가장 최근 N개 날짜만 쓴다. 평일 20일, 토요일 8일, 휴일 8일.
2. **안 보인 열차 빼기(recency):** 어떤 칸의 열차가, 그 단위에 수집이 정상이었던 최근 날짜 가운데 평일 3일·토 2일·휴일 2일 연속으로 안 보였으면 뺀다. 감지가 놓쳐도 유령 열차는 이 기간 뒤 사라진다.
3. **기준일(epoch):** 확정된 단위는 변경일 이후 날짜만 쓴다. 이 단위의 관측 일수 하한은 3이 아니라 **2**다(확정 자체가 "서로 맞는 이틀"을 요구했으므로). 이틀이 안 되면(공식 시간표나 손으로 하루 만에 확정한 경우) 그 단위는 `retraining`이고 시각을 내지 않는다 → 서버는 공식 시간표(2~9호선)로 내려가거나 예측을 보이지 않는다.

| 상황 | 유령 열차가 남는 기간 | 새 열차가 보이기까지 |
|---|---|---|
| 감지가 확정(평일) | 변경 뒤 2일(둘째 날 밤에 확정) | 2일 |
| 감지가 놓침, 번호가 바뀐 변경 | 3일(2번 규칙) | 3일(`min_days`) |
| 감지가 놓침, 번호는 같고 시각만 바뀜 | 중앙값이 넘어갈 때까지 최대 약 10평일(1번 규칙) | 같음 |

### 처음에는 관찰만

기준값은 지연 분포를 모르는 채 정한 초안이다. `ml.nightly`는 처음에 **관찰 모드**로 돈다: 의심·확정을 기록하고 알리지만 3번(기준일)은 B1에 적용하지 않는다(1·2번은 항상 적용). 2~3주 자료로 기준값을 맞춘 뒤(Task 9) 사용자가 `--enforce`를 켠다.

### 결과물(B1 `version` 2)에 더하는 것

```json
{"version": 2, "as_of": "2026-11-23",
 "window": {"1": 20, "2": 8, "3": 8}, "stale_days": {"1": 3, "2": 2, "3": 2}, "min_days_after_change": 2,
 "scopes": {"1|9호선|1|6": {"state": "retraining", "since": "2026-11-20", "kind": "pattern", "official": "stale"}},
 "changes": [{"day_type": 1, "line": "9호선", "direction": 1, "band_from": 5, "band_to": 7,
              "change_date": "2026-11-20", "decided_date": "2026-11-23", "kind": "pattern",
              "source": "measured", "triggers": ["express", "numbers"], "note": ""}],
 "cells": {"1|9호선|1|1009000913": [{"secs": 21630, "train_no": "9502", "n_days": 2, "dest_name": "중앙보훈병원",
                                     "express": 1, "first_seen": "2026-11-20", "last_seen": "2026-11-23"}]}}
```

`scopes`에는 `suspect`·`retraining`·`changed`(확정 뒤 14일 동안)인 단위만 적는다. 없는 단위는 정상이다.

### 파일 구조

| 파일 | 책임 |
|---|---|
| `server/ml/scopes.py` (새) | `Scope`, `band_of`, 키 문자열, 이어진 시간대 합치기 |
| `server/ml/points.py` (새) | 하루치 출발 시각과 시간대별 수집 횟수를 파생 DB에 넣고 읽기 |
| `server/ml/dataset.py` (고침) | `load_events`에 날짜·호선 조건 |
| `server/ml/b1.py` (고침) | 출발 시각 목록으로 만드는 빌더, 창·안 보인 열차·기준일, `Entry` 칸, `scope_state` |
| `server/ml/changes.py` (새) | 신호 계산, 의심·확정 상태 기계, 상태 파일, 변경 기록, CLI |
| `server/ml/b0.py` (고침) | 공식 시간표 다시 받기와 이전 것과의 차이 |
| `server/ml/alerts.py` (새, 선택) | 웹훅 알림 |
| `server/ml/nightly.py` (새) | 밤 작업 순서 |
| `server/ml/evaluate.py` (고침) | 변경 전후·하루짜리 이상을 나눠 보고 |
| `server/ml/deploy/subway-ml-nightly.{service,timer}`, `setup_vm.sh` | VM 타이머 |
| `docs/ml/collector.md`, `docs/ml/timetable-changes.md` (새) | 운영 안내 |

## Review Focus

1. 수집기가 몇 시간 멈춘 날 → 그 시간대는 판단하지 않고, 의심도 해제도 하지 않는다. 안 보인 열차 빼기도 그 날을 세지 않는다(Task 2·4).
2. 큰 지연이 하루 있고 다음 날 정상 → 해제. 서로 다른 모양의 지연이 이틀 → 확정되지 않는다(Task 4).
3. 시각은 그대로인데 열차 번호만 전부 바뀜 → `renumber`로 확정, 새 번호로 바로 다시 만들어지고 시각이 비는 날이 없다(Task 3·4).
4. 상태 파일이 깨졌거나 쓰다가 끊김 → 덮어쓰지 않고 종료 코드 2. 파일이 없으면 빈 상태로 시작(Task 4).
5. VM이 며칠 꺼졌다 켜짐 → 빠진 날을 날짜순으로 따라잡는다. raw가 지워져(14일) 수집 횟수를 모르는 날은 판단하지 않는다(Task 5).

---

### Task 1: 단위(`ml/scopes.py`), 날짜·호선 조건, 파생 DB(`ml/points.py`)

**Files:** Create `server/ml/scopes.py`, `server/ml/points.py`, `server/tests/unit/test_ml_scopes.py`, `server/tests/unit/test_ml_points.py` / Modify `server/ml/dataset.py`, `server/tests/ml_helpers.py`, `server/tests/unit/test_ml_dataset.py`

**Produces:**
- `scopes.Scope = tuple[int, str, int, int]`(day_type, line, direction, band), `BAND_SEC = 3600`
- `scopes.band_of(secs: int) -> int`, `scope_key(scope) -> str`(`"1|9호선|1|6"`), `parse_scope_key(key: str) -> Scope`
- `scopes.merge_bands(bands: Iterable[int]) -> list[tuple[int, int]]` — 이어진 것끼리 (처음, 끝) 양 끝 포함.
- `dataset.load_events(db_path, *, include_flagged=False, date_from: date | None = None, date_to: date | None = None, line: str | None = None)` — 조건은 SQL `WHERE`에 넣는다(기본 키 앞머리 `(service_date, line)`를 탄다).
- `dataset.service_dates(db_path, *, date_from: date, date_to: date) -> list[date]` — 그 사이에 이벤트가 있는 서비스 날짜(양 끝 포함, 오름차순).
- `points.PointStore(path)`:
  - `extract_day(db_path, day: date, *, lines: Sequence[str] | None = None) -> int` — 호선마다 `load_events(date_from=day, date_to=day, line=…)` → `build_runs` → `b1.departure_points`(그 날 그 호선의 정차 중앙값 사용) → 그 (날짜, 호선)의 행을 지우고 다시 넣는다. 돌려주는 값은 넣은 행 수. 같은 날을 다시 돌려도 결과가 같다.
  - 같은 호출에서 `raw_snapshots` 가운데 `code = 'INFO-000'`인 것을 (호선, 시간대)별로 세어 `coverage`에 넣는다. 그 날의 raw가 한 줄도 없으면 `coverage` 행을 만들지 않는다(= 모름).
  - `points(line: str, day_type: int, dates: Sequence[date]) -> list[b1.Departure]`
  - `dates(line: str, day_type: int, *, upto: date, n: int) -> list[date]` — 출발이 있는 날짜 가운데 `upto` 이하 최근 n개, 오름차순.
  - `lines(day: date) -> list[tuple[str, int]]` — 그 날 출발이 있는 (호선, day_type).
  - `coverage(line: str, day: date) -> dict[int, int] | None` — 시간대 → 정상 응답 수, 모르면 `None`.
  - `set_coverage(line, day, counts: dict[int, int])`(테스트·따라잡기용), `prune(before: date) -> int`, `close()`
- `tests/ml_helpers.py`: `timetable(days: list[date], starts: dict[str, int], **trip_kw) -> list[Obs]`(날마다 `starts`의 열차 번호 → 시작 초로 `trip`을 만든다), `cover(store: PointStore, days, line="9호선", n=120)`(5~24시대 모두 n).

스키마(`ml_data/points.sqlite`. 출발 시각은 수집 DB에서 다시 만들 수 있지만, `coverage`는 raw가 지워지는 14일 뒤에는 다시 만들 수 없다 — 이 파일을 지우면 그보다 오래된 날은 "모름"이 된다):

```sql
CREATE TABLE departures(service_date TEXT NOT NULL, day_type INTEGER NOT NULL, line TEXT NOT NULL,
  direction INTEGER NOT NULL, statn_id TEXT NOT NULL, train_no TEXT NOT NULL, secs INTEGER NOT NULL,
  estimated INTEGER NOT NULL, dest_name TEXT, express INTEGER NOT NULL,
  PRIMARY KEY(line, day_type, service_date, direction, statn_id, train_no));
CREATE TABLE coverage(service_date TEXT NOT NULL, line TEXT NOT NULL, band INTEGER NOT NULL,
  n_ok INTEGER NOT NULL, PRIMARY KEY(service_date, line, band));
```

- [ ] 테스트 작성: `band_of(21599) == 5`, `band_of(88200) == 24` / `parse_scope_key(scope_key(s)) == s` / `merge_bands([5, 6, 8]) == [(5, 6), (8, 8)]`, 빈 입력 → `[]` / `load_events(date_from=d2, date_to=d2, line="9호선")`가 다른 날·다른 호선을 빼고, 조건 없이 부르면 예전과 같다 / `extract_day` 뒤 `points()`가 `departure_points(build_runs(그 날 이벤트))`와 같은 (역, 번호, 초) 집합 / 두 번 돌려도 행 수가 같다 / `write_db`에 raw를 넣지 않으면 `coverage(...) is None`, `store.add_snapshot`으로 06시대에 `INFO-000` 3건·`ERROR-500` 1건을 넣으면 `{6: 3}` / `dates(upto, n=2)`가 최근 2개만 / 00:30 출발(88200초)이 전날 서비스 날짜의 24시대로 들어간다.
- [ ] 실패 확인: `.venv/Scripts/python -m pytest tests/unit/test_ml_scopes.py tests/unit/test_ml_points.py -q` → `ModuleNotFoundError`.
- [ ] 구현 → 위 두 파일과 `test_ml_dataset.py` 통과 → 전체 테스트 통과.
- [ ] 학습 노트 73 → 커밋 `feat(ml): 하루치 출발 시각 파생 DB와 판단 단위`.

### Task 2: B1 — 출발 시각으로 만들기, 창, 안 보인 열차 빼기, 기준일

**Files:** Modify `server/ml/b1.py`, `server/tests/unit/test_ml_b1.py`

**Consumes:** `scopes.Scope`, `band_of`.

**Produces:**
- `Entry`에 `first_seen: str | None = None`, `last_seen: str | None = None`(ISO 날짜) 추가. 기본값이 있어 `version` 1 결과물도 읽힌다.
- `WINDOW = {1: 20, 2: 8, 3: 8}`, `STALE_DAYS = {1: 3, 2: 2, 3: 2}`, `MIN_DAYS_AFTER_CHANGE = 2`, `EPOCH_MARGIN_SEC = 300`, `VERSION = 2`
- `build_b1_from_points(points: list[Departure], *, modes: dict[str, str] | None = None, min_days=MIN_DAYS, slot_width=SLOT_WIDTH_SEC, window: dict[int, int] | None = None, stale_days: dict[int, int] | None = None, epochs: dict[Scope, date] | None = None, active: dict[Scope, set[date]] | None = None, min_days_after_change=MIN_DAYS_AFTER_CHANGE, as_of: date | None = None, now: datetime | None = None) -> dict`
- `stability_from_points(points) -> dict[str, float]`(기존 `train_no_stability`가 이것을 부른다), `modes`가 `None`이면 지금처럼 안정성 0.8로 고른다.
- `build_b1(runs, …, window=None, stale_days=None, epochs=None, as_of=None)`는 안에서 `build_b1_from_points`를 부르고 새 인자를 그대로 넘긴다. 새 인자를 안 주면 지금과 같은 결과다. **기존 B1 테스트 14개는 고치지 않고 통과해야 한다**(`version` 값을 보는 단언이 있으면 그것만 2로).
- `MeasuredTimetable.scope_state(day_type: int, line: str, direction: int, secs: int) -> str` — `"retraining"`, `"suspect"`, 그 밖은 `"ok"`. `scopes`가 없는 결과물은 항상 `"ok"`.

규칙(이 순서로):
1. `as_of`가 있으면 그 뒤 날짜를 버린다. `window`가 있으면 요일 구분별로 남은 날짜 가운데 최근 `window[day_type]`개만 쓴다.
2. `epochs`가 있으면, 출발 `p`의 날짜가 `band_of(p.secs)`, `band_of(p.secs - 300)`, `band_of(p.secs + 300)` 가운데 어느 단위의 기준일보다 앞이면 버린다(시간대 경계를 넘나드는 열차의 옛 관측이 섞이지 않게 5분 번진다).
3. 묶어서 `Entry`를 만든다. 그 `Entry`의 `secs`가 속한 단위에 기준일이 있으면 하한은 `min_days_after_change`, 없으면 `min_days`.
4. `stale_days`가 있으면, `Entry`의 단위에서 `last_seen` 뒤의 활동 날짜가 `stale_days[day_type]`개 이상일 때 뺀다. 활동 날짜는 `active[scope]`가 있으면 그것, 없으면 그 단위에 출발이 하나라도 있는 날짜.
5. 기준일이 있는 단위에서 기준일 이후 날짜 수가 `min_days_after_change` 미만이면 `scopes[key] = {"state": "retraining", "since": 기준일}`로 적고 그 단위의 `Entry`를 모두 뺀다.

- [ ] 테스트 작성(평일 날짜는 `weekdays(n)`, 열차는 `timetable(...)`, A역 출발 = 시작 + 30초):
  - `test_window_keeps_only_recent_dates`: 평일 6일, 앞 3일은 `"100"`이 08:00:00, 뒤 3일은 08:10:00. `window={1: 3}` → A역 `secs == 28800 + 600 + 30`, `n_days == 3`.
  - `test_train_gone_for_stale_days_is_dropped`: 6일 가운데 `"100"`은 앞 3일만, `"200"`은 6일 모두 같은 시간대. `stale_days={1: 3}` → `"100"` 없음, `"200"` 있음. `stale_days={1: 4}` → `"100"` 있음.
  - `test_days_without_coverage_do_not_count_as_absence`: 위와 같은데 `active`에 뒤 3일 가운데 하루만 넣으면 `"100"`이 남는다.
  - `test_epoch_drops_days_before_change_and_needs_two_days`: 5일 08:00, 이어서 2일 08:05(같은 번호). `epochs={(1, "9호선", 0, 8): 여섯째 날}` → `secs == 28800 + 300 + 30`, `n_days == 2`, `first_seen == 여섯째 날`. 기준일이 없으면 중앙값은 08:00 쪽이다.
  - `test_scope_with_one_day_after_change_is_retraining`: 변경 뒤 하루뿐 → 그 단위 `Entry` 없음, `artifact["scopes"]["1|9호선|0|8"]["state"] == "retraining"`, `MeasuredTimetable(artifact).scope_state(1, "9호선", 0, 28900) == "retraining"`, 다른 시간대는 `"ok"`.
  - `test_epoch_margin_covers_trains_near_band_edge`: 변경 전 08:58:00(8시대), 변경 뒤 09:01:00(9시대), 기준일은 8시대에만 → 09:01 쪽 `Entry`에 옛 날짜가 섞이지 않는다(`n_days == 2`).
  - `test_version1_artifact_still_loads`: `first_seen`·`scopes`가 없는 dict → `departures(...)` 동작, `scope_state(...) == "ok"`.
  - `test_slots_mode_entries_carry_last_seen_and_obey_stale_days`.
- [ ] 실패 확인 → 구현 → `tests/unit/test_ml_b1.py` 전부 통과 → 전체 통과.
- [ ] 학습 노트 74 → 커밋 `feat(ml): B1에 최근 창·안 보인 열차 빼기·변경 기준일`.

### Task 3: 신호 계산(`ml/changes.py` 앞부분)

**Files:** Create `server/ml/changes.py`, `server/tests/unit/test_ml_changes_signals.py`

**Consumes:** `b1.Entry`, `b1.Departure`, `b1.build_b1_from_points`, `scopes`.

**Produces:**
- `Thresholds`(frozen dataclass, 기본값): `late_sec=120, shifted=0.30, moved=0.30, numbers=0.20, tph=0.20, tph_min_trains=2, express=0.15, agree_max=0.50, min_ref=20, min_coverage=96, streak=3, give_up=4`
- `ScopeStats`(frozen): `scope: Scope, n_ref: int, n_obs: int, n_stations: int, shifted: float | None, numbers: float | None, moved: float, tph_ratio: float, express_delta: float`
- `scope_stats(cells: dict[str, list[Entry]], obs: list[Departure], *, mode: str, th: Thresholds = Thresholds()) -> dict[Scope, ScopeStats]` — `cells`는 B1 결과물의 `cells`를 `Entry`로 읽은 것, `obs`는 한 호선·한 날의 출발. `n_ref < th.min_ref`인 단위는 결과에 없다.
- `triggers(stats: ScopeStats, th) -> tuple[str, ...]`(순서 `shifted, numbers, moved, tph, express`), `kind_of(names: tuple[str, ...]) -> str`
- `agree(first_day: list[Departure], today: list[Departure], *, mode: str, th) -> dict[Scope, bool]` — `first_day`를 `min_days=1`로 하루치 기준표로 만들어 `today`와 견준다. 맞음 = `shifted`·`moved`가 `None`이거나 `< agree_max`, `numbers`가 `None`이거나 `< th.numbers`, `tph`·`express`는 안 넘음.

정의(한 역의 한 방향을 칸이라 한다. 시간대는 기준 쪽은 `Entry.secs`, 관측 쪽은 관측 시각으로 정한다):
- `shifted` = 번호가 같은 짝 가운데 `|관측 − 기준| > late_sec`인 것 ÷ 짝 수(기준 시간대 기준). `mode != "train_no"`이거나 짝이 0이면 `None`.
- `numbers` = (그 날 그 칸에서 안 보인 기준 번호 + 기준에 없는 관측 번호) ÷ (기준 수 + 기준에 없는 관측 수). `mode != "train_no"`이면 `None`.
- `moved` = (짝 없는 기준 + 짝 없는 관측) ÷ (기준 수 + 관측 수). 짝짓기는 칸마다 하루 전체를 시각순으로:

```python
i = j = 0
while i < len(ref) and j < len(obs):
    if abs(ref[i] - obs[j]) <= late_sec: i += 1; j += 1          # 짝
    elif ref[i] < obs[j]: unmatched_ref.append(ref[i]); i += 1
    else: unmatched_obs.append(obs[j]); j += 1
# 남은 것은 모두 짝 없음
```

- `tph_ratio` = `n_obs / n_ref`. `tph`가 넘었다고 보려면 `|tph_ratio − 1| ≥ th.tph`이고 `|n_obs − n_ref| ≥ th.tph_min_trains × n_stations`.
- `express_delta` = 관측의 급행(`express > 0`) 비율 − 기준의 급행 비율.

- [ ] 테스트 작성(기준: 평일 5일, 4역 노선에 06:00:00부터 360초 간격 10대 `"9001"`~`"9010"`, 시험 날은 여섯째 평일. 기준표는 `build_b1_from_points(앞 5일)`):
  - 같은 날 → 6시대 `triggers == ()`.
  - 모든 열차 +180초 → `shifted == 1.0`, `moved == 1.0`, `numbers == 0.0`, `kind_of(...) == "retime"`.
  - 모든 열차 +60초 → `triggers == ()`.
  - 번호만 `"8001"`~`"8010"`으로 → `numbers == 1.0`, `moved == 0.0`, `shifted is None`, `kind_of(...) == "renumber"`.
  - 240초 간격 15대로(+50%) → `"tph" in triggers`, `kind == "frequency"`. 327초 간격 11대(+10%)는 `tph` 없음. 비율 비교는 부동소수 오차를 타지 않게 `abs(n_obs - n_ref) * 100 >= round(th.tph * 100) * n_ref`처럼 정수로 한다.
  - 10대 가운데 급행 5대 → 7대(시각 같음) → `express_delta == pytest.approx(0.2)`, `kind == "pattern"`.
  - `mode="slots"` → `shifted is None and numbers is None`, +180초면 `"moved" in triggers`.
  - 기준이 3대뿐인 시간대(3대 × 3역 = 9 < 20) → 결과에 그 단위 없음.
  - `agree`: 둘 다 +180초인 두 날 → 6시대 `True`. +180초인 날과 +400초인 날 → `False`.
- [ ] 실패 확인 → 구현 → 통과 → 학습 노트 75 → 커밋 `feat(ml): 시간표 변경 신호 다섯 가지`.

### Task 4: 상태 기계, 상태 파일, 변경 기록

**Files:** Modify `server/ml/changes.py` / Create `server/tests/unit/test_ml_changes_state.py`

**Produces:**
- `ChangeRecord`(dataclass): `scope: Scope, status: str("suspect"|"confirmed"|"cleared"), first_date: date, days: list[date], missed: list[date], decided_date: date | None, kind: str, triggers: tuple[str, ...], signals: dict[str, float | None], source: str("measured"|"official"|"manual"), official: str("unknown"|"changed"|"fits"|"stale"), note: str`
- `ChangeState`: `records: list[ChangeRecord]`, `checked: dict[str, date]`(`"호선|day_type"` → 마지막으로 판단한 날), `ChangeState.load(path) -> ChangeState`(없으면 빈 상태, JSON이 깨졌으면 `CorruptState`), `save(path)`(임시 파일에 쓰고 `os.replace`, 바꾸기 전 것은 `….prev`로 남김), `epochs() -> dict[Scope, date]`(단위별 가장 늦은 확정 `first_date`), `open_suspect(scope) -> ChangeRecord | None`, `incidents() -> dict[Scope, set[date]]`(해제된 의심의 `days`).
- `step(state, day: date, line: str, day_type: int, stats: dict[Scope, ScopeStats], *, covered: set[Scope], agrees: Callable[[date], dict[Scope, bool]], official_changed: Set[Scope] = frozenset(), th: Thresholds = Thresholds()) -> list[ChangeRecord]` — 이 날 새로 생기거나 상태가 바뀐 기록. "의심 → 확정" 표 그대로:
  - `covered`에 없는 단위(그 호선·요일 구분의 열린 의심을 모두 훑는다. `stats`에 없어도): `missed`에 `day`를 더하고, `len(missed) >= th.give_up`이면 해제(`note="확인 못 함"`), 아니면 그대로. 새 의심은 만들지 않는다.
  - 열린 의심 없음 + 신호 넘음 → 의심(`days=[day]`). 그 단위가 `official_changed`에 있으면 바로 확정(`source="official"`).
  - 열린 의심 + 신호 안 넘음 → 해제(`note="하루짜리"` 또는 `"N일"`).
  - 열린 의심 + 신호 넘음 → `days`에 더하고, `agrees(first_date)[scope]`가 참이거나 `len(days) >= th.streak`이면 확정(`decided_date=day`).
  - 같은 `day`를 두 번 넣어도 결과가 같다(`days`에 이미 있으면 아무 일도 안 함).
- `mark(state, scopes: Iterable[Scope], change_date: date, *, today: date, note: str) -> list[ChangeRecord]`(`source="manual"`, 바로 확정), `clear(state, scopes, *, today, note) -> list[ChangeRecord]`(확정·의심을 해제로. `epochs()`에서 빠진다).
- `detect_day(store: PointStore, state: ChangeState, day: date, *, th=Thresholds(), window=b1.WINDOW, official_changed: Set[Scope] = frozenset()) -> list[ChangeRecord]` — `store.lines(day)`마다: `state.checked`가 이미 `day` 이상이면 건너뜀 → 기준표 = `build_b1_from_points(store.points(line, day_type, store.dates(…, upto=day − 1일, n=window[day_type])), epochs=state.epochs(), window=window)`(여기서는 `stale_days` 없이) → `scope_stats` → `covered` = `store.coverage(line, day)`가 `None`이 아니고 그 시간대 값이 `th.min_coverage` 이상인 단위 → `step` → `checked` 갱신. `agrees`는 `store.points(line, day_type, [first_date])`로 계산한다.
- `render(records: list[ChangeRecord], *, direction_names: dict[tuple[str, int], str] | None = None) -> list[str]` — 상태·첫 날짜·종류가 같고 시간대가 이어진 기록을 한 줄로. 모양: `- 2026-11-23 확정 | 9호선 하행 평일 05~07시 | pattern | 급행 비율 +0.17, 번호 42% | 2026-11-20부터 | measured`
- `append_log(path, day: date, lines: list[str]) -> None` — `ml_data/changes.md` 끝에 `## 날짜` 아래로 덧붙인다. 줄이 없으면 아무것도 쓰지 않는다.
- `annotate(artifact: dict, state: ChangeState, *, as_of: date, enforce: bool) -> dict` — `scopes`에 열린 의심(`suspect`), 확정 뒤 14일 안(`changed`)을 더하고(`retraining`은 Task 2가 이미 넣음), `changes`에 최근 60일의 확정 기록을 시간대를 합쳐 넣고, `"enforce": bool`을 적는다.
- CLI(`python -m ml.changes`): `show [--state]`, `mark --line --direction --day-type --from HH --to HH --date YYYY-MM-DD --note`, `clear --line --direction --day-type --from HH --to HH --note`. 기본 `--state ml_data/change_state.json`. `mark`·`clear`는 `changes.md`에도 적는다.

- [ ] 테스트 작성(Task 3의 기준 5일 + 뒤따르는 날들을 `PointStore`에 넣고 `cover`로 수집 횟수를 채운 뒤 `detect_day`를 날짜순으로):
  - `test_two_matching_days_confirm_with_first_day_as_change_date`: 여섯째·일곱째 날 +180초 → 여섯째 날 뒤 `suspect`, 일곱째 날 뒤 `confirmed`, `first_date == 여섯째 날`, `epochs()[(1, "9호선", 0, 6)] == 여섯째 날`.
  - `test_one_bad_day_then_normal_is_cleared`: 여섯째 +180초, 일곱째 정상 → `cleared`, `epochs() == {}`, `incidents()`에 여섯째 날.
  - `test_two_different_delay_days_do_not_confirm`: 여섯째 +180초, 일곱째 +400초 → 여전히 `suspect`(`days` 2개). 여덟째도 +250초 → `confirmed`(3일 연속).
  - `test_renumber_confirms_as_renumber`: 번호만 바뀐 이틀 → `confirmed`, `kind == "renumber"`.
  - `test_uncovered_band_is_neither_suspected_nor_cleared`: 여섯째 +180초(의심), 일곱째는 `set_coverage(…, {6: 10})`이고 그 시간대 출발이 절반 → 의심 그대로, 새 기록 없음. `coverage`가 `None`인 날도 같다. 판단 못 한 날이 4개째 → `cleared`, `note == "확인 못 함"`.
  - `test_weekend_days_between_do_not_interrupt`: 금요일 의심, 토·일(다른 요일 구분)을 지나 월요일 같은 모양 → 확정.
  - `test_official_change_confirms_on_first_suspect_day`.
  - `test_after_confirm_next_day_is_quiet`: 확정 다음 날 같은 시간표 → 새 기록 없음(기준표가 기준일 뒤로 다시 만들어지므로).
  - `test_detect_day_twice_is_idempotent`.
  - `test_state_roundtrip_and_atomic_save`: 저장 → 읽기 같음, 임시 파일이 남지 않음. `test_corrupt_state_raises_and_file_is_untouched`. `test_missing_state_is_empty`.
  - `test_mark_then_clear_restores_window`: `mark` 뒤 `epochs()`에 있음 → `clear` 뒤 없음.
  - `test_render_merges_adjacent_bands`: 5·6·7시대 확정 → 한 줄, `"05~07시"` 포함.
  - CLI: `main(["mark", …])`가 상태 파일과 `changes.md`를 쓰고 0을 돌려준다. 깨진 상태 파일이면 2.
- [ ] 실패 확인 → 구현 → 통과 → 전체 통과 → 학습 노트 76 → 커밋 `feat(ml): 변경 의심·확정 상태와 기록`.

### Task 5: 밤 작업(`ml/nightly.py`)

**Files:** Create `server/ml/nightly.py`, `server/tests/unit/test_ml_nightly.py`

**Consumes:** Task 1·2·4 전부.

**Produces:**
- `run_nightly(*, db, points_path, state_path, out_path, log_path, today: date, enforce: bool = False, official_diff_path: Path | None = None, notify: Callable[[str], None] | None = None, catch_up_days: int = 14, keep_points_days: int = 120) -> int`(종료 코드)
- `main(argv=None, *, now=None) -> int` — `python -m ml.nightly [--db ml_data/collector.sqlite] [--points ml_data/points.sqlite] [--state ml_data/change_state.json] [--out ml_data/artifacts/measured_timetable.json] [--log ml_data/changes.md] [--official-diff ml_data/official_diff.json] [--enforce] [--day YYYY-MM-DD]`

순서:
1. 상태를 읽는다. `CorruptState`면 한 줄 알리고 2로 끝낸다(아무것도 쓰지 않음).
2. 처리할 날짜 = `today − catch_up_days` 이후이고 `today` 전인 서비스 날짜 가운데 `events`에 있는 것. 날짜순으로 `extract_day`(이미 뽑았고 `checked`도 지난 날은 건너뜀) → `detect_day`.
3. 호선마다 B1을 만든다: `points` = 요일 구분별 `store.dates(…, upto=어제, n=WINDOW)`의 출발, `active` = 수집 횟수가 `min_coverage` 이상인 (단위 → 날짜), `stale_days=STALE_DAYS`, `window=WINDOW`, `epochs = state.epochs() if enforce else None`. 호선별 `cells`·`scopes`·`modes`·`dwell`을 한 결과물로 합치고 `annotate` → `stations`를 붙여(지금 `b1.main`과 같게) 임시 파일에 쓴 뒤 `os.replace`. 바꾸기 전 것은 `…json.prev`로 남긴다.
4. `append_log`. 이번에 **확정**된 줄만 `notify`로 보낸다(의심은 기록만).
5. `store.prune(today − keep_points_days)`. 요약 한 줄 출력(날짜 수, 의심·확정·해제 수, 칸·시각 수).
6. 한 호선에서 예외가 나면 종류 이름만 로그에 남기고 다음 호선으로 가며, 끝에 1을 돌려준다. 그 호선의 칸은 이전 결과물의 것을 그대로 옮긴다.

- [ ] 테스트 작성(`write_db`로 만든 수집 DB + `store.add_snapshot`으로 raw를 넣거나, 뽑은 뒤 `cover`):
  - `test_first_run_extracts_all_days_and_writes_artifact`: 평일 5일 → 결과물 `version == 2`, `as_of == 어제`, 칸이 있다, 상태 파일이 생긴다.
  - `test_observe_mode_records_but_does_not_apply_epochs`: 5일 + 변경 2일, `enforce=False` → 상태에 `confirmed`, 결과물 `enforce is False`, 그 칸 `secs`는 옛 시각 쪽(중앙값), `scopes`에 `changed`.
  - `test_enforce_applies_epochs`: 같은 데이터 `enforce=True` → 새 시각, `n_days == 2`.
  - `test_catches_up_missed_days_in_order`: 변경 이틀을 한 번에 따라잡아도 확정이고 `first_date`가 첫 변경일.
  - `test_rerun_same_night_changes_nothing`: 두 번 돌린 뒤 상태 파일과 결과물(`built_at` 제외)이 같다.
  - `test_notify_gets_only_confirmed_lines`.
  - `test_corrupt_state_exits_2_and_keeps_old_artifact`.
  - `test_one_line_failing_keeps_its_previous_cells_and_exits_1`(`build_b1_from_points`를 한 호선에서만 던지게 monkeypatch).
  - `test_no_events_db_exits_2`.
- [ ] 실패 확인 → 구현 → 통과 → 전체 통과.
- [ ] VM DB 사본(백업 파일)으로 개발 PC에서 한 번 돌려 걸린 시간과 최대 메모리를 학습 노트에 적는다(목표: 하루치 5분 안, 400MB 안).
- [ ] 학습 노트 77 → 커밋 `feat(ml): 밤마다 변경 판단과 B1 다시 만들기`.

### Task 6: 공식 시간표 다시 받기와 차이(`ml/b0.py`)

**Files:** Modify `server/ml/b0.py`, `server/tests/unit/test_ml_b0.py`, `server/ml/changes.py`, `server/tests/unit/test_ml_changes_state.py`

**Produces:**
- `b0.diff_caches(old: TimetableCache, new: TimetableCache, targets: Iterable[Target], lines_by_code: dict[str, str]) -> dict[Scope, float]` — 단위별로 (`TRAIN_NO`, `LEFTTIME`) 집합의 대칭차 ÷ 합집합. 시간대는 `LEFTTIME`으로. `old`에 없는 대상은 건너뛴다.
- `b0.refresh(http, key, targets, cache_dir: Path, *, today: date, keep: int = 4, sleep=time.sleep) -> tuple[dict[Scope, float], int]` — `cache_dir.next`에 모두 새로 받는다. 실패가 하나라도 있으면 바꾸지 않고 `(빈 dict, 실패 수)`. 성공이면 차이를 계산하고 `cache_dir` → `cache_dir-YYYYMMDD`(최근 `keep`개만 남김), `.next` → `cache_dir`.
- CLI `python -m ml.b0 --refresh [--diff-out ml_data/official_diff.json]` — `{"checked_on": "YYYY-MM-DD", "changed": {scope_key: 비율}}`를 쓴다. 실패가 있으면 종료 코드 1.
- `changes.OFFICIAL_CHANGED_MIN = 0.10`, `changes.load_official_changed(path, *, today: date, max_age_days: int = 8) -> set[Scope]` — 파일이 없거나 오래됐으면 빈 집합.
- `b0.B0.departures(day_type: int, line: str, direction: int, statn_id: str) -> list[tuple[str, int]]` — (열차 번호, 출발 초), 이 역에서 끝나는 열차는 뺀다.
- `changes.official_fit(b0: B0, store: PointStore, state: ChangeState, day: date, *, th) -> list[ChangeRecord]` — 확정 뒤 3일이 지난 2~9호선 기록마다, 공식 시간표를 기준표로(번호 없이, `mode="slots"`) 그 날 관측과 견줘 `moved ≥ th.moved`면 `official="stale"`, 아니면 `"fits"`. 바뀐 기록을 돌려준다. `render`는 `stale`이면 줄 끝에 `공식 시간표가 아직 옛것`을 붙인다.

- [ ] 테스트 작성: 같은 캐시 둘 → `{}` / 한 역의 06시대 10행 가운데 4행의 `LEFTTIME`이 바뀜 → 그 단위 `8/14` / 한 대상 실패 → 디렉터리 그대로, 실패 수 1(respx) / 성공 → 옛 디렉터리가 날짜 이름으로 남고 `keep=1`이면 더 옛것이 지워짐 / `load_official_changed`: 0.05는 빠지고 0.10은 들어감, 9일 지난 파일 → 빈 집합 / `official_fit`: 공식이 옛 시각이고 관측이 +180초 → `"stale"`, 관측과 같으면 `"fits"`.
- [ ] 실패 확인 → 구현 → 통과 → `nightly`에 `--timetable-cache ml_data/timetable_cache`를 더한다(디렉터리가 있을 때만 `official_fit`을 부르고 바뀐 기록을 `changes.md`에 적는다). `nightly` 테스트 둘 추가: `test_official_diff_file_confirms_in_one_day`(`--official-diff` → `detect_day(official_changed=…)`), `test_missing_timetable_cache_is_skipped` → 전체 통과.
- [ ] 학습 노트 78 → 커밋 `feat(ml): 공식 시간표 다시 받아 차이 보기`.

### Task 7: VM 타이머와 운영 문서

**Files:** Create `server/ml/deploy/subway-ml-nightly.service`, `subway-ml-nightly.timer`, `docs/ml/timetable-changes.md` / Modify `server/ml/deploy/setup_vm.sh`, `server/tests/unit/test_ml_deploy.py`, `docs/ml/collector.md`

- `subway-ml-nightly.service`: `Type=oneshot`, `User=@USER@`, `WorkingDirectory=@SERVER_DIR@`, `After=subway-collector-backup.service`, `Nice=10`, `MemoryMax=500M`, `ExecStart=@SERVER_DIR@/.venv/bin/python -m ml.nightly`(관찰 모드. `--enforce`는 Task 9 뒤에 사용자가 더한다).
- `subway-ml-nightly.timer`: `OnCalendar=*-*-* 02:40:00`, `Persistent=true`(백업 02:10 뒤, 수집 시작 05:00 전).
- `setup_vm.sh`: 두 파일을 다른 단위와 같은 방식으로 설치하고 타이머를 켠다. 끝의 안내에 `journalctl -u subway-ml-nightly`와 `ml_data/changes.md`를 더한다.
- `docs/ml/timetable-changes.md`: 신호와 기준값 표, 의심·확정 규칙, `changes.md` 읽는 법, `show`·`mark`·`clear` 예, 관찰 모드에서 `--enforce`로 넘어가는 절차, 공식 시간표 주 1회 받기(어디서 돌릴지는 사용자 결정에 따라), 잘못 확정됐을 때 되돌리기.

- [ ] 테스트 작성(`test_ml_deploy.py`): 타이머가 `02:40`이고 백업(02:10)보다 뒤 / 서비스가 `python -m ml.nightly`를 부르고 `After=subway-collector-backup.service` / 서비스 파일에 `--enforce`가 없다 / 기존 "키 이름·값이 없다" 테스트가 새 파일도 훑는다 / `setup_vm.sh`가 새 단위 둘을 설치하고 여전히 올바른 bash.
- [ ] 실패 확인 → 구현 → 통과 → 학습 노트 79 → 커밋 `feat(ml): 밤 작업 타이머와 변경 운영 안내`.
- [ ] **사용자에게 묻고** VM에 반영한다(`git pull` → `setup_vm.sh` 다시 실행 또는 단위 두 개만 설치). 다음 날 `changes.md`와 `journalctl`을 확인해 `collector.md`에 적는다.

### Task 8: 평가에서 변경 전후 나누기(`ml/evaluate.py`)

**Files:** Modify `server/ml/evaluate.py`, `server/tests/unit/test_ml_evaluate.py`

**Produces:**
- `Report.by_phase: dict[str, Scores]` — `"stable"`, `"post_change"`, `"incident"`.
- `evaluate_b1(train, test, *, b0=None, min_days=…, slot_width=…, mode="auto", state: ChangeState | None = None)`:
  - 학습 쪽 B1은 `epochs` = `state.epochs()` 가운데 변경일이 학습 마지막 날 이하인 것으로 만든다(시험 날짜의 지식을 쓰지 않는다).
  - 시험 관측의 단위에 해제된 의심이 그 날짜로 있으면 `"incident"`. 그 단위의 확정 변경일 D가 관측 날짜 이하이고 학습 쪽에 D 이후 같은 요일 구분 날짜가 `MIN_DAYS_AFTER_CHANGE`개 미만이면 `"post_change"`. 그 밖은 `"stable"`.
  - `overall`·`by_line`·`by_day_type`은 지금처럼 **모든** 관측을 센다(숨기지 않는다). `by_phase`가 더해질 뿐이다.
- `render`에 "구간별" 표(안정 / 변경 직후 / 하루짜리 이상) 추가. `state`가 없으면 표를 내지 않는다.
- CLI `--changes ml_data/change_state.json`. 주석 한 줄: 공식 시간표(B0) 비교는 지금 캐시 한 벌로 하므로, 시험 기간 안에 공식 시간표가 바뀌었으면 그 전 날짜의 B0 숫자는 믿지 않는다.

- [ ] 테스트 작성: 학습 5일(08:00) + 시험 2일(08:05), 시험 첫날부터 확정인 상태 → `by_phase["post_change"].total > 0`, `by_phase["stable"]`에 그 단위 없음, `overall.total`은 `state` 없이 돌린 값과 같다 / 변경일이 학습 기간 안(학습에 변경 뒤 2일)이면 학습 B1이 새 시각을 내고 시험 MAE 0, 구간은 `"stable"` / 해제된 의심 날의 관측은 `"incident"` / `state=None`이면 `by_phase`가 비고 기존 테스트 그대로 통과.
- [ ] 실패 확인 → 구현 → 통과 → 학습 노트 80 → 커밋 `feat(ml): 평가를 안정·변경 직후·하루짜리 이상으로 나눠 보기`.

### Task 9: 기준값 맞추기 — 자료가 쌓인 뒤(평일 15일 이상, 2026-12-07 이후)

**Files:** Modify `server/ml/changes.py`, `server/tests/unit/test_ml_changes_state.py` / Create `docs/ml/2026-12-XX-change-thresholds.md`

**Produces:**
- CLI `python -m ml.changes backtest --points ml_data/points.sqlite [--from D --to D] [--set shifted=0.4,moved=0.4] [--inject LINE:DIR:BAND:SHIFT_SEC:FROM_DATE]` — 빈 상태에서 날짜순으로 `detect_day`를 돌리고(상태 파일은 쓰지 않음) 출력한다: 날짜별 판단한 단위 수·의심·확정·해제, 신호별 분포(p50·p90·p99, 호선별), 연속한 같은 요일 구분 두 날의 `agree` 비율(호선별).
- `--inject`는 그 호선·방향·시간대의 `FROM_DATE` 이후 출발에 `SHIFT_SEC`를 더한 사본으로 돌린다(실제 변경이 없어도 "잡아내는지"를 재려는 것).

- [ ] 테스트: 합성 12일(정상)에서 `backtest` → 확정 0, 출력에 신호 분포 표 / `--inject 9호선:0:6:180:<8일째>` → 9일째에 확정 / `--set shifted=2.0,moved=2.0,…`으로 모두 끄면 의심 0.
- [ ] 구현 → 통과 → 커밋.
- [ ] VM `points.sqlite` 사본으로 돌려 `docs/ml/…-change-thresholds.md`에 적는다: 정상인 날 의심 비율(목표: 단위·날의 2% 이하), 확정 수(알려진 변경이 없으면 목표 0), 호선별 `agree` 비율(낮은 노선은 3일 연속 규칙에만 기대게 됨을 적는다), 심어 본 변경(+120·+180·+300초, 급행 비율, 배차 ±10·20%)을 며칠 만에 잡는지. 수능 날(2026-11-19)과 눈·사고가 있던 날이 어떻게 나왔는지 따로 적는다.
- [ ] 기준값 고칠 것을 사용자에게 제안하고, 승인받으면 `Thresholds` 기본값과 `timetable-changes.md`를 고친다. 사용자가 켜자고 하면 VM 서비스에 `--enforce`를 더한다.
- [ ] 학습 노트 81 → 커밋.

### Task 10 (선택, 사용자가 채널을 정한 뒤에만): 웹훅 알림(`ml/alerts.py`)

**Produces:** `webhook_notifier(kind: str, url: str, http: httpx.Client, *, chat_id: str | None = None) -> Callable[[str], None]` — `kind="discord"`는 `POST url {"content": text}`, `kind="telegram"`은 `POST url {"chat_id": chat_id, "text": text}`. 실패해도 예외를 올리지 않고 종류 이름만 로그에 남긴다. URL은 로그에 찍지 않는다. `nightly.main`은 `.env`의 `ML_ALERT_KIND`, `ML_ALERT_WEBHOOK_URL`, `ML_ALERT_CHAT_ID`가 있을 때만 붙인다.

- [x] 테스트(respx): 두 모양의 본문 / 500·연결 오류에도 `nightly`가 0으로 끝남 / caplog에 URL 문자열 없음 / 환경 변수가 없으면 호출 0회.
- [x] 구현 → 통과 → `timetable-changes.md`에 사용자가 할 일(토큰 만들기, VM `.env`에 넣기) → 커밋. (2026-11-17, Discord만. 변수 이름은 `DISCORD_WEBHOOK_URL`, `ml.b0` 실패도 알림. 학습 노트 89)

---

## 이번에 하지 않는 것

- **서버 통합**(`PredictionService`): 결과물의 `scopes`를 읽어 `retraining`이면 공식 시간표(2~9호선)로 내려가거나 예측을 내지 않고, `suspect`면 "변동 가능"을 표시할지 정한다. `official == "stale"`인 단위에서 공식 시간표 대신 B1을 쓸지도 그때 정한다.
- **루틴 다시 계산과 사용자 알림:** 확정 기록의 단위에 걸린 루틴의 예정 열차를 다시 고르고 알린다. 서버가 `changes`를 읽을 수 있게 된 뒤.
- **공지·데이터셋 갱신일 감시:** 만들지 않는다. 사람이 공지를 봤을 때 쓰는 `mark`가 그 자리를 대신한다.
- **B2(구간 시간):** 기준일을 적용하지 않는다. 급행·일반 순서가 바뀌면 대피역 정차 시간이 달라지지만, 서버 통합 때 B2에도 같은 창(최근 28일)을 주는 것으로 충분한지 평가 숫자로 본다.
- **수집 DB 크기:** 하루 133MB씩 늘어 29GB 디스크가 약 6개월이면 찬다. `events` 보관 기간이나 Parquet 전환은 별도 작업.

## 사용자가 정할 것

1. **결과물을 서버로 얼마나 자주 보낼지.** 승인된 설계는 "주 1회 다시 학습, 배포에 포함"이다. 그대로면 감지가 이틀 만에 확정해도 앱은 최대 일주일 동안 옛 시각을 보여 준다. 매일 밤 만든 결과물을 서버가 받아 가는 길(예: VM이 올려 두고 서버가 하루 한 번 받기)을 서버 통합 계획에 넣을지.
2. **알림 채널.** 기본은 로그 + `ml_data/changes.md`. Discord 웹훅이나 Telegram 봇을 쓰려면 토큰을 만들어 VM `.env`에 넣어야 한다(Task 10).
3. **공식 시간표 주 1회 받기를 어디서 돌릴지.** VM `.env`에 `SEOUL_OPENAPI_KEY`를 더하고 VM 타이머로(주 약 1,800회 호출), 또는 개발 PC에서 손으로. 지금은 위치·실시간·일반 키가 같은 값이라, VM에 넣으면 수집기와 같은 한도를 나눠 쓴다.
4. **기준값**(Task 9에서 숫자와 함께 다시 묻는다): 120초, 30%, 20%, ±20%, ±0.15, 3일 연속.
5. **변경 뒤 관측 일수 하한 2일.** 논의하던 안은 3일이었다. 2일이면 확정과 동시에 새 시각이 나오고, 3일이면 하루 더 `retraining`이다.
6. **토요일·휴일.** 확정까지 일주일, 새 열차가 보이기까지 3주(`min_days` 3)다. 토·휴일만 하한을 2로 낮출지.

**2026-11-17 사용자 답:**
- 1번은 **매주 수요일·일요일**에 결과물을 서버로 보낸다.
- 2번은 **Discord 웹훅**이다. Task 10을 `kind="discord"`로 구현하고, 웹훅 URL은 사용자가 VM `.env`에 넣는다.
- 3번은 서울시 키가 **한도 없는 키 하나**이므로 VM 타이머로 돌린다(이미 반영).
- 5·6번은 **바꾸지 않는다.** `MIN_DAYS`는 토요일·휴일을 포함해 3일, `MIN_DAYS_AFTER_CHANGE`는 2일 그대로다.
- 4번은 Task 9에서 정한다.

## 검토에서 고친 것 (2026-11-16 자체 검토)

| 본 것 | 문제 | 고친 내용 |
|---|---|---|
| 지연과 기준값 | 사고·눈으로 한 시간대가 통째로 3분 늦으면 `shifted`·`moved`가 100%다. 기준값을 올려서는 못 막는다 | 확정 조건을 "이틀 연속"에서 **"이틀이 서로 맞음"**으로 바꿨다. 지연은 날끼리 안 맞는다. 날끼리 원래 잘 안 맞는 노선을 위해 3일 연속 규칙을 따로 뒀다 |
| 여러 날 이어지는 이상(파업 감축 운행, 공사, 폭설) | 3일 연속 규칙이 확정시킨다 | 감축 운행·공사는 실제로 임시 시간표이므로 확정이 맞다. 끝나면 다시 확정된다(각각 2일 늦음). 폭설은 잘못된 확정이 될 수 있고, 값은 "그 단위의 과거를 잃고 2~3일치 중앙값을 쓰는 것"이다. `clear`로 되돌린다 |
| 휴일·특별한 날 | 수능(11-19), 명절 연장 운행, 주중 공휴일은 하루짜리다 | 하루 의심 → 다음 같은 요일 구분에서 해제. B1에는 하루만 섞이므로 중앙값·하한이 막는다. 하루 의심으로는 B1을 바꾸지 않는다 |
| 일부 시간대만 바뀜 | 호선 전체로 보면 05:40~07:00 변경이 묻힌다. 1시간대로 봐도 05시대는 20분만 바뀌어 비율이 희석된다 | 1시간대 단위 + 역별 시각 기준(하류 역은 다음 시간대로 걸림). 희석은 `express`·`numbers` 신호가 보완한다. 30분대로 줄일지는 Task 9의 심어 보기로 판단 |
| 시간대 경계 | 변경 전 06:58, 변경 뒤 07:02인 열차는 기준일이 6시대에만 있으면 옛 관측이 7시대 쪽에 섞인다 | 기준일을 경계 양쪽 5분으로 번지게 했다(Task 2 규칙 2, 테스트 있음) |
| 번호 전체 재부여 | `numbers` 100%인데 시각은 그대로. 유령 열차는 아니지만 번호로 찾는 조회가 깨진다 | `renumber`로 분류해 확정. 확정에 이틀이 필요하고 하한도 2일이라 시각이 비는 날이 없다 |
| 2호선(번호가 안 맞음) | `shifted`·`numbers`를 못 쓴다. 배차 2.5분에 허용 120초라 `moved`도 거의 항상 짝이 맞는다 | 2호선은 `tph`·`express`만 사실상 작동한다고 문서에 적는다. 2호선은 공식 시간표가 있어 B1을 내보내지 않으므로 영향이 작다. 번호가 날마다 다른 다른 노선이 있는지는 Task 9에서 확인 |
| 수집 공백 | 수집기가 멈추면 "열차가 사라짐"으로 보인다 | 시간대별 정상 응답 수가 96회 미만이면 판단하지 않고, 안 보인 열차 빼기도 그 날을 세지 않는다 |
| 감지가 놓쳤을 때 | 창만 있으면 번호가 바뀐 변경의 유령 열차가 20평일 남는다 | 안 보인 열차 빼기(3일)를 감지와 무관하게 항상 적용 |
| 잘못된 확정의 값 | 기준값이 검증 전인데 B1을 깎으면 위험하다 | 관찰 모드로 시작(기록·알림만), Task 9 뒤 `--enforce`. 알림은 확정만 보낸다 |
| VM 메모리 | 28일치 이벤트(약 1,200만 건)를 한 번에 읽으면 836MB VM이 죽는다 | 하루치 출발만 파생 DB에 뽑고, 호선 하나씩 처리. 서비스에 `MemoryMax=500M` |
| 공식 시간표 "안 바뀜"의 뜻 | 지난주와의 차이가 없다고 "API가 옛것"은 아니다(캐시를 받기 전에 이미 바뀌었을 수 있음) | 낡음 판단은 차이가 아니라 **공식 시간표와 관측을 직접 견줘서** 한다(`official_fit`). 차이는 하루 만에 확정하는 데만 쓴다 |
| 평가 | 변경일을 걸친 날짜 분할은 B1을 실제보다 나쁘게, 변경일을 빼면 실제보다 좋게 보이게 한다 | 빼지 않고 구간을 나눠 함께 보고. 학습 쪽은 학습 마지막 날까지 알려진 변경만 쓴다 |
| 배포 주기 | 주 1회 배포면 빠른 감지가 앱에 닿지 않는다 | 이 계획 밖이라 "사용자가 정할 것" 1번으로 올렸다 |

남은 약점: 번호는 같고 시각만 120초 이하로 바뀐 변경은 잡지 못하고(창의 중앙값이 약 10평일에 걸쳐 따라간다), 토·휴일은 느리다. 평일 가운데 특정 요일에만 다니는 열차가 있다면 안 보인 열차 빼기가 그 열차를 지운다(지금 자료로는 있는지 모른다 — Task 9에서 확인). 기준값은 Task 9 전까지 근거 없는 초안이다. 관찰 모드에서도 감지의 기준표에는 기준일을 적용한다(안 그러면 확정한 변경을 매일 다시 의심한다).

## 구현하며 바뀐 것 (2026-11-17, Task 1~8 구현)

Task 1~8을 구현했다(전체 테스트 476개 통과). Task 9(기준값 맞추기)는 자료가 쌓인 뒤, Task 10(웹훅 알림)은 채널이 정해진 뒤다. **VM에는 아직 반영하지 않았다.**

- **번호 안정성 계산을 바꿨다.** "최대 − 최소 ≤ 300초"로 재면 크게 늦은 날 하루로 그 호선이 "번호가 날마다 다르다"로 분류돼 번호 신호가 꺼졌다(상태 기계 테스트가 드러냄). "관측한 날의 70% 이상이 중앙값에서 300초 안"으로 바꿨다(`b1.STABLE_DAY_SHARE`).
- 결과물의 시각마다 `first_seen`·`last_seen`이 붙어, 칸을 통째로 비교하던 기존 B1 테스트 하나의 기대값에 두 칸을 더했다(`version` 단언은 2로).
- 기준일의 5분 번짐을 관측 일수 하한에도 적용했다(시간대 경계 바로 뒤 시각도 하한 2일). 계획의 규칙 3 문장만으로는 `test_epoch_margin…`이 통과할 수 없었다.
- `PointStore.put`(출발 시각을 직접 넣기), `has_day`, `all_lines`를 더했다. 테스트와 Task 9의 되돌려 보기에서 쓴다.
- `b0.refresh`에 `lines_by_code` 인자를 더했다(단위를 만들려면 역 코드 → 호선이 필요하다).
- `run_nightly`는 `official_diff_path`와 `b0`를 받는다(계획의 `official_diff_path`만으로는 공식 시간표와의 직접 비교를 할 수 없다). CLI에 `--timetable-cache`, `--official-diff`, `--stations-db`.
- 파생 DB 보관을 120일에서 70일로 줄였다. 실제 하루치가 약 27MB였다(120일이면 3GB 초과).
- 실제 하루치(2026-11-16, 이벤트 43만 건)로 개발 PC에서 밤 작업 8.9초, 메모리 최대 142MB. 날짜가 하루뿐이라 칸은 0개였다(의도한 동작).
- 평가의 구간 나누기는 B1에만 넣었다(계획과 같음). B0 숫자에 대한 주의는 보고서의 한 줄로 넣었다.
- TDD에서 벗어난 곳: Task 3(신호)은 테스트와 구현을 이어서 쓰고 한 번에 통과했다. 나머지는 실패를 먼저 확인했다.

## 다음 개정 제안 (2026-11-17, 아직 구현하지 않음)

사용자 질문: "시간표 API로 변경을 더 확실하게 감지하고, 실측이 같으면 바로 바꾸는 편이 안전하고 확실하지 않은가?" 검토 결과를 적어 둔다. **사용자가 진행 여부를 아직 정하지 않았다.**

### 지금 방식의 허점

| 허점 | 영향 |
|---|---|
| 느림 | 평일 이틀, 토·휴일은 다음 같은 요일까지 1주, 새 열차가 보이기까지 최대 3주 |
| 기준값이 초안 | 실제 지연 분포로 검증하지 않아 관찰 모드로만 돈다 |
| 작은 변경 | 번호는 같고 시각만 120초 이하로 바뀌면 못 잡는다(중앙값이 약 10평일에 걸쳐 따라감) |
| 2호선 | 번호가 날마다 달라 배차 수·급행 비율만 사실상 작동 |
| 공식 시간표 경로가 꺼져 있음 | VM에 `SEOUL_OPENAPI_KEY`가 없고 주 1회라 지금은 실측만으로 판단한다 |
| 공식 시간표 차이의 오탐 | 한 역이 일시적으로 "데이터 없음"을 돌려주면 그 역 열차가 전부 사라진 것으로 계산된다. `diff_caches`에 방어가 없다 |
| 실패를 모름 | 밤 작업이 실패해도 VM 로그에만 남는다 |
| 앱에 닿지 않음 | 서버 통합 전 |

### 제안: 2~9호선은 "두 시간표 가운데 어느 쪽에 맞는가"로 확정

1. 공식 시간표를 **매일** 받는다(주 1회 → 매일, 하루 약 2천 회). 지난 것과의 차이를 본다.
2. 차이가 난 단위는 "후보"로만 둔다. 그 날 관측을 **옛 공식 시간표와 새 공식 시간표에 각각** 맞춰 본다(번호 없이 시각으로. 2호선은 번호가 절반만 맞는다).
3. 새 시간표에 뚜렷이 더 잘 맞으면 **그날 밤 바로 전환**한다. 새 공식 시각 + 그 노선의 평소 지연으로 시각을 채우고, 실측이 쌓이면 실측으로 넘어간다.
4. 둘 다 안 맞으면 보류한다(지연·사고이거나 API가 시행일보다 먼저 바뀐 경우). 다음 날 다시 본다.
5. 이전 것은 남겨 되돌릴 수 있게 한다(`.prev`, `clear`).
6. API가 없는 11개 노선은 지금 방식(실측만)을 유지한다. 같은 날 여러 노선의 공식 시간표가 함께 바뀌면(전체 개편) 그 날은 다른 노선의 의심 기준을 낮추는 것을 검토한다.

좋은 점: 하루 만에 확정, 토·휴일 시간표를 그 요일이 오기 전에 앎, 절대 기준값(30% 등) 대신 상대 비교라 지연에 덜 속음, 새 시각을 기다리는 빈 구간이 없음.

주의할 점:
- API 변경만으로 바꾸면 안 된다. API가 시행일보다 먼저 바뀌는지 늦게 바뀌는지 모른다(몇 주 받아 봐야 안다). 처음에는 이것도 기록만 한다.
- "실측이 공식과 같은가"로 보면 평소에도 통과하지 못한다(휴일 자료에서 2·5호선 실제 출발은 공식 시각과 평균 1분쯤 어긋났다). 옛 시간표와의 **상대 비교**여야 한다.
- 전부 받기에 성공했을 때만 비교하고, 열차 수가 갑자기 0이 된 역은 변경이 아니라 받기 실패로 다룬다.
- VM에 일반 인증키가 필요하다. 지금은 위치·실시간·일반 키가 같은 값이라 한도를 함께 쓴다.
- 한계: 시간표 API는 2~9호선뿐이고, 앱은 그 노선에서 이미 공식 시간표를 그대로 보여 준다. 그래서 실익은 루틴 알림("타던 열차 시각이 바뀜"), "내가 탄 열차" 예측, 공식 API가 틀렸을 때 알아채는 데 있다. 실측 시간표가 꼭 필요한 노선(1호선·경의중앙선·수인분당선 등)에는 쓸 수 없다.

함께 넣을 것: 밤 작업 실패 알림(종료 코드 1·2일 때), `diff_caches`의 "갑자기 빈 역" 방어.

**2026-11-17 뒤이음:** 이 제안은 설계 `docs/superpowers/specs/2026-11-17-official-timetable-confirmation-design.md`와 계획 `docs/superpowers/plans/2026-11-17-official-timetable-confirmation.md`로 옮겨 구현했다(실패 알림은 빼고, 1호선과 "지나가는 급행 빼기"를 더했다). 2026-11-17 15시에 VM에 반영했다(`docs/ml/timetable-changes.md` 맨 위).

## 다른 PC에서 이어서 하기

- 저장소: `https://github.com/klanary/subway_easy.git`, 브랜치 **`develop`**(계획·코드·문서가 모두 여기 있다. `main`에는 없다).
- 저장소에 없는 것(직접 옮기거나 다시 만든다):
  - `server/.env` — API 키. 커밋하지 않는다.
  - `server/.venv` — `cd server && python -m venv .venv && .venv/Scripts/python -m pip install -e ".[dev]"`(Python 3.12 이상).
  - VM 접속 키 `~/.ssh/subway_vm` — 처음 만든 PC에만 있다. 옮기거나, 새 키를 만들어 VM의 `~/.ssh/authorized_keys`에 공개 키를 더한다.
  - `server/ml_data/` — 로컬 수집 자료·시간표 캐시(2·5호선 휴일). VM의 것이 본자료다.
- 테스트: `cd server && .venv/Scripts/python -m pytest -q`(2026-11-17 뒤이음 뒤 536개 통과, 1개 건너뜀).
- VM 접속 키는 PC마다 따로 만든다. 2026-11-17에 노트북 키(`subway_vm-notebook`)를 Azure Portal의 "실행 명령"으로 `~/.ssh/authorized_keys`에 더했다. 다른 PC도 같은 방법으로 공개 키를 더하면 된다.
- 다음 할 일: ① 11-18 01:30 받기와 02:40 밤 작업 결과 확인(`journalctl -u subway-ml-official -n 5`, `cat ml_data/changes.md`), ② 자료가 며칠 쌓이면 1호선 공식 시간표와 관측 견주기(`python -m ml.analyze --timetable-cache ml_data/timetable_cache`), ③ 평일 15일 이상 쌓이면(12-07 이후) 기준값 맞추기(앞선 계획 Task 9), ④ 밤 작업 실패 알림(채널 정한 뒤).
- 읽을 순서: `docs/ml/2026-11-13-train-eta-model-plan.md`(설계) → `docs/ml/collector.md`(수집기·VM) → `docs/ml/timetable-changes.md`(변경 감지 운영) → 이 문서.
