# 실측 시간표를 서버에 연결 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** VM의 실측 시간표(B1)·구간 시간(B2)을 수·일마다 GitHub 릴리스로 내보내고, 서버가 받아 시간표 API가 없는 노선의 시간표·실시간 대체·환승 열차를 채운다.

**Architecture:** VM에 `ml/publish.py`(B2 만들기 → gzip → manifest → 릴리스 자산 교체)와 수·일 타이머를 더한다. 서버에는 순수 조회 모듈 `app/services/measured.py`(결과물 dict → 조회 함수)와 받기 모듈 `app/services/measured_store.py`(manifest 확인, 검증, 지난 것 유지)를 두고, `TimetableService`가 공식 시간표가 없는 노선에서 실측으로 내려간다. 열차마다 출처(`official`·`measured`)를 달아 응답까지 흘린다.

**Tech Stack:** Python 3.12+, FastAPI, httpx, respx(테스트), pytest; Kotlin/Compose(앱 문구), kotlinx.serialization.

**Spec:** `docs/superpowers/specs/2026-11-17-measured-timetable-serving-design.md`

## Global Constraints

- 1~9호선은 실측이 있어도 공식 시간표를 쓴다. 실측은 `TIMETABLE_LINES`(1~9호선) 밖의 노선에서만, 공식 결과가 비었을 때만 쓴다.
- 릴리스 태그·이름 `measured-timetable`. 자산 이름 `measured_timetable.json.gz`, `segment_times.json.gz`, `manifest.json`. manifest는 **마지막에** 올린다.
- 서버 기본 주소 `https://github.com/klanary/subway_easy/releases/download/measured-timetable`, 설정 `MEASURED_BASE_URL`(빈 문자열이면 끔).
- 받기 확인 주기 6시간(`MEASURED_REFRESH_SEC = 21600`), 신선도 한계 14일(`as_of`가 오늘보다 14일 넘게 이전이면 쓰지 않음).
- 검증 실패·받기 실패 시 지난 것을 유지한다. 요청 처리는 받기를 기다리지 않는다.
- VM 내보내기: 수·일 03:30 KST(`OnCalendar=Wed,Sun *-*-* 03:30:00`, `Persistent=true`), 토큰 `.env`의 `GITHUB_RELEASE_TOKEN`. 토큰 값은 로그·예외·커밋에 남기지 않는다. 토큰이 없으면 종료 코드 2.
- 실패 알림은 기존 `ml.alerts.run_with_alert`(Discord)로.
- 새 출처 이름 `"measured"`, 앱 문구 "실측 기준"(기존 "시간표 기준"과 나란히).
- B1 거르기: 항목 `n_days` < 결과물의 `min_days`(변경 확정 단위는 `min_days_after_change`)는 이미 B1이 뺀다. 서버는 추가로 `scopes`에서 상태가 `suspect` 또는 `retraining`인 단위를 뺀다.
- 서버 테스트: `server/`에서 `.venv/Scripts/python -m pytest -q`. 앱: `android/`에서 `./gradlew.bat testDebugUnitTest lintDebug`.
- 커밋은 `develop`, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 푸시·VM 반영은 사용자에게 묻는다. 학습 노트는 `docs/learning/94-…`부터 작업마다 하나.

## Review Focus

1. **잠든 서버의 첫 요청:** 결과물을 아직 못 받았을 때 `/timetable`은 기다리지 않고 기존처럼 빈 목록(`source: "none"`)을 준다. → Task 3 `test_first_request_does_not_wait_for_download`.
2. **자정 넘는 실측 시각(secs ≥ 86400):** 운행일 다음 날 시각으로 바뀌어야 한다. 23:50 조회에 00:20 열차가 나온다. → Task 1 `test_secs_past_midnight_belong_to_next_calendar_day`.
3. **우리 역 id로 못 이은 statn_id(`stations`의 station_id가 null):** 그 칸은 조용히 건너뛴다. → Task 1 `test_unmapped_station_is_skipped`.
4. **변경 의심 중인 단위:** 시각이 바뀌는 중이라 내보내면 틀린다. → Task 1 `test_suspect_scope_is_excluded`.
5. **깨진·바뀐 자산(sha256 불일치, version 다름):** 지난 것을 계속 쓴다. → Task 2 `test_bad_checksum_keeps_previous`.

---

### Task 1: 실측 결과물 조회 모듈

**Files:**
- Create: `server/app/services/measured.py`
- Create: `server/tests/fixtures/measured/b1_small.json`, `server/tests/fixtures/measured/b2_small.json`
- Test: `server/tests/unit/test_measured.py`

