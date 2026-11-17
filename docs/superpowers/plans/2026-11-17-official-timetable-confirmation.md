# 공식 시간표로 변경을 확정하기 Implementation Plan

**Goal:** 서지 않고 지나가는 급행을 관측에서 빼고, 1~9호선의 공식 시간표를 매일 받아, 달라진 단위를 그날 관측이 옛·새 시간표 가운데 어느 쪽에 맞는지로 확정한다.

**Architecture:** `ml/b0.py`가 매일 시간표를 받아 차이를 날짜별 목록(`official_diff.json`)에 더한다. 밤 작업(`ml/nightly.py`)이 그 항목을 상태 파일의 "후보"로 들이고, 새 모듈 `ml/official.py`가 후보를 판정해 기존 `ChangeRecord` 확정으로 넘긴다. 확정이 실측 시간표에 반영되는 것은 지금처럼 `--enforce`일 때만이다.

**Tech Stack:** Python 3.12+, httpx, SQLite, pytest·respx, ruff, systemd.

**Spec:** `docs/superpowers/specs/2026-11-17-official-timetable-confirmation-design.md`

## Global Constraints

- 모든 명령은 `server/`에서 돈다. 테스트: `.venv/Scripts/python -m pytest -q`(시작할 때 476 passed, 1 skipped). 끝날 때마다 전체가 통과해야 한다.
- 린트: `.venv/Scripts/python -m ruff check ml tests`와 `.venv/Scripts/python -m ruff format ml tests`(줄 길이 100).
- 테스트는 네트워크를 부르지 않는다(`respx`). 기존 도우미 `tests/ml_helpers.py`, `tests/unit/test_ml_official.py`의 `rows`·`cache_with`·`CODES`, `tests/unit/test_ml_changes_signals.py`의 `day_points`를 다시 쓴다.
- TDD: 실패하는 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다.
- 키 값은 출력·기록·커밋하지 않는다. URL을 로그에 남길 때는 `app.clients.http.mask_url`.
- 대상 노선은 1~9호선. 나머지 노선의 동작은 바꾸지 않는다.
- 관측 확인 없이 확정하지 않는다. 새 판정은 `--enforce`와 무관하게 돌고 기록하며, 결과물의 시각은 `--enforce`일 때만 바뀐다.
- 번호는 쓰지 않고 시각으로만 견준다.
- 숫자는 모두 `ml.changes.Thresholds`의 이름 붙은 필드나 모듈 상수로 둔다(값은 아래 각 Task에 있다).
- 커밋은 `develop`에, 메시지는 한국어 `feat(ml): …`·`docs(ml): …` 꼴. **푸시와 VM 반영은 하지 않는다**(사용자에게 묻는다).
- Task마다 학습 노트 하나: `docs/learning/81-…md`부터 번호를 잇고, `docs/learning/80-…md`의 틀(무엇을·왜·막혔던 점·면접 질문)을 따른다. 그 Task의 커밋에 함께 넣는다.

## Review Focus

1. **같은 날 `--refresh`를 두 번 돌림** — 두 번째는 차이가 없다. 그날 이미 적은 차이 항목과 남겨 둔 옛 캐시가 사라지면 안 된다. (Task 2)
2. **밤 작업이 며칠 빠졌다 따라잡음** — 그 사이의 차이 항목이 모두 후보로 들어오고, 날짜순으로 판정된다. (Task 5)
3. **후보가 가리키는 옛 캐시 폴더가 없음** — 밤 작업이 죽지 않고 그 후보만 만료로 닫는다. (Task 5)
4. **전체 개편이라 "그대로"인 자리가 하나도 없음** — 평소 지연을 못 구해도 판정이 돈다(저장된 값이나 0을 쓴다). (Task 4)
5. **자정을 넘는 시각(24시대)과 시간대 경계** — 86400초를 넘는 시각도 같은 규칙으로 표를 센다. (Task 4)

## File Structure

| 파일 | 맡는 일 |
|---|---|
| `server/ml/b0.py` (수정) | 대상 넓히기, 빈 역 방어, 옛 캐시 남기기, 차이 목록 쓰기 |
| `server/ml/changes.py` (수정) | `Thresholds` 새 필드, `Candidate`, 상태 판 2, 차이 목록 읽기 |
| `server/ml/official.py` (새로) | 후보 들이기, 판정 계산, 후보 판정 돌리기, 채우기 |
| `server/ml/nightly.py` (수정) | 위를 밤 작업 순서에 잇기 |
| `server/ml/b1.py` (수정) | 지나가는 급행 빼기, `Entry.official` 필드 |
| `server/ml/deploy/subway-ml-official.{service,timer}` (새로), `setup_vm.sh` (수정) | 매일 01:30 받기 |
| `server/tests/unit/test_ml_official.py` (수정), `test_ml_candidates.py` (새로), `test_ml_nightly.py`·`test_ml_deploy.py` (수정) | 테스트 |
| `docs/ml/timetable-changes.md`, `docs/ml/2026-11-13-train-eta-model-plan.md`, `docs/ml/collector.md` (수정) | 운영 안내 |

---

### Task 0: 서지 않고 지나가는 급행을 출발에서 빼기

**배경(2026-11-17 VM 자료, 9호선 상행 급행 120대):** 위치 API는 급행이 서지 않는 역에서도 이벤트를 보낸다. 급행 정차역(당산·동작·여의도 등)은 도착→출발 간격이 70~115초였고, 지나가는 역은 ① 출발만 있거나(언주·흑석·선유도) ② 도착과 출발이 5~17초 간격이거나(삼전 5초, 구반포 9초, 한성백제 17초) ③ 진입과 출발만 있었다(국회의사당). 지금 `departure_points`는 이것을 모두 "그 역을 떠난 시각"으로 쳐서, 서지 않는 급행이 그 역의 실측 시간표에 들어가고 호선 정차 시간 중앙값도 흐려진다. 정차역에서도 출발이 안 찍힌 경우(염창: 진입·도착만)가 있어 "도착만 있음"으로는 가를 수 없다.

**Files:**
- Modify: `server/ml/b1.py`(`departure_points`, `line_dwell`), `server/ml/nightly.py`(`--reextract`)
- Test: `server/tests/unit/test_ml_b1.py`, `server/tests/unit/test_ml_nightly.py`