**Interfaces:**
- Consumes: `app.services.timetable.week_tag(day) -> int`, `service_days(at) -> list[tuple[date, int]]`, `app.models.schemas.TimetableTrain`(Task 3에서 `source` 필드가 더해진다 — 이 Task에서 먼저 `source: Literal["official","measured"] = "official"`을 `TimetableTrain`에 더한다).
- Produces:
  - `class MeasuredData` (불변), `MeasuredData.from_artifacts(b1: dict, b2: dict | None) -> MeasuredData`
  - `MeasuredData.as_of: date`
  - `MeasuredData.departures(station_id: str, line: str, direction: str, after: datetime, count: int) -> list[TimetableTrain]` — `source="measured"`, 시간순, `after` 이후.
  - `MeasuredData.arrival_at(line: str, direction: str, train_no: str, station_id: str, service_day: date) -> datetime | None` — 그 열차가 그 역을 떠나는 실측 시각. `modes[line] != "train_no"`거나 없으면 None.
  - `MeasuredData.hop_seconds(line: str, direction: str, from_id: str, to_id: str, *, express: bool, at: datetime) -> float | None` — B2 중앙값. `hops_hour`(요일·시간대) → `hops_day` → `hops` 순으로 있는 것. 이웃이 아니면 None(작업 2가 경로를 따라 더한다).
  - 방향 변환: B1 방향 0 → 2호선 "내선", 그 밖 "상행"; 1 → "외선"/"하행".

픽스처: `b1_small.json`은 실제 형식(version 2)으로 신분당선 강남(statn_id 예 `1077000687`)·양재 상행 평일 칸 각 3개(그중 하나 secs 87600 = 다음 날 00:20), `modes {"신분당선": "slots", "경의중앙선": "train_no"}`, 경의중앙선 공덕·서강대 하행 칸(같은 `train_no` 두 역), `stations`에 null station_id 하나, `scopes`에 `suspect` 단위 하나. `b2_small.json`은 경의중앙선 공덕→서강대 `hops`, `hops_day`, `hops_hour` 각 하나.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_departures_come_from_cells_in_time_order(data):
    got = data.departures("강남", "신분당선", "상행", at("2026-11-18T07:00"), 3)
    assert [t.departs_at.strftime("%H:%M") for t in got] == ["07:05", "07:11"]
    assert all(t.source == "measured" for t in got)

def test_secs_past_midnight_belong_to_next_calendar_day(data):
    got = data.departures("강남", "신분당선", "상행", at("2026-11-18T23:50"), 1)
    assert got[0].departs_at.isoformat().startswith("2026-11-19T00:20")

def test_unmapped_station_is_skipped(data): ...      # null station_id 칸은 어떤 역 id로도 안 나온다
def test_suspect_scope_is_excluded(data): ...        # suspect 단위 시간대의 칸은 빠진다
def test_direction_codes_map_to_names(data): ...      # 경의중앙선 방향 1 → "하행"

def test_arrival_at_matches_train_no_across_stations(data):
    t = data.arrival_at("경의중앙선", "하행", "K123", "서강대", date(2026, 11, 18))
    assert t.strftime("%H:%M") == "08:06"

def test_arrival_at_is_none_for_slots_line(data):
    assert data.arrival_at("신분당선", "상행", "D1", "양재", date(2026, 11, 18)) is None

def test_hop_seconds_prefers_hour_then_day_then_all(data): ...  # 시간대 값 → 없으면 요일 값 → 전체
def test_weekend_uses_day_type_2_and_3(data): ...                # 토요일은 2, 일요일·공휴일은 3 칸
```

- [ ] **Step 2: 실패 확인** — `.venv/Scripts/python -m pytest tests/unit/test_measured.py -q` → `ModuleNotFoundError`.
- [ ] **Step 3: 구현** — `from_artifacts`에서 칸을 `(day_type, line, direction_name, station_id) → [Entry]`로 미리 색인하고(station_id는 `stations[f"{line}|{statn_id}"]["station_id"]`, null이면 건너뜀), `scopes`에서 suspect·retraining 키를 집합으로 둔다(키 형식은 `ml.scopes.scope_key`와 같은 `요일|호선|방향|시간대`; 칸의 시간대는 `secs // 3600 % 24`). `departures`는 `service_days(after)`(+모자라면 다음 날)로 날짜를 골라 `운행일 0시 + secs`.
- [ ] **Step 4: 통과 확인** — 같은 명령, 모두 PASS.
- [ ] **Step 5: 커밋** — `feat(server): 실측 시간표·구간 시간 조회 모듈`

### Task 2: 결과물 받기와 지난 것 유지