**Interfaces:**
- Produces (`ml.b1`):
  - 상수 `PASS_MAX_DWELL_SEC = 20`, `PASS_MIN_EVIDENCE = 5`.
  - `passed_stations(runs: list[Run]) -> set[tuple[str, int, int, str]]` — 급행이 지나가는 것으로 본 `(호선, 방향, express 값, statn_id)`.
  - `is_pass(run: Run, visit: Visit, passed: Set[tuple[str, int, int, str]]) -> bool`
  - `departure_points`와 `line_dwell`은 서명이 그대로이고, 지나간 방문을 뺀다.
- Produces (`ml.nightly`): `run_nightly(..., reextract: bool = False)`, CLI `--reextract`.

**규칙**
- 급행이 아닌 운행(`run.express == 0`)은 건드리지 않는다.
- **방문 하나로 아는 경우:** 도착과 출발이 둘 다 있고 `0 <= dep - arr < 20`이면 지나간 것이다. 20초 이상이면 선 것이다.
- **방문 하나로 모르는 경우**(도착이나 출발 하나만 있음): 같은 `(호선, 방향, express 값, 역)`의 그날 방문 가운데 둘 다 있는 것이 5개 이상이고 그 과반이 "지나감"이면 지나간 것으로 본다. 근거가 모자라면 선 것으로 둔다(지금 동작과 같다).
- 지나간 방문은 `departure_points`가 내지 않고, `line_dwell`의 표본에도 넣지 않는다.
- `--reextract`: 따라잡기 창(14일) 안의 모든 날을 호선 전부 다시 뽑는다(`store.extract_day(db, day)`). 이미 판단한 날의 변경 판단은 다시 하지 않는다.

**한계(문서에 적는다):** 1호선은 출발 이벤트가 드물어(11-16 급행 이벤트 8,396건 가운데 출발 241건) 이 규칙으로 가를 근거가 거의 없다. 그런 노선은 지금과 같이 둔다.

- [ ] **Step 1: 실패하는 테스트** — `express_run(visits)`는 `Run(..., line="9호선", direction=0, express=1, visits=...)`를 만드는 도우미, `H = 8 * 3600`.

```python
def test_express_visit_with_a_few_seconds_between_arrival_and_departure_is_a_pass():
    run = express_run([Visit("stop", arr=H, dep=H + 80), Visit("thru", arr=H + 200, dep=H + 209),
                       Visit("end", arr=H + 400)])
    assert [p.statn_id for p in departure_points([run], fallback_dwell=30)] == ["stop"]

def test_local_train_with_a_short_dwell_is_kept():
    run = replace(express_run([Visit("a", arr=H, dep=H + 9), Visit("end", arr=H + 400)]), express=0)
    assert [p.statn_id for p in departure_points([run], fallback_dwell=30)] == ["a"]

def test_departure_only_visit_follows_the_majority_at_that_station():
    seen = [express_run([Visit("thru", arr=H + i * 600, dep=H + i * 600 + 8), Visit("end", arr=H + i * 600 + 300)], train_no=f"9{i}") for i in range(5)]
    only_dep = express_run([Visit("thru", dep=H + 5000), Visit("end", arr=H + 5300)], train_no="99")
    assert ("9호선", 0, 1, "thru") in passed_stations(seen + [only_dep])
    assert departure_points(seen + [only_dep], fallback_dwell=30) == []

def test_arrival_only_visit_at_a_stop_station_is_kept():
    seen = [express_run([Visit("stop", arr=H + i * 600, dep=H + i * 600 + 80), Visit("end", arr=H + i * 600 + 300)], train_no=f"9{i}") for i in range(5)]
    only_arr = express_run([Visit("stop", arr=H + 5000), Visit("end", arr=H + 5300)], train_no="99")
    points = departure_points(seen + [only_arr], fallback_dwell=30)
    assert len(points) == 6 and points[-1].estimated

def test_one_sided_visit_without_enough_evidence_is_kept():
    only_dep = express_run([Visit("thru", dep=H), Visit("end", arr=H + 300)])
    assert len(departure_points([only_dep], fallback_dwell=30)) == 1

def test_pass_visits_do_not_pull_down_the_line_dwell():
    stops = [express_run([Visit("stop", arr=H + i * 600, dep=H + i * 600 + 60), Visit("thru", arr=H + i * 600 + 200, dep=H + i * 600 + 208), Visit("end", arr=H + i * 600 + 400)], train_no=f"9{i}") for i in range(20)]
    assert line_dwell(stops, fallback=30) == {"9호선": 60}
```

`test_ml_nightly.py`:

```python
def test_reextract_replaces_points_for_days_in_the_window(tmp_path, paths): ...
    # 한 번 돌린 뒤 points.sqlite에서 한 날의 출발을 지우고 night(reextract=True) → 다시 채워짐, state.checked는 그대로
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 전체 테스트·린트 통과** — 급행을 쓰는 기존 합성 자료의 정차 시간이 20초 미만이면 그 자료를 20초 이상으로 고친다(뜻이 "정차"인 자료이므로).
- [ ] **Step 5: 커밋** — `fix(ml): 서지 않고 지나가는 급행을 출발 시각에서 빼기`

---

### Task 1: 대상을 1~9호선·세 요일 구분으로

**Files:**
- Modify: `server/ml/b0.py`(`TIMETABLE_LINES`, `main`의 대상 만들기, "2~9호선" 주석), `server/ml/analyze.py:173`(문구)
- Test: `server/tests/unit/test_ml_official.py`

**Interfaces:**
- Produces: `ml.b0.TIMETABLE_LINES == ("1호선", …, "9호선")`, `ml.b0.DAY_TYPES = (1, 2, 3)`, `ml.b0.targets_for(codes: dict[tuple[str, str], str], lines: set[str]) -> list[Target]`(정렬됨, 요일 구분 셋 × 방향 둘).

- [ ] **Step 1: 실패하는 테스트**

```python
def test_targets_cover_lines_1_to_9_and_all_three_day_types():
    codes = {("1호선", "s1"): "0150", ("9호선", "s9"): "4101", ("경의중앙선", "k"): "1219"}
    targets = targets_for(codes, set(TIMETABLE_LINES))
    assert "1호선" in TIMETABLE_LINES and len(TIMETABLE_LINES) == 9
    assert targets == sorted(
        (code, day_type, inout)
        for code in ("0150", "4101") for day_type in (1, 2, 3) for inout in (1, 2)
    )
```

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_ml_official.py::test_targets_cover_lines_1_to_9_and_all_three_day_types -q` → ImportError.
- [ ] **Step 3: 구현** — `TIMETABLE_LINES`를 `range(1, 10)`으로, `targets_for`를 더하고 `main`이 그것을 쓰게 한다(수집 DB의 `day_type`을 더는 읽지 않는다). `station_codes`는 이미 `TIMETABLE_LINES`를 보므로 1호선이 따라 들어온다. 문서 문자열의 "2~9호선"을 "1~9호선"으로.
- [ ] **Step 4: 전체 테스트** — 1호선이 "시간표 없는 노선"이라고 가정한 기존 테스트가 있으면 경의중앙선으로 바꾼다. 전체 통과.
- [ ] **Step 5: 커밋** — `feat(ml): 공식 시간표 대상을 1~9호선과 세 요일 구분으로`

---

### Task 2: 빈 역 방어, 옛 캐시 남기기, 차이 목록

**Files:**
- Modify: `server/ml/b0.py`(`refresh`, `main`), `server/ml/changes.py`(`load_official_changed`)
- Test: `server/tests/unit/test_ml_official.py`

**Interfaces:**
- Produces (`ml.b0`):
  - 상수 `EMPTY_STREAK_ACCEPT = 3`, `EMPTY_FAIL_SHARE = 0.20`, `KEEP_OLD_CACHES = 6`, `DIFF_KEEP_DAYS = 30`.
  - `target_key(target: Target) -> str` — `"0150_1_2"` 꼴.
  - ```python
    @dataclass(frozen=True)
    class RefreshResult:
        changed: dict[Scope, float]
        failed: int                    # 0이 아니면 아무것도 바꾸지 않았다
        skipped: list[str]             # 빈 응답이라 지난 행을 유지한 대상(target_key)
        old_cache: str | None          # 차이가 있어 남긴 옛 캐시 폴더 이름
        empty_streaks: dict[str, int]  # 대상별 연속 빈 응답 횟수(다음 받기에 넘긴다)
    def refresh(http, key, targets, cache_dir, *, today: date, lines_by_code: dict[str, str],
                empty_streaks: dict[str, int] | None = None, protected: Set[str] = frozenset(),
                keep: int = KEEP_OLD_CACHES, sleep=time.sleep) -> RefreshResult
    ```
  - `load_diff_log(path) -> dict` — `{"version": 2, "entries": [...], "empty_streaks": {...}}`. 파일이 없거나 옛 꼴(`checked_on`이 맨 위에 있음)·깨진 파일이면 빈 목록.
  - `record_refresh(path, result: RefreshResult, *, today: date) -> None` — 항목 `{"checked_on", "changed": {scope_key: 비율}, "old_cache", "skipped"}`를 더하고 30일 지난 항목을 버린다.
- Produces (`ml.changes`): `load_official_changed(path, *, today, max_age_days=8) -> set[Scope]`가 새 꼴을 읽는다(기간 안 항목들의 합집합, 비율 `OFFICIAL_CHANGED_MIN` 이상).

**규칙(테스트가 고정한다)**
- 지난 캐시에 행이 1개 이상이던 대상이 이번에 0행이면: 연속 횟수 +1. 횟수가 3 미만이면 지난 행을 새 캐시에 그대로 옮기고 `skipped`에 넣고 차이에서 뺀다. 3이 되면 0행을 받아들이고 횟수를 지운다. 행이 다시 오면 횟수를 지운다.
- `skipped`가 대상 수의 20%를 넘으면 실패로 본다(`failed = len(skipped)`, 아무것도 안 바꿈, 횟수도 그대로).
- 차이가 없으면 옛 캐시를 남기지 않고 새것으로 바꾸기만 한다(`old_cache is None`).
- 차이가 있으면 옛 캐시를 `timetable_cache-YYYYMMDD`로 남긴다. **그 이름의 폴더가 이미 있으면 있던 것을 그대로 두고** 지금 캐시는 버린다.
- 날짜 붙은 폴더는 `protected`에 든 이름은 지우지 않고, 나머지 가운데 최근 `keep`개만 둔다.
- `record_refresh`: 같은 `checked_on` 항목이 이미 있으면 `changed`를 합치고, 있던 `old_cache`가 `None`이 아니면 그것을 지킨다.
- `main --refresh`: `--state`(기본 `ml_data/change_state.json`)의 `candidates` 가운데 `status == "open"`인 것의 `old_cache`를 `protected`로 넘긴다(파일이 없거나 못 읽으면 빈 집합. `ml.changes`를 import하지 않고 JSON으로 직접 읽는다 — 순환 import를 피한다).

- [ ] **Step 1: 실패하는 테스트** (`test_ml_official.py`에 더한다. 기존 `test_refresh_*` 세 개는 반환값이 `RefreshResult`로 바뀌므로 함께 고친다.)