**Files:**
- Create: `server/app/services/measured_store.py`
- Modify: `server/app/core/config.py`(`measured_base_url: str = "https://github.com/klanary/subway_easy/releases/download/measured-timetable"`), `server/app/core/container.py`(`AppServices.measured: MeasuredStore`), `server/tests/conftest.py`(`test_settings`에 `measured_base_url=""`)
- Test: `server/tests/unit/test_measured_store.py`

**Interfaces:**
- Consumes: `MeasuredData.from_artifacts`(Task 1).
- Produces:
  - `class MeasuredStore(http: httpx.AsyncClient, base_url: str, clock: Callable[[], float])`
  - `MeasuredStore.current() -> MeasuredData | None` — 동기. 지금 가진 것(신선도 14일 넘으면 None). 6시간이 지났으면 백그라운드 새로 고침 작업을 하나만 띄우고 기다리지 않는다.
  - `async MeasuredStore.refresh() -> bool` — manifest를 받아 `built_at`이 바뀌었으면 두 자산을 받고 sha256·gzip·`version`·`kind`를 검증해 바꾼다. 실패하면 False, 지난 것 유지. `base_url`이 비면 아무것도 안 함.

- [ ] **Step 1: 실패하는 테스트 (respx)**

```python
async def test_refresh_loads_manifest_then_assets(store, releases): ...       # current()가 MeasuredData
async def test_same_built_at_does_not_download_again(store, releases): ...    # 자산 호출 1번
async def test_bad_checksum_keeps_previous(store, releases): ...              # 두 번째 refresh가 sha 불일치 → 첫 것 유지
async def test_download_error_keeps_previous(store, releases): ...            # 500 → False, current() 그대로
def test_stale_as_of_is_not_used(store_with_old_artifact): ...                # as_of 15일 전 → None
def test_current_starts_one_background_refresh_after_6h(store, clock): ...    # 두 번 불러도 작업 하나
def test_empty_base_url_disables(store_disabled): ...                         # 호출 0회, None
```

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_measured_store.py -q` → ModuleNotFoundError.
- [ ] **Step 3: 구현** — 받기는 기존 `httpx.AsyncClient`로(외부 API 한도 대상이 아니므로 `call_upstream` 밖), `follow_redirects=True`(릴리스 주소는 리디렉트). 시작 시 한 번은 `container`가 `asyncio` 작업으로 띄우는 대신 `current()`의 첫 호출이 띄운다(잠든 서버가 깨어날 때 자연스럽게).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 전체 `pytest -q` PASS(설정 기본값이 테스트에서 비어 있어 기존 테스트에 영향 없음).
- [ ] **Step 5: 커밋** — `feat(server): 실측 결과물을 릴리스에서 받아 두기(검증 실패 시 지난 것 유지)`

### Task 3: 응답에 실측 쓰기 (`source: "measured"`)

**Files:**
- Modify: `server/app/models/schemas.py`(`TimetableResponse.source: Literal["official","measured","none"]`, `ArrivalGroup.source`에 `"measured"`, `TransferTrain.source`에 `"measured"`)
- Modify: `server/app/services/timetable_service.py`(생성자에 `measured: MeasuredStore | None = None`), `server/app/api/stations.py`(응답 `source`), `server/app/services/arrival_service.py`(채운 그룹 출처), `server/app/services/route_service.py`(환승 열차 출처), `server/app/core/container.py`
- Test: `server/tests/integration/test_timetable_api.py`, `server/tests/integration/test_arrivals_api.py`(있는 파일 이름에 맞춤), `server/tests/integration/test_routes_api.py`

**Interfaces:**
- Consumes: `MeasuredStore.current()`(Task 2), `MeasuredData.departures`(Task 1), `TIMETABLE_LINES`(`ml.b0`에 있음 — 서버 쪽에는 `app/services/timetable.py`에 `OFFICIAL_LINES = frozenset(f"{n}호선" for n in range(1, 10))`로 둔다).
- Produces: `TimetableService.trains_with_status(...)` 반환은 그대로 `(trains, cache_status)`. 열차의 `source`로 출처를 안다. 응답 `source`는 열차가 있으면 첫 열차의 출처, 없으면 `"none"`. 공식 열차는 `"official"`.

- [ ] **Step 1: 실패하는 테스트** — 결과물 픽스처를 넣은 `MeasuredStore` 대역을 `services.timetable`에 주입하는 픽스처 `measured_loaded`.

```python
async def test_line_without_official_timetable_uses_measured(client, respx_mock, measured_loaded):
    mock_timetable(respx_mock, "seoul_timetable_nodata.json")
    r = await client.get(URL.format("강남"), params={"line": "신분당선", "direction": "상행", "from": "2026-11-18T07:00:00+09:00"})
    assert r.json()["source"] == "measured" and r.json()["trains"][0]["departsAt"][11:16] == "07:05"