```python
def test_station_suddenly_empty_is_kept_and_skipped(tmp_path):
    # 지난 캐시: cd-A 10행. 이번 응답: cd-A는 INFO-200(0행), 나머지는 그대로.
    result = refresh_with(tmp_path, empty={"cd-A"}, n_targets=10)
    assert result.failed == 0 and result.changed == {} and result.old_cache is None
    assert result.skipped == ["cd-A_1_1"] and result.empty_streaks == {"cd-A_1_1": 1}
    assert len(TimetableCache(tmp_path / "tt").rows("cd-A", 1, 1)) == 10

def test_third_empty_reply_in_a_row_is_accepted(tmp_path):
    result = refresh_with(tmp_path, empty={"cd-A"}, n_targets=10, empty_streaks={"cd-A_1_1": 2})
    assert result.skipped == [] and result.empty_streaks == {}
    assert TimetableCache(tmp_path / "tt").rows("cd-A", 1, 1) == []
    assert SCOPE in result.changed

def test_too_many_empty_targets_fail_the_whole_refresh(tmp_path):
    result = refresh_with(tmp_path, empty={"cd-A", "cd-B", "cd-C"}, n_targets=10)  # 30%
    assert result.failed == 3
    assert not list(tmp_path.glob("tt-*"))  # 아무것도 바꾸지 않았다

def test_old_cache_is_kept_only_when_something_changed(tmp_path):
    same = refresh_with(tmp_path, n_targets=10)
    assert same.old_cache is None and not list(tmp_path.glob("tt-*"))
    moved = refresh_with(tmp_path, shift=180, n_targets=10)
    assert moved.old_cache == f"tt-{TODAY:%Y%m%d}" and (tmp_path / moved.old_cache).is_dir()

def test_second_refresh_on_the_same_day_keeps_entry_and_old_cache(tmp_path):  # Review Focus 1
    log = tmp_path / "official_diff.json"
    first = refresh_with(tmp_path, shift=180, n_targets=10)
    record_refresh(log, first, today=TODAY)
    second = refresh_with(tmp_path, shift=180, n_targets=10)   # 이미 새 캐시와 같다
    record_refresh(log, second, today=TODAY)
    (entry,) = load_diff_log(log)["entries"]
    assert entry["old_cache"] == first.old_cache and scope_key(SCOPE) in entry["changed"]
    assert (tmp_path / first.old_cache).is_dir()

def test_protected_old_caches_survive_pruning(tmp_path):
    for i in range(8):
        (tmp_path / f"tt-2026110{i + 1}").mkdir()
    refresh_with(tmp_path, shift=180, n_targets=10, protected={"tt-20261101"}, keep=2)
    names = sorted(p.name for p in tmp_path.glob("tt-*"))
    assert "tt-20261101" in names and len(names) == 3   # 보호 1 + 최근 2

def test_diff_log_drops_old_entries_and_reads_legacy_file_as_empty(tmp_path):
    log = tmp_path / "official_diff.json"
    log.write_text('{"checked_on": "2026-11-01", "changed": {}}', encoding="utf-8")  # 옛 꼴
    assert load_diff_log(log)["entries"] == []
    record_refresh(log, RefreshResult({SCOPE: 0.5}, 0, [], "tt-20261001", {}), today=date(2026, 10, 1))
    record_refresh(log, RefreshResult({}, 0, [], None, {}), today=TODAY)
    assert [e["checked_on"] for e in load_diff_log(log)["entries"]] == [TODAY.isoformat()]

def test_load_official_changed_reads_entries_within_age(tmp_path):
    log = tmp_path / "official_diff.json"
    record_refresh(log, RefreshResult({SCOPE: 0.5}, 0, [], "tt-x", {}), today=TODAY - timedelta(days=3))
    assert load_official_changed(log, today=TODAY) == {SCOPE}
    assert load_official_changed(log, today=TODAY + timedelta(days=9)) == set()
```

`refresh_with(tmp_path, *, empty=(), shift=0, n_targets, **kw)`는 테스트 도우미로 쓴다: `tmp_path/"tt"`가 없으면 `cd-A`…로 `n_targets`개 역(요일 1·방향 1)의 `rows()` 캐시를 만들고, `respx`로 `empty`에 든 역은 `INFO-200`, 나머지는 `rows(code, shift=shift)`를 돌려주게 한 뒤 `refresh(...)`를 부른다(기존 `test_refresh_swaps_directories_and_reports_the_diff`의 `reply` 꼴을 따른다).

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_ml_official.py -q` → 새 테스트 실패.
- [ ] **Step 3: 구현** — 위 인터페이스와 규칙대로. `main --refresh`는 `load_diff_log`에서 `empty_streaks`를 읽어 넘기고 `record_refresh`로 적는다. 끝줄 출력에 건너뛴 대상 수를 더한다.
- [ ] **Step 4: 전체 테스트·린트 통과**
- [ ] **Step 5: 커밋** — `feat(ml): 공식 시간표 받기에 빈 역 방어와 차이 목록`

---

### Task 3: 상태 파일에 후보와 평소 지연

**Files:**
- Modify: `server/ml/changes.py`
- Create: `server/ml/official.py`
- Test: `server/tests/unit/test_ml_candidates.py`(새로), `server/tests/unit/test_ml_changes_state.py`

**Interfaces:**
- Consumes: Task 2의 차이 항목 꼴.
- Produces (`ml.changes`):
  - `STATE_VERSION = 2`
  - ```python
    @dataclass
    class Candidate:
        scope: Scope
        found_on: date                 # 공식 시간표를 받은 날
        old_cache: str                 # 옛 캐시 폴더 이름
        ratio: float
        status: str = "open"           # open | confirmed | expired | superseded
        tries: list[tuple[date, str]] = field(default_factory=list)   # (판정한 날, 결과 글자)
        closed_on: date | None = None
        note: str = ""
    ```
    `to_json`/`from_json`은 `ChangeRecord`와 같은 방식.
  - `ChangeState.candidates: list[Candidate]`, `ChangeState.delays: dict[str, int]`(`"호선|방향"` → 초). `to_json`에 둘 다 넣고, `load`는 두 키가 없는 판 1 파일도 읽는다.
  - `ChangeState.open_candidate(scope) -> Candidate | None`
  - `Thresholds`에 필드를 더한다: `same_sec=20`, `tie_sec=20`, `vote_reach_sec=300`, `delay_reach_sec=180`, `base_reach_sec=60`, `base_fit=0.60`, `base_min_slots=10`, `min_votes=20`, `new_share=0.70`, `old_share=0.30`, `tries_weekday=5`, `tries_other=3`.
- Produces (`ml.official`):
  - `ingest_candidates(state: ChangeState, entries: list[dict]) -> list[Candidate]` — 새로 들인 후보.

**규칙**
- 항목을 `checked_on` 순서로 본다. `old_cache`가 `None`인 항목은 건너뛴다.
- `(scope, found_on)`이 이미 있으면 다시 넣지 않는다.
- 같은 단위에 열린 후보가 있으면 `superseded`로 닫고(`closed_on = 새 found_on`) 새 후보를 넣는다.
- 닫힌 지 `CHANGES_KEEP_DAYS`(60일)가 넘은 후보는 `ChangeState.save` 전에 버린다(`prune_candidates(state, today)`).

- [ ] **Step 1: 실패하는 테스트**

```python
SCOPE = (1, "9호선", 0, 6)
ENTRY = {"checked_on": "2026-11-18", "changed": {scope_key(SCOPE): 0.12}, "old_cache": "tt-20261118", "skipped": []}

def test_state_round_trips_candidates_and_delays(tmp_path):
    state = ChangeState(delays={"9호선|0": 40})
    ingest_candidates(state, [ENTRY])
    state.candidates[0].tries.append((date(2026, 11, 18), "hold:few_votes"))
    state.save(tmp_path / "s.json")
    loaded = ChangeState.load(tmp_path / "s.json")
    assert loaded.candidates == state.candidates and loaded.delays == {"9호선|0": 40}
    assert json.loads((tmp_path / "s.json").read_text(encoding="utf-8"))["version"] == 2

def test_version_1_state_file_loads_with_no_candidates(tmp_path):
    (tmp_path / "s.json").write_text('{"version": 1, "checked": {}, "records": []}', encoding="utf-8")
    state = ChangeState.load(tmp_path / "s.json")
    assert state.candidates == [] and state.delays == {}

def test_ingest_is_idempotent_and_skips_entries_without_old_cache():
    state = ChangeState()
    assert len(ingest_candidates(state, [ENTRY, {**ENTRY, "checked_on": "2026-11-19", "old_cache": None}])) == 1
    assert ingest_candidates(state, [ENTRY]) == []
    assert state.open_candidate(SCOPE).ratio == 0.12

def test_newer_candidate_supersedes_the_open_one():
    state = ChangeState()
    ingest_candidates(state, [ENTRY, {**ENTRY, "checked_on": "2026-11-20", "old_cache": "tt-20261120"}])
    assert [c.status for c in state.candidates] == ["superseded", "open"]
    assert state.candidates[0].closed_on == date(2026, 11, 20)

def test_closed_candidates_are_pruned_after_60_days():
    state = ChangeState()
    ingest_candidates(state, [ENTRY])
    state.candidates[0].status, state.candidates[0].closed_on = "expired", date(2026, 11, 20)
    prune_candidates(state, date(2027, 1, 18))
    assert len(state.candidates) == 1
    prune_candidates(state, date(2027, 1, 20))
    assert state.candidates == []
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `ml/official.py`는 `ml.changes`와 `ml.b0`를 import한다(반대 방향은 없다).
- [ ] **Step 4: 전체 테스트·린트 통과** — 상태 JSON 전체를 견주는 기존 테스트는 새 키에 맞춰 고친다.
- [ ] **Step 5: 커밋** — `feat(ml): 변경 상태에 공식 시간표 후보와 평소 지연`

---

### Task 4: 판정 계산(순수 함수)

**Files:**
- Modify: `server/ml/official.py`
- Test: `server/tests/unit/test_ml_candidates.py`

**Interfaces:**
- Consumes: Task 3의 `Thresholds` 필드.
- Produces (`ml.official`). 시각은 모두 운행일 0시부터의 초, 표는 `역 id → 정렬된 시각 목록`(그 호선·방향·요일 구분의 하루 전체)이다.
  - `split_changed(old: list[int], new: list[int], same_sec: int) -> tuple[list[int], list[int], list[int]]` — `(그대로, 옛것만, 새것만)`. "그대로"는 옛 시각으로 적는다.
  - `usual_delay(same: dict[str, list[int]], obs: dict[str, list[int]], reach_sec: int) -> int | None` — 관측마다 가장 가까운 "그대로" 시각과의 차(관측 − 시각)가 `reach_sec` 안인 것들의 중앙값. 표본이 20개 미만이면 `None`.
  - ```python
    @dataclass(frozen=True)
    class Verdict:
        result: str            # confirm | early | hold
        reason: str            # "" | few_votes | mixed | base_fit | no_obs
        new_votes: int
        old_votes: int
        base_fit: float | None
    def judge_scope(old: dict[str, list[int]], new: dict[str, list[int]], obs: dict[str, list[int]],
                    *, band: int, delay: int, th: Thresholds = DEFAULT) -> Verdict
    ```

**`judge_scope`의 순서**(스펙 5.5)
1. 관측에서 `delay`를 뺀다. 뺀 시각이 `band`에 드는 관측만 센다. 하나도 없으면 `hold`/`no_obs`.
2. 역마다 `split_changed`. "그대로"인 자리 가운데 시각이 `band`에 드는 것들에서, `base_reach_sec`(60초) 안에 (지연 뺀) 관측이 있는 비율이 `base_fit`. 그런 자리가 `base_min_slots` 미만이면 `None`이고 막지 않는다. `base_fit < th.base_fit`이면 `hold`/`base_fit`.
3. 센 관측마다 그 역의 가장 가까운 옛 시각까지 거리 `d_old`, 새 시각까지 `d_new`. 둘 다 `vote_reach_sec` 밖이면 표 없음. `abs(d_old - d_new) < tie_sec`이면 표 없음. 아니면 가까운 쪽에 한 표.
4. 표가 `min_votes` 미만 → `hold`/`few_votes`. 새 쪽 비율 ≥ `new_share` → `confirm`. ≤ `old_share` → `early`. 그 밖 → `hold`/`mixed`.

- [ ] **Step 1: 실패하는 테스트** — `table(shift=0, n=14, gap=240, stations=("A","B","C"), start=6*3600)`은 역 세 곳에 `start`부터 `gap` 간격 `n`대(역마다 +120초)를 만드는 도우미다. 옛 표와 새 표의 "일부만 다름"은 `retimed(table, every=2, by=60)`(짝수 번째 열차만 `by`초 늦춤)으로 만든다.