async def test_official_line_stays_official(client, respx_mock, measured_loaded):  # 5호선 → "official"
async def test_first_request_does_not_wait_for_download(client, respx_mock):      # 결과물 없음 → trains [] , source "none", 빠름
async def test_arrivals_fill_uses_measured_group_source(...)                      # 실시간 없는 신분당선 방향 → group source "measured"
async def test_transfer_trains_carry_measured_source(...)                         # 환승이 신분당선이면 source "measured"
```

- [ ] **Step 2: 실패 확인** — 해당 테스트 FAIL.
- [ ] **Step 3: 구현** — `trains_with_status`: 공식 결과가 비고 `line not in OFFICIAL_LINES`이고 `measured.current()`가 있으면 그 `departures`. 역 코드가 없는 노선(`code is None`)도 같은 규칙. `ArrivalService._fill_missing`은 `realtime_supported`이고 공식 코드가 없는 노선도 대상에 넣는다(지금은 코드 없으면 빈 목록). 그룹 `source`는 첫 열차의 출처로(`official`→`"timetable"`, `measured`→`"measured"`). `RouteService._transfer_trains`도 같은 매핑.
- [ ] **Step 4: 통과 확인** — 전체 `pytest -q` PASS, `ruff check app tests` 통과.
- [ ] **Step 5: 커밋** — `feat(server): 시간표 없는 노선을 실측 시간표로 채우기(source measured)`

### Task 4: VM 내보내기 (B2 만들기, 릴리스 자산 교체, 수·일 타이머)

**Files:**
- Create: `server/ml/publish.py`
- Create: `server/ml/deploy/subway-ml-publish.service`, `server/ml/deploy/subway-ml-publish.timer`
- Modify: `server/ml/deploy/setup_vm.sh`(두 유닛 설치·켜기, 안내 `journalctl -u subway-ml-publish`), `server/ml/b2.py`(호선별로 만들어 합치는 `build_b2_by_line(db, date_from, date_to) -> dict`)
- Test: `server/tests/unit/test_ml_publish.py`, `server/tests/unit/test_ml_deploy.py`, `server/tests/unit/test_ml_b2.py`

**Interfaces:**
- Consumes: `ml.b2.build_b2`, `ml.dataset.load_events(db, date_from=, date_to=, line=)`, `ml.dataset.build_runs`, `ml.alerts.run_with_alert`.
- Produces:
  - `build_b2_by_line(db: Path, *, date_from: date, date_to: date) -> dict` — 호선마다 `load_events(..., line=line)`로 따로 만들어 `hops`·`hops_day`·`hops_hour`·`dwell`을 합친다(키에 호선이 들어 있어 겹치지 않음). `*` 키(전체 평균)는 표본 수가 가장 큰 것을 남긴다. 메모리를 위해 기본 창은 최근 7일(`PUBLISH_B2_DAYS = 7`).
  - `publish(http: httpx.Client, token: str, repo: str, files: dict[str, bytes]) -> None` — 태그 `measured-timetable` 릴리스를 찾고(404면 만든다), 같은 이름 자산을 지우고 올린다. `manifest.json`은 `files`의 마지막.
  - `main(argv=None) -> int` — `run_with_alert("실측 내보내기", "subway-ml-publish", ...)`. 토큰 없으면 2.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_manifest_lists_sha256_and_dates(tmp_path): ...            # built_at·as_of·두 파일 sha256
@respx.mock
def test_publish_replaces_assets_manifest_last(): ...              # DELETE 옛 자산들 → POST 데이터 2개 → POST manifest (호출 순서)
@respx.mock
def test_publish_creates_release_when_missing(): ...               # GET 404 → POST /releases
def test_missing_token_exits_2(tmp_path, monkeypatch): ...
@respx.mock
def test_token_never_logged(caplog): ...                           # 401 응답에도 토큰 문자열 없음
def test_b2_by_line_merges_lines(tmp_path): ...                    # 두 호선 DB → hops에 두 호선 키 모두
def test_publish_timer_runs_wed_sun_after_nightly(): ...           # "OnCalendar=Wed,Sun *-*-* 03:30:00", Persistent=true
def test_publish_service_names_no_key(): ...                       # "-m ml.publish", MemoryMax, "TOKEN" 문자열 없음
def test_setup_script_installs_publish_units(): ...
```

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_ml_publish.py tests/unit/test_ml_deploy.py tests/unit/test_ml_b2.py -q` → FAIL.
- [ ] **Step 3: 구현** — GitHub REST: `GET https://api.github.com/repos/{repo}/releases/tags/measured-timetable`, `POST /repos/{repo}/releases`, `DELETE /repos/{repo}/releases/assets/{id}`, 올리기는 `POST https://uploads.github.com/repos/{repo}/releases/{id}/assets?name=…`(`Content-Type: application/gzip` 또는 `application/json`). 헤더 `Authorization: Bearer <token>`, `X-GitHub-Api-Version: 2022-11-28`. 로그에는 상태 코드와 자산 이름만. `repo` 기본 `klanary/subway_easy`(설정 `--repo`). 서비스는 `MemoryMax=500M`, `Nice=10`.
- [ ] **Step 4: 통과 확인** — 위 명령 PASS, 전체 `pytest -q` PASS, `bash -n server/ml/deploy/setup_vm.sh`(있는 테스트가 봄).
- [ ] **Step 5: 커밋** — `feat(ml): 수·일 실측 결과물을 GitHub 릴리스로 내보내기`