```python
def test_split_changed_pairs_within_tolerance():
    assert split_changed([100, 400, 700], [110, 460, 1000], 20) == ([100], [400, 700], [460, 1000])

def test_usual_delay_is_the_median_offset_or_none():
    same = table()
    assert usual_delay(same, shifted(same, 40), 180) == 40          # reach는 th.delay_reach_sec
    assert usual_delay({"A": [100]}, {"A": [140]}, 180) is None      # 표본 부족

def test_observations_matching_new_times_confirm():
    old, new = table(), retimed(table(), every=2, by=60)             # 1분짜리 변경
    v = judge_scope(old, new, shifted(new, 40), band=6, delay=40)
    assert (v.result, v.old_votes) == ("confirm", 0) and v.new_votes >= 20   # 역 3곳 × 7대

def test_observations_matching_old_times_mean_the_api_changed_early():
    old, new = table(), retimed(table(), every=2, by=60)
    assert judge_scope(old, new, shifted(old, 40), band=6, delay=40).result == "early"

def test_a_delayed_day_is_held_not_confirmed():
    old, new = table(), retimed(table(), every=2, by=60)
    late = shifted(old, 40 + 30)   # 옛 시간표대로인데 30초 더 늦은 날: 옛·새 한가운데
    v = judge_scope(old, new, late, band=6, delay=40)
    assert v.result == "hold"

def test_few_changed_trains_are_held_for_lack_of_votes():
    old = table()
    new = retimed(old, every=12, by=60)                              # 역마다 두 대만 바뀜(6표)
    v = judge_scope(old, new, shifted(new, 0), band=6, delay=0)
    assert (v.result, v.reason) == ("hold", "few_votes")

def test_official_table_that_does_not_describe_the_line_is_held():
    old, new = table(), retimed(table(), every=2, by=60)
    unrelated = table(shift=150, gap=420)                            # 공식과 무관하게 다니는 노선
    v = judge_scope(old, new, unrelated, band=6, delay=0)
    assert (v.result, v.reason) == ("hold", "base_fit") and v.base_fit < 0.6

def test_whole_table_changed_still_judges_without_same_slots():      # Review Focus 4
    old, new = table(), table(shift=90)
    v = judge_scope(old, new, shifted(new, 0), band=6, delay=0)
    assert v.result == "confirm" and v.base_fit is None

def test_no_observation_in_band_is_held():
    old, new = table(), retimed(table(), every=2, by=60)
    assert judge_scope(old, new, {}, band=6, delay=0).reason == "no_obs"

def test_times_after_midnight_are_counted_the_same_way():            # Review Focus 5
    old = table(start=24 * 3600)
    new = retimed(old, every=2, by=60)
    assert judge_scope(old, new, shifted(new, 0), band=24, delay=0).result == "confirm"
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — 가장 가까운 시각 찾기는 `bisect`.
- [ ] **Step 4: 전체 테스트·린트 통과**
- [ ] **Step 5: 커밋** — `feat(ml): 옛·새 공식 시간표 가운데 관측이 맞는 쪽 판정`

---

### Task 5: 밤 작업에 잇기

**Files:**
- Modify: `server/ml/official.py`, `server/ml/nightly.py`, `server/ml/changes.py`(기록 줄)
- Test: `server/tests/unit/test_ml_candidates.py`, `server/tests/unit/test_ml_nightly.py`

**Interfaces:**
- Consumes: Task 2 `load_diff_log`, Task 3 `ingest_candidates`·`Candidate`·`ChangeState.delays`, Task 4 `judge_scope`·`usual_delay`·`split_changed`, 기존 `ml.b0.B0(TimetableCache(dir), codes).departures(day_type, line, direction, statn_id) -> list[tuple[str, int]]`, `PointStore.points`·`coverage`·`lines`.
- Produces (`ml.official`):
  - `judge_candidates(state: ChangeState, store: PointStore, cache_dir: Path, codes: dict[tuple[str, str], str], day: date, *, th: Thresholds = DEFAULT) -> tuple[list[ChangeRecord], list[str]]` — (새로 확정된 기록, 기록 파일에 쓸 줄).
  - `candidate_lines(found: list[Candidate]) -> list[str]` — 새 후보를 알리는 줄.
- Produces (`ml.nightly.run_nightly`): 새 인자 `timetable_cache: str | Path | None = None`, `codes: dict[tuple[str, str], str] | None = None`. `main`이 `--timetable-cache`와 `station_codes(...)`를 넘긴다.

**`judge_candidates`의 규칙**
- 열린 후보 가운데, 단위의 요일 구분이 그 날의 것이고(`store.lines(day)`), `day >= found_on - 1일`이고, 그 날의 시도가 아직 없는 것만 본다.
- 그 날 그 호선·시간대의 정상 응답(`store.coverage`)이 `th.min_coverage` 미만이면 건너뛴다(시도로 세지 않는다).
- 옛 표는 `cache_dir.parent / candidate.old_cache`, 새 표는 `cache_dir`. **옛 폴더가 없으면** 그 후보를 `expired`(`note="옛 시간표 없음"`)로 닫고 계속한다.
- 평소 지연: 그 호선·방향의 "그대로" 자리로 `usual_delay`(`th.delay_reach_sec`) → `None`이면 같은 호선의 다른 방향 것 → `state.delays["호선|방향"]` → 0. 구한 값은 `state.delays`에 적는다.
- 결과를 `tries`에 `(day, "confirm" | "early" | "hold:<reason>")`으로 더한다.
- `confirm`: 후보를 `confirmed`로 닫고 `ChangeRecord(scope, status="confirmed", first_date=day, days=[day], missed=[], decided_date=day, kind="official", triggers=(), signals={"new_votes": n, "old_votes": m}, source="official", official="changed", note=f"새 {n}표·옛 {m}표")`를 `state.records`에 넣는다. 그 단위에 열린 실측 의심은 `cleared`(`note="공식 시간표로 확정함"`)로 닫는다.
- 시도 수가 평일 `th.tries_weekday`, 그 밖 `th.tries_other`에 이르고도 확정이 아니면 `expired`.
- 기록 줄: 같은 (요일 구분·호선·방향·문구)의 이어진 시간대는 `merge_bands`로 한 줄에 합친다. 문구는 아래 그대로.

| 경우 | 줄 |
|---|---|
| 새 후보 | `- 2026-11-18 후보 \| 9호선 상행 평일 6~7시 \| 공식 시간표가 달라짐` |
| 보류 | `- 2026-11-18 후보 보류 \| … \| 표 모자람` / `엇갈림` / `공식 시간표가 평소 운행과 안 맞음` / `관측 없음` |
| 먼저 바뀜 | `- 2026-11-18 후보 보류 \| … \| 공식 시간표가 먼저 바뀜` |
| 만료 | `- 2026-11-25 후보 만료 \| … \| 5번 보고도 정하지 못함` (옛 폴더가 없으면 `옛 시간표 없음`) |

확정 줄은 기존 `render`가 낸다(`… | official | 새 37표·옛 3표 | 2026-11-18부터 | official`).

**`run_nightly`에 넣는 자리**
- 날짜 반복에 들어가기 전: `official_diff_path`가 있으면 `ingest_candidates(state, load_diff_log(path)["entries"])`, 그 줄을 모아 둔다.
- 날짜마다 `detect_day` **앞에** `judge_candidates`. (확정되면 그 단위의 기준일이 그 날이 되어, 이어지는 `detect_day`의 기준표에서 그 단위가 빠진다.) `timetable_cache`나 `codes`가 없으면 건너뛴다.
- 판정이 예외를 내면 `logger.error` 뒤 `failed = True`로 두고 그 날의 `detect_day`는 그대로 돈다.
- 모은 후보 줄은 확정·의심 줄과 함께 `append_log`로 쓴다. 저장 전에 `prune_candidates`.

- [ ] **Step 1: 실패하는 테스트** (`test_ml_candidates.py`. 도우미 `scene(tmp_path, *, old_shift=0, new_shift=180)`은 `tmp_path/"tt-20261118"`에 옛 표, `tmp_path/"tt"`에 새 표를 `cache_with`로 만들고 열린 후보 하나가 든 `state`와 `store`를 돌려준다.)

```python
def test_candidate_is_confirmed_the_night_observations_match_the_new_table(tmp_path):
    state, store = scene(tmp_path)
    store.put(day_points(DAY, shift=180)); cover(store, [DAY])
    records, lines = judge_candidates(state, store, tmp_path / "tt", CODES, DAY)
    assert [(r.status, r.source, r.first_date) for r in records] == [("confirmed", "official", DAY)]
    assert state.candidates[0].status == "confirmed" and state.epochs()[SCOPE] == DAY

def test_open_measured_suspect_is_closed_by_the_official_confirmation(tmp_path): ...
    # 같은 단위에 status="suspect" 기록을 미리 넣는다 → cleared, note "공식 시간표로 확정함"

def test_hold_is_retried_and_expires_after_five_weekdays(tmp_path):
    state, store = scene(tmp_path)
    for day in DAYS[:5]:
        store.put(day_points(day, shift=0)); cover(store, [day])   # 관측은 계속 옛 시각
        _, lines = judge_candidates(state, store, tmp_path / "tt", CODES, day)
    assert state.candidates[0].status == "expired" and len(state.candidates[0].tries) == 5
    assert "후보 만료" in lines[-1] and "5번 보고도 정하지 못함" in lines[-1]

def test_day_without_coverage_is_not_counted_as_a_try(tmp_path): ...        # cover() 없이 → tries == []
def test_same_day_is_not_judged_twice(tmp_path): ...                         # 두 번 불러도 tries 길이 1
def test_other_day_type_is_left_alone(tmp_path): ...                         # 토요일 후보는 평일에 건드리지 않음

def test_missing_old_cache_folder_expires_the_candidate_without_raising(tmp_path):   # Review Focus 3
    state, store = scene(tmp_path)
    shutil.rmtree(tmp_path / "tt-20261118")
    store.put(day_points(DAY, shift=180)); cover(store, [DAY])
    records, lines = judge_candidates(state, store, tmp_path / "tt", CODES, DAY)
    assert records == [] and state.candidates[0].status == "expired"
    assert "옛 시간표 없음" in lines[0]

def test_delay_falls_back_to_the_stored_value_when_nothing_is_unchanged(tmp_path): ...
    # 전체가 바뀐 표 + state.delays={"9호선|0": 40} + 관측 = 새 표 + 40 → confirmed
```

`test_ml_nightly.py`:

```python
def test_official_candidate_confirms_in_one_night_and_is_logged(tmp_path, paths): ...
    # 차이 목록 파일 + 옛·새 캐시를 두고 night() → changes.md에 "후보"와 "확정 … official" 줄,
    # 관찰 모드이므로 결과물 cells는 그대로

def test_catch_up_ingests_every_missed_diff_entry_in_date_order(tmp_path, paths):   # Review Focus 2
    # 차이 항목 두 날짜(다른 단위) + 밤 작업 한 번(따라잡기 3일) → 후보 2개, 각각 맞는 날에 판정됨

def test_without_timetable_cache_candidates_are_ingested_but_not_judged(tmp_path, paths): ...
def test_judging_failure_is_reported_and_measured_detection_still_runs(tmp_path, paths, monkeypatch): ...
    # judge_candidates가 예외 → 종료 코드 1, state.checked는 그 날까지 나아감
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 전체 테스트·린트 통과**
- [ ] **Step 5: 커밋** — `feat(ml): 밤 작업이 공식 시간표 후보를 들이고 판정`

---

### Task 6: 확정 단위 채우기(`--enforce`)

**Files:**
- Modify: `server/ml/b1.py`(`Entry`), `server/ml/official.py`, `server/ml/nightly.py`
- Test: `server/tests/unit/test_ml_candidates.py`, `server/tests/unit/test_ml_nightly.py`

**Interfaces:**
- Consumes: 기존 `build_b1_from_points`가 내는 `part["scopes"][scope_key] == {"state": "retraining", …}`, `B0.departures`, `ChangeState.delays`.
- Produces:
  - `ml.b1.Entry`에 필드 `official: bool = False`(맨 끝).
  - `ml.official.official_fill(part: dict, state: ChangeState, b0: B0, statn_ids: Iterable[str], *, line: str) -> int` — 채운 항목 수.

**규칙**
- `part["scopes"]`에서 상태가 `retraining`이고, 그 단위의 가장 늦은 확정 기록이 `source == "official"`인 단위만 채운다.
- 그 호선의 역(`statn_ids`)마다 `b0.departures(day_type, line, direction, statn_id)` 가운데 시각이 그 시간대에 드는 것을 항목으로 넣는다: `secs = 공식 시각 + state.delays.get(f"{line}|{direction}", 0)`, `train_no=None`, `n_days=0`, `dest_name=None`, `express=0`, `first_seen=None`, `last_seen=None`, `official=True`. 칸은 시각순으로 다시 정렬한다.
- 단위 상태는 `retraining` 그대로 둔다.
- `nightly._build_line`은 `enforce`이고 `b0`가 있을 때만 부른다. 변경 감지의 기준표(`detect_day` 안의 `build_b1_from_points`)에는 넣지 않는다.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_retraining_scope_confirmed_by_official_is_filled_with_official_times_plus_delay(tmp_path):
    part, state, b0 = retraining_part(tmp_path, source="official", delay=40)
    n = official_fill(part, state, b0, ["s-A", "s-B", "s-C"], line="9호선")
    cell = part["cells"]["1|9호선|0|s-A"]
    assert n == sum(len(c) for c in part["cells"].values()) > 0
    assert all(e["official"] and e["train_no"] is None and e["n_days"] == 0 for e in cell)
    assert cell[0]["secs"] == H6 + 30 + 180 + 40
    assert part["scopes"][scope_key(SCOPE)]["state"] == "retraining"