### Task 5: 앱 — "실측 기준" 표시

**Files:**
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/data/remote/Dtos.kt`(`TimetableDto.source: String = "official"`), `.../ui/common/Copy.kt`(`BY_MEASURED = "실측 기준"`), `.../ui/linemap/StationSheetViewModel.kt`(`StationSheetUi.basis: String?` — `"시간표 기준"`/`"실측 기준"`/null; 기존 `fromTimetable` 대체), `.../ui/linemap/StationSheet.kt`(문구), `.../ui/routedetail/StepText.kt`(환승 열차 출처가 `"measured"`면 "(실측)")
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/linemap/StationSheetViewModelTest.kt`, `.../ui/routedetail/StepTextTest.kt`(있으면)

**Interfaces:**
- Consumes: 서버 응답 `TimetableResponse.source`, `ArrivalGroup.source == "measured"`, `TransferTrain.source == "measured"`(Task 3).
- Produces: 없음(화면).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun measuredGroupsShowMeasuredBasis() = runTest { /* arrivals 그룹 source "measured" → ui.basis == "실측 기준" */ }
@Test fun timetableFromMeasuredShowsMeasuredBasis() = runTest { /* +1시간, TimetableDto.source "measured" → "실측 기준" */ }
@Test fun lineWithMeasuredIsNotListedAsNoTimetable() = runTest { /* 실측 열차가 있으면 noTimetableLines에 없음 */ }
```

- [ ] **Step 2: 실패 확인** — `./gradlew.bat testDebugUnitTest --tests "*StationSheetViewModelTest"` → FAIL.
- [ ] **Step 3: 구현** — 공식과 실측이 섞이면 "시간표 기준"과 "실측 기준"을 둘 다 보인다(`basis`를 목록으로 해도 됨: `List<String>`).
- [ ] **Step 4: 통과 확인** — `./gradlew.bat testDebugUnitTest lintDebug` 통과, lint 오류 0.
- [ ] **Step 5: 커밋** — `feat(android): 실측 시간표 출처 표시`

### Task 6: 문서·학습 노트

**Files:**
- Modify: `docs/ml/collector.md`(내보내기 타이머, 토큰 만들기·넣기 — 사용자가 할 일), `docs/ml/timetable-changes.md`(§ 내보내기), `README.md`(배포: `MEASURED_BASE_URL` 설명), `docs/ml/2026-11-13-train-eta-model-plan.md` §5(서버 통합 1단계 완료 표시)
- Create: `docs/learning/94-…` ~ `98-…`(Task 1~5마다 하나, 형식은 `docs/learning/README.md`)

- [ ] **Step 1:** 문서 고치기. 토큰 만드는 길: GitHub → Settings → Developer settings → Fine-grained tokens → 저장소 `klanary/subway_easy`만, 권한 Contents: Read and write → VM `.env`에 `GITHUB_RELEASE_TOKEN=`.
- [ ] **Step 2:** 전체 서버·앱 테스트 다시 실행해 통과 확인.
- [ ] **Step 3: 커밋** — `docs: 실측 시간표 연결 운영 안내와 학습 노트`

---

## 실행 뒤 사용자에게 물을 것

1. 푸시해도 되는지.
2. VM 반영(`git pull`, `setup_vm.sh` 다시 실행으로 타이머 설치)과 토큰 넣기 — 토큰은 사용자가.
3. 첫 내보내기를 손으로 한 번 돌릴지(`sudo systemctl start subway-ml-publish`), 그 뒤 Render Manual Deploy.