def test_scope_confirmed_by_measurement_only_is_not_filled(tmp_path):
    part, state, b0 = retraining_part(tmp_path, source="measured", delay=40)
    assert official_fill(part, state, b0, ["s-A"], line="9호선") == 0 and part["cells"] == {}

def test_entry_default_is_not_official():
    assert Entry(secs=1, train_no=None, n_days=1, dest_name=None, express=0).official is False
```

`test_ml_nightly.py`:

```python
def test_enforce_fills_officially_confirmed_scope_until_two_days_are_observed(tmp_path, paths): ...
    # 확정 다음 날 밤(--enforce): 그 단위 칸에 official=True 항목. 관측 이틀 뒤 밤: official 항목 없음, 실측 항목.
def test_observe_mode_never_fills(tmp_path, paths): ...
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `Entry`에 필드가 늘면 결과물의 모든 항목에 `"official": false`가 붙는다. 칸을 통째로 견주는 기존 테스트의 기대값에 그 키를 더한다.
- [ ] **Step 4: 전체 테스트·린트 통과**
- [ ] **Step 5: 커밋** — `feat(ml): 공식 근거로 확정된 단위를 새 공식 시각으로 채우기`

---

### Task 7: VM에서 매일 받기

**Files:**
- Create: `server/ml/deploy/subway-ml-official.service`, `server/ml/deploy/subway-ml-official.timer`
- Modify: `server/ml/deploy/setup_vm.sh`
- Test: `server/tests/unit/test_ml_deploy.py`

**정한 값**
- 타이머: `OnCalendar=*-*-* 01:30:00`, `Persistent=true`.
- 서비스: `Type=oneshot`, `User=@USER@`, `WorkingDirectory=@SERVER_DIR@`, `Nice=10`, `MemoryMax=300M`, `ExecStart=@SERVER_DIR@/.venv/bin/python -m ml.b0 --refresh`. 키 이름·값은 유닛에 적지 않는다(`ml.b0`가 `.env`에서 읽는다).
- `setup_vm.sh`: 유닛 목록에 두 파일을 더하고 타이머를 `enable --now`. 끝의 안내에 `공식: journalctl -u subway-ml-official -n 20` 한 줄.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_official_timer_runs_after_collection_ends_and_before_the_backup():
    timer = _read("subway-ml-official.timer")
    assert "OnCalendar=*-*-* 01:30:00" in timer and "Persistent=true" in timer

def test_official_service_refreshes_with_limits_and_names_no_key():
    service = _read("subway-ml-official.service")
    assert "-m ml.b0 --refresh" in service and "Type=oneshot" in service
    assert "MemoryMax=300M" in service and "KEY" not in service

def test_setup_script_installs_the_official_units():
    script = _read("setup_vm.sh")
    assert "subway-ml-official.service" in script and "subway-ml-official.timer" in script
    assert "enable --quiet --now subway-ml-official.timer" in script
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — 기존 `subway-ml-nightly.*`를 본뜬다.
- [ ] **Step 4: 전체 테스트 통과**(`test_setup_script_is_valid_bash` 포함)
- [ ] **Step 5: 커밋** — `feat(ml): 공식 시간표를 매일 01:30에 받는 타이머`

---

### Task 8: 운영 문서

**Files:**
- Modify: `docs/ml/timetable-changes.md`, `docs/ml/2026-11-13-train-eta-model-plan.md`, `docs/ml/collector.md`, `docs/superpowers/plans/2026-11-16-timetable-change-handling.md`

- [ ] **Step 1: `timetable-changes.md`** — §1 흐름에 01:30 받기와 후보 판정을 넣는다. §3에 "공식 시간표 후보" 절(판정 표, 보류·만료, 기록 줄 읽는 법)을 더한다. §6을 "매일, 1~9호선, 세 요일 구분, 빈 역 방어, 차이 목록"으로 고쳐 쓴다. §8에 "채우기는 `--enforce`일 때만"과 새 숫자가 초안임을 적는다. 맨 위 "지금 상태"는 **VM에 아직 반영하지 않았다**로 둔다.
- [ ] **Step 2: 사실 바로잡기** — `2026-11-13-train-eta-model-plan.md`의 "시간표 API는 2~9호선만"을 "1~9호선(2026-11-17 확인)"으로, 시간표 없는 노선 목록에서 1호선을 뺀다. 1호선 시간표의 정확도는 아직 견주지 않았다고 적는다.
- [ ] **Step 3: `collector.md`** — VM `.env`에 `SEOUL_OPENAPI_KEY`가 필요해졌다는 것과 새 타이머를 적는다.
- [ ] **Step 4: 앞선 계획 문서** — "다음 개정 제안" 절 끝에 이 계획과 스펙으로 가는 한 줄.
- [ ] **Step 5: 커밋** — `docs(ml): 공식 시간표 후보 판정 운영 안내`

---

## 올린 뒤 할 일(사용자 승인 뒤, 이 계획의 Task가 아니다)

1. `develop` 푸시.
2. VM: `git pull` 뒤 `python -m ml.nightly --reextract`를 한 번 돌려 이미 뽑은 날에서 지나가는 급행을 뺀다. 그리고 `.env`에 `SEOUL_OPENAPI_KEY` 더하기(값을 출력하지 않고 옮긴다), `setup_vm.sh` 다시 실행, `python -m ml.b0 --refresh`를 한 번 손으로 돌려 캐시를 만든다.
3. **1호선 확인**(스펙 5.8): VM 자료로 `python -m ml.analyze --timetable-cache ml_data/timetable_cache`를 돌려 1호선의 공식 시간표와 관측의 차이를 `docs/ml/`에 적는다. 전혀 맞지 않으면 `TIMETABLE_LINES`에서 1호선을 다시 뺀다.
