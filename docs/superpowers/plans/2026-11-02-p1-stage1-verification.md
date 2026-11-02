# P1. 1단계 확인과 저장소 기반 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 구현을 시작하기 전에 외부 API·지도 SDK·배포 경로를 실제로 확인하고, 그 결과(실제 응답 샘플, 역 원본 데이터, 필드 대응표)와 서버·앱의 뼈대·CI를 저장소에 남긴다.

**Architecture:** 서버는 `/health`만 있는 FastAPI 앱을 Docker로 Render에 올리고, 앱은 지도에 선 하나를 그리는 Compose 프로젝트로 시작한다. 두 뼈대는 P2·P3에서 그대로 자란다. 샘플 수집 스크립트는 키를 남기지 않고 `server/tests/fixtures/`에 실제 응답을 저장한다.

**Tech Stack:** Python 3.12, FastAPI, uvicorn, pydantic-settings, pytest, ruff, Docker, Render, GitHub Actions / Kotlin, Jetpack Compose, 카카오 지도 Android SDK v2, Gradle Kotlin DSL

**Spec:** `docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md` (14절 중심, 4.6, 5.1, 5.4, 7.5, 9.4, 10.1)

**기간:** 2026-11-02 ~ 11-03 · **선행:** 없음 · **이 계획이 끝나야** P2(서버)와 P3(앱)를 시작한다.

**표기:** "(직접)"이 붙은 단계는 계정·키·실기기가 필요해 사람이 한다. 나머지는 Claude Code가 해도 된다.

## Global Constraints

- 서버 키(`SEOUL_REALTIME_API_KEY`, `KAKAO_REST_API_KEY`)는 `server/.env`(커밋 금지)와 Render 환경 변수에만 둔다. 저장소에는 키가 없는 `server/.env.example`만 올린다. (5.4)
- 앱의 서버 주소와 카카오 네이티브 앱 키는 `android/local.properties`(커밋 금지)에서 읽어 `BuildConfig`로 넣는다. (7.5)
- 패키지명 `io.github.klanary.jigeumchulbal`, minSdk 26, compileSdk·targetSdk는 작업 시점의 최신 안정 API 레벨(35 이상). (5.3)
- 서버 명령은 `server/`에서, 앱 명령은 `android/`에서, git 명령은 저장소 루트에서 실행한다.
- CI: `server.yml`은 `server/**` 변경 PR·푸시에서 Python 3.12 → 의존성 → ruff → pytest. `android.yml`은 `android/**` 변경 PR·푸시에서 JDK 17 → `./gradlew testDebugUnitTest assembleDebug`(지도 키는 더미 값). (9.4)
- 확인 결과는 `docs/analysis/api-verification.md`에 적고, 스펙과 다르면 스펙을 먼저 고친다. (14)
- 공개 저장소이므로 샘플·문서에 본인 집·학교 좌표를 쓰지 않는다. 공공 장소 좌표만 쓴다.
- 코드를 바꾸는 작업의 커밋에는 학습 노트 초안 `docs/learning/NN-주제.md`(12절 형식, NN은 기존 가장 큰 번호 + 1, 두 자리)를 함께 넣는다.

## Review Focus

1. 키가 커밋되거나 샘플 파일에 섞여 들어감 → 저장소 어디에도 키 문자열이 없어야 한다. (Task 1 Step 2, Task 4 `test_redact_replaces_every_secret`와 Step 8)
2. 샘플 좌표로 본인 집·학교를 쓰면 공개 저장소에 생활 반경이 남음 → 샘플은 스크립트에 고정된 공공 장소 좌표만 쓴다. (Task 4 `test_capture_plan_uses_only_public_landmarks`)
3. 역사마스터 CSV가 CP949로 저장돼 UTF-8로 읽으면 깨짐 → 인코딩을 기록하고, P2 빌드 스크립트가 두 인코딩을 모두 읽는다. (Task 5 Step 2)
4. Dockerfile의 exec 형식 CMD는 `$PORT`를 풀지 않아 Render에서 포트 바인딩 실패 → `PORT=9000`으로 실행해도 그 포트에서 `/health`가 응답해야 한다. (Task 2 Step 7)
5. CI에는 `local.properties`가 없음 → 키 없이도 더미 값으로 빌드·테스트가 통과해야 한다. (Task 6 `BuildConfigTest`와 Step 8)

---

### Task 1: 저장소 골격과 문서 틀

**Files:**
- Create: `.gitignore`, `.github/ISSUE_TEMPLATE/bug.md`, `docs/analysis/api-verification.md`, `docs/analysis/original-app.md`, `docs/measurements/ux-comparison.md`, `docs/learning/README.md`
- Modify: `README.md`

**Interfaces:**
- Produces: `api-verification.md`의 절 번호 §1~§6(스펙 14절 항목 번호와 같음). 이후 작업은 "§3 경로 필드 대응표"처럼 이 번호로 가리킨다.

- [ ] **Step 1: `.gitignore` 작성**

무시 대상: `server/.env`, `.venv/`, `__pycache__/`, `.pytest_cache/`, `.ruff_cache/`, `android/local.properties`, `android/.gradle/`, `android/**/build/`, `android/.idea/`, `*.iml`, `*.jks`, `*.keystore`, `*.apk`, `.DS_Store`

- [ ] **Step 2: 무시 규칙 확인**

Run: `git check-ignore server/.env android/local.properties release.jks app-release.apk; git check-ignore server/.env.example || echo "env.example tracked"`
Expected: 네 경로가 모두 출력되고 마지막 줄이 `env.example tracked`

- [ ] **Step 3: 버그 템플릿 작성**

`.github/ISSUE_TEMPLATE/bug.md`: front matter `name: 버그`, `about: 발견한 버그 기록`, `labels: bug`. 본문 절은 12절 그대로 `재현 방법`, `기대 결과`, `실제 결과`, `원인`, `해결`.

- [ ] **Step 4: 문서 틀 작성**

- `docs/analysis/api-verification.md`
  - §1 서울시 실시간 도착 API: 필드 확인표(스펙 이름 | 실제 이름 | 값 예시 | 비고; 행: `barvlDt`, `recptnDt`, `arvlMsg2`, `trainLineNm`, `updnLine`, `btrainSttus`, 막차 표시, `subwayId`, `arvlCd`), 호선 코드표(`subwayId` → 호선), 응답 코드표(정상 / 데이터 없음 / 일일 한도 초과 / 그 밖), 요청 URL 형식
  - §2 역사마스터: 파일 이름, 내려받은 날짜, 인코딩, 열 이름, 행 수, 좌표가 빈 행 수, 호선 표기 목록, 실시간 API 이름 예외 표(역 id | api_name | 근거), 같은 이름·1km 넘게 떨어진 역 표(이름 | 호선 | 좌표 | 나눌 id), 이용허락 문구
  - §3 카카오: 앱 이름, 무료 쿼터 적용 여부, API별 일일 쿼터 표, 경로 API 전체 URL·파라미터, **경로 필드 대응표**(서버 필드 | 카카오 응답 경로 | 변환 규칙; 행: 경로 목록, `totalSec`, `distanceM`, `fare`, `transfers`, 단계 목록, `step.mode` 판별, `durationSec`, 단계 `distanceM`, `guidance`, `path` 좌표 형식, `lineName`, `routeName`, `stops`), 오류 응답 형식과 코드 표(카카오 코드 | HTTP 상태 | 서버 오류), 지하철 호선 이름 표기 목록
  - §4 지도 SDK 실험: SDK 버전, 키 해시 등록 여부, 결과 스크린샷 경로
  - §5 Render: 서비스 URL, 잠든 뒤 첫 응답 시간 3회, 깨어난 뒤 응답 시간
  - §6 GitHub: 저장소 `klanary/subway_easy`, 패키지명 `io.github.klanary.jigeumchulbal`
- `docs/measurements/ux-comparison.md`: 측정 규칙(앱을 완전히 종료한 상태에서 시작, 화면 녹화, 탭 수는 녹화를 보고 셈, 시간은 첫 탭부터 목표 화면이 보일 때까지)과 표 두 벌(원본 / 지금출발; 열: 과제 | 앱 | 1·2·3회 탭 수와 시간 | 중앙값)
- `docs/analysis/original-app.md`: 앱별(카카오맵 길찾기, 카카오지하철) 화면 흐름과 불편 사항 표(불편 | 장면 | 지금출발의 개선 | 관련 기능 번호)
- `docs/learning/README.md`: 12절의 노트 형식 블록과 번호 규칙

- [ ] **Step 5: README 교체**

앱 이름과 한 줄 소개, 저장소 구조(스펙 5.1), 스펙·계획 문서 링크. CI 배지는 Task 2·6에서 추가한다.

- [ ] **Step 6: Commit**

```bash
git add .gitignore .github/ISSUE_TEMPLATE/bug.md docs/analysis docs/measurements docs/learning README.md
git commit -m "chore: 저장소 골격과 확인·측정 문서 틀"
```

---

### Task 2: 최소 서버, Docker, 서버 CI

**Files:**
- Create: `server/pyproject.toml`, `server/app/__init__.py`, `server/app/main.py`, `server/app/api/__init__.py`, `server/app/api/health.py`, `server/app/core/__init__.py`, `server/app/core/config.py`, `server/tests/__init__.py`, `server/tests/conftest.py`, `server/tests/unit/__init__.py`, `server/tests/unit/test_config.py`, `server/tests/integration/__init__.py`, `server/tests/integration/test_health.py`, `server/data/.gitkeep`, `server/.env.example`, `server/Dockerfile`, `server/.dockerignore`, `.github/workflows/server.yml`
- Modify: `README.md` (서버 CI 배지)

**Interfaces:**
- Produces: `class Settings(BaseSettings)` (`app/core/config.py`) — 필드와 환경 변수 이름(대소문자 무시)·기본값:

| 필드 | 기본값 | 필드 | 기본값 |
|---|---|---|---|
| `seoul_realtime_api_key` | `""` | `rate_limit_per_minute` | `60` |
| `kakao_rest_api_key` | `""` | `mock_upstream` | `False` |
| `seoul_realtime_daily_limit` | `1000` | `stations_db_path` | `"data/stations.sqlite"` |
| `kakao_route_daily_limit` | `1000` | `fixtures_dir` | `"tests/fixtures"` |
| `kakao_keyword_daily_limit` | `100000` | `upstream_timeout_sec` | `5.0` |
| `kakao_coord2address_daily_limit` | `10000` | `upstream_retry_delay_sec` | `0.5` |

  `model_config = SettingsConfigDict(env_file=".env", extra="ignore")`
- Produces: `get_settings() -> Settings` (`functools.lru_cache`), `create_app(settings: Settings | None = None) -> FastAPI` (`app/main.py`), 모듈 수준 `app = create_app()` (uvicorn 진입점)
- Produces: 테스트 픽스처 `test_settings`, `client` (`tests/conftest.py`) — P2의 모든 테스트가 쓴다.

- [ ] **Step 1: 프로젝트 설정 작성**

`server/pyproject.toml`: 프로젝트 이름 `jigeumchulbal-server`, `requires-python = ">=3.12"`, 의존성 `fastapi>=0.115`, `uvicorn[standard]>=0.30`, `httpx>=0.27`, `pydantic>=2.7`, `pydantic-settings>=2.3`, 선택 의존성 `dev = ["pytest>=8", "pytest-asyncio>=0.24", "respx>=0.21", "ruff>=0.6"]`. 빌드는 setuptools(`packages.find include = ["app*"]`). pytest `asyncio_mode = "auto"`, `testpaths = ["tests"]`. ruff `line-length = 100`, `target-version = "py312"`, lint `select = ["E", "F", "I", "B", "UP"]`.

Run: `cd server && python3.12 -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"`
Expected: 설치 성공

- [ ] **Step 2: 실패하는 테스트 작성**

```python
# tests/conftest.py
import httpx
import pytest
from app.core.config import Settings
from app.main import create_app

@pytest.fixture
def test_settings() -> Settings:
    return Settings(_env_file=None, seoul_realtime_api_key="TESTSEOULKEY",
                    kakao_rest_api_key="TESTKAKAOKEY", upstream_retry_delay_sec=0)

@pytest.fixture
async def client(test_settings):
    app = create_app(test_settings)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                 base_url="http://test") as c:
        yield c

# tests/integration/test_health.py
async def test_health_returns_ok(client):
    r = await client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}

# tests/unit/test_config.py
def test_defaults_match_spec():
    s = Settings(_env_file=None)
    assert (s.seoul_realtime_daily_limit, s.kakao_route_daily_limit,
            s.kakao_keyword_daily_limit, s.kakao_coord2address_daily_limit,
            s.rate_limit_per_minute, s.mock_upstream) == (1000, 1000, 100000, 10000, 60, False)

def test_env_overrides(monkeypatch):
    monkeypatch.setenv("SEOUL_REALTIME_DAILY_LIMIT", "5")
    monkeypatch.setenv("MOCK_UPSTREAM", "true")
    s = Settings(_env_file=None)
    assert s.seoul_realtime_daily_limit == 5 and s.mock_upstream is True
```

- [ ] **Step 3: 실패 확인**

Run: `pytest -q`
Expected: FAIL (`ModuleNotFoundError: No module named 'app.core'` 등)

- [ ] **Step 4: 구현**

`Settings`, `get_settings`, `api/health.py`(`APIRouter`, `GET /health` → `{"status": "ok"}`), `create_app`(FastAPI `title="지금출발 서버"`, health 라우터 등록). `server/.env.example`에는 위 표의 환경 변수 이름을 모두 적고 키 값은 비운다.

- [ ] **Step 5: 통과 확인**

Run: `pytest -q && ruff check .`
Expected: `3 passed`, ruff 오류 없음

- [ ] **Step 6: Dockerfile 작성**

```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .
COPY data ./data
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
```

`.dockerignore`: `.env`, `.venv`, `tests`, `data/raw`, `__pycache__`, `.pytest_cache`, `.ruff_cache`

- [ ] **Step 7: 컨테이너가 `$PORT`를 따르는지 확인**

Run: `docker build -t jc-server . && docker run -d --rm --name jc -e PORT=9000 -p 9000:9000 jc-server && sleep 3 && curl -s localhost:9000/health; docker stop jc`
Expected: `{"status":"ok"}`

- [ ] **Step 8: 서버 CI 작성**

```yaml
# .github/workflows/server.yml
name: server
on:
  push:
    paths: ["server/**", ".github/workflows/server.yml"]
  pull_request:
    paths: ["server/**", ".github/workflows/server.yml"]
jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: server
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
          cache: pip
          cache-dependency-path: server/pyproject.toml
      - run: pip install -e ".[dev]"
      - run: ruff check .
      - run: pytest -q
```

README 상단에 배지 `![server](https://github.com/klanary/subway_easy/actions/workflows/server.yml/badge.svg)`를 추가한다.

- [ ] **Step 9: Commit**

```bash
git add server .github/workflows/server.yml README.md docs/learning
git commit -m "feat(server): /health 최소 서버, Docker, 서버 CI"
```

---

### Task 3: Render 배포 실험 (직접)

**Files:**
- Modify: `docs/analysis/api-verification.md` (§5)

**Interfaces:**
- Consumes: Task 2의 `server/Dockerfile`이 `main`에 머지된 상태
- Produces: 공개 서비스 URL `https://<서비스 이름>.onrender.com` (P2 Task 16, P3 `server.baseUrl`이 쓴다)

- [ ] **Step 1: (직접) Render 웹 서비스 생성**

New → Web Service → GitHub `klanary/subway_easy` → Root Directory `server`, Runtime Docker, Instance Type Free, Health Check Path `/health`, 브랜치 `main` 자동 배포.

- [ ] **Step 2: 배포 확인**

Run: `curl -s https://<서비스 이름>.onrender.com/health`
Expected: `{"status":"ok"}`

- [ ] **Step 3: (직접) 잠듦·깨어남 측정**

15분 넘게 요청하지 않은 뒤 `curl -o /dev/null -s -w "%{time_total}\n" https://<서비스 이름>.onrender.com/health`를 실행하고 곧바로 한 번 더 실행한다. 회차 사이에 16분 넘게 쉬며 3회 반복해 §5에 적는다.

- [ ] **Step 4: Commit**

```bash
git add docs/analysis/api-verification.md
git commit -m "docs: Render 배포 실험 결과"
```

---

### Task 4: 외부 API 샘플 수집 스크립트와 실행

**Files:**
- Create: `server/app/clients/__init__.py`, `server/app/clients/endpoints.py`, `server/scripts/__init__.py`, `server/scripts/capture_fixtures.py`, `server/tests/unit/test_capture_fixtures.py`, `server/tests/fixtures/*.json`(실행 결과)
- Modify: `docs/analysis/api-verification.md` (§1, §3)

**Interfaces:**
- Produces: `app/clients/endpoints.py` 상수 — P2 클라이언트가 그대로 가져다 쓴다.
  - `SEOUL_REALTIME_BASE = "http://swopenapi.seoul.go.kr/api/subway"` (요청 형식: `{BASE}/{키}/json/realtimeStationArrival/0/50/{역 이름}`)
  - `KAKAO_LOCAL_BASE = "https://dapi.kakao.com/v2/local"`
  - `KAKAO_ROUTE_URL` = 부록 A 첫 번째 문서에서 확인한 대중교통 경로 조회의 전체 URL(경로 `/v2/routing/publictraffic`)
- Produces: 샘플 파일 형식 `{"status": <HTTP 상태 코드>, "body": <응답 JSON>}` — P2 테스트의 `load_fixture(name) -> tuple[int, dict]`가 이 형식을 읽는다.
- Produces: 샘플 파일 이름(P2 테스트가 이 이름으로 읽는다)

| 파일 | 요청 |
|---|---|
| `seoul_realtime_hongdae.json` | 역 이름 "홍대입구" (2호선·경의중앙선·공항철도 환승역) |
| `seoul_realtime_cityhall.json` | "시청" (1·2호선) |
| `seoul_realtime_exception.json` | 이름 예외 역 1곳 (§2 표에서 고름, 예: "응암순환(상선)") |
| `seoul_realtime_nodata.json` | 없는 역 이름 "없는역이름" (데이터 없음 응답) |
| `kakao_keyword_hongik.json` | query "홍익대학교", 홍대입구역 좌표 기준 거리순 |
| `kakao_keyword_empty.json` | 결과가 없는 query "ㅁㄴㅇㄹㅁㄴㅇㄹ" |
| `kakao_coord2address_hongdae.json` | 홍대입구역 좌표 |
| `kakao_coord2address_sea.json` | 서해 바다 (37.0, 125.0) |
| `kakao_route_subway_only.json` | 홍대입구역 → 강남역 |
| `kakao_route_transfer.json` | 홍대입구역 → 경복궁역 |
| `kakao_route_bus_subway.json` | 서울대학교 정문 → 홍대입구역 |
| `kakao_route_error_equal_points.json` | 홍대입구역 → 홍대입구역 |
| `kakao_route_error_far.json` | 홍대입구역 → 제주공항 |

- Produces (`scripts/capture_fixtures.py`):
  - `LANDMARKS: dict[str, tuple[float, float]]` = 홍대입구역 (37.5571, 126.9245), 강남역 (37.4979, 127.0276), 경복궁역 (37.5759, 126.9735), 시청역 (37.5657, 126.9769), 서울대학교 정문 (37.4664, 126.9480), 서해 바다 (37.0, 125.0), 제주공항 (33.5070, 126.4929)
  - `@dataclass(frozen=True) class CaptureItem: api: Literal["seoul_realtime", "kakao_keyword", "kakao_coord2address", "kakao_route"]; slug: str; params: dict` 와 메서드 `points() -> list[tuple[float, float]]`(그 항목이 쓰는 모든 좌표)
  - `CAPTURE_PLAN: list[CaptureItem]` (위 표 13개), `fixture_path(item, fixtures_dir: Path) -> Path` (`f"{item.api}_{item.slug}.json"`)
  - `redact(text: str, secrets: list[str]) -> str` — 비어 있지 않은 각 비밀 문자열을 `***`로 바꾼다.
  - CLI `python -m scripts.capture_fixtures [--only <api>]`: `Settings`로 키를 읽고, 항목마다 호출해 오류 응답도 그대로 저장한다(`json.dumps(..., ensure_ascii=False, indent=2)` → `redact` → 저장). 카카오 요청 헤더는 `Authorization: KakaoAK {키}`, 카카오 좌표 파라미터는 `x=경도, y=위도`.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/unit/test_capture_fixtures.py
from pathlib import Path
from scripts.capture_fixtures import CAPTURE_PLAN, LANDMARKS, fixture_path, redact

def test_redact_replaces_every_secret():
    assert redact('{"k": "AB12"} /api/subway/AB12/json', ["AB12", ""]) == \
        '{"k": "***"} /api/subway/***/json'

def test_fixture_names_are_unique_and_ascii():
    names = [fixture_path(i, Path("f")).name for i in CAPTURE_PLAN]
    assert len(names) == len(set(names)) == 13
    assert all(n.isascii() for n in names)

def test_capture_plan_uses_only_public_landmarks():
    allowed = set(LANDMARKS.values())
    assert all(p in allowed for item in CAPTURE_PLAN for p in item.points())
```

- [ ] **Step 2: 실패 확인**

Run: `pytest tests/unit/test_capture_fixtures.py -q`
Expected: FAIL (`ModuleNotFoundError: No module named 'scripts.capture_fixtures'`)

- [ ] **Step 3: 구현**

`endpoints.py`의 `KAKAO_ROUTE_URL`은 문서를 확인하기 전까지 빈 문자열로 두고, 스크립트는 그 값이 비어 있으면 카카오 경로 항목을 건너뛰며 "KAKAO_ROUTE_URL을 먼저 채우세요"를 출력한다.

- [ ] **Step 4: 통과 확인**

Run: `pytest -q && ruff check .`
Expected: 모두 통과

- [ ] **Step 5: (직접) 키 발급**

서울 열린데이터광장 "실시간 지하철" 인증키, 카카오 개발자 콘솔에서 이 프로젝트용 앱 생성 → 카카오맵 API 활성화 → REST API 키. `server/.env`에 `SEOUL_REALTIME_API_KEY`, `KAKAO_REST_API_KEY`를 적는다.

- [ ] **Step 6: (직접) 카카오 경로 URL 확인**

부록 A 첫 번째 문서에서 대중교통 경로 조회의 호스트·메서드·파라미터·오류 형식을 확인해 `KAKAO_ROUTE_URL`을 채우고 §3에 적는다.

- [ ] **Step 7: 샘플 수집 실행**

Run: `python -m scripts.capture_fixtures`
Expected: 13개 파일 저장 로그. 실패한 항목은 사유를 확인해 다시 실행한다.

- [ ] **Step 8: 키가 남지 않았는지 확인**

Run (저장소 루트): `set -a; . server/.env; set +a; for k in "$SEOUL_REALTIME_API_KEY" "$KAKAO_REST_API_KEY"; do [ -n "$k" ] && { git grep --untracked -n -F "$k" || echo clean; }; done`
Expected: `clean` 두 줄 (`.env`는 무시 대상이라 검색되지 않는다. 키가 비어 있으면 아무것도 출력되지 않으니 Step 5부터 다시 본다)

- [ ] **Step 9: 확인 결과 기록**

샘플을 보며 §1(필드 확인표, `subwayId` 코드표, 응답 코드표 — "데이터 없음" 코드는 nodata 샘플에서, 일일 한도 초과 코드는 데이터셋 안내 문서에서)과 §3(경로 필드 대응표, 오류 형식·코드 표, 지하철 호선 이름 표기 목록)을 채운다. 대응표의 "카카오 응답 경로"는 `routes[0].…`처럼 샘플에서 그대로 따라갈 수 있게 적는다.

- [ ] **Step 10: Commit**

```bash
git add server/app/clients server/scripts server/tests/unit/test_capture_fixtures.py server/tests/fixtures docs/analysis/api-verification.md docs/learning
git commit -m "feat(server): 외부 API 샘플 수집 스크립트와 실제 응답 샘플"
```

---

### Task 5: 역사마스터 원본 확보와 예외 목록 (직접)

**Files:**
- Create: `server/data/raw/station_master.csv`, `server/data/raw/README.md`
- Modify: `docs/analysis/api-verification.md` (§2)

**Interfaces:**
- Produces: `server/data/raw/station_master.csv`(내려받은 원본 그대로, 파일 이름 고정) — P2 Task 3이 읽는다.
- Produces: §2의 열 이름·인코딩·호선 표기 목록·실시간 API 이름 예외 표·같은 이름 먼 거리 역 표 — P2 Task 2·3의 표를 채운다.

- [ ] **Step 1: (직접) 내려받기**

서울 열린데이터광장 "서울시 역사마스터 정보"(부록 A) CSV를 내려받아 `server/data/raw/station_master.csv`로 저장한다. 변환하지 않는다.

- [ ] **Step 2: 형식 확인**

인코딩(`utf-8-sig`로 읽어 실패하면 `cp949`), 열 이름, 행 수, 위도·경도가 빈 행 수, 호선 열의 서로 다른 값 목록을 출력하는 한 줄짜리 Python을 실행해 §2에 적는다. (예상 열: `역사_ID, 역사명, 호선, 위도, 경도`)

- [ ] **Step 3: 같은 이름·먼 거리 역 찾기**

역 이름을 스펙 6.5의 정규화 규칙(1 → 3 → 2)으로 묶었을 때 호선 좌표 사이 거리가 1km를 넘는 이름을 §2 표에 적고, 나눌 역의 id를 정한다. (예상: 경의중앙선 양평 → `양평(경의중앙선)`)

- [ ] **Step 4: (직접) 실시간 API 이름 예외 수집**

실시간 도착정보 데이터셋(OA-12764) 안내 문서와 첨부 역명 목록에서 역사마스터 이름과 조회 이름이 다른 역을 §2 예외 표에 적는다.

- [ ] **Step 5: 출처 문구 기록**

두 데이터셋 페이지의 이용허락 조건 문구를 `server/data/raw/README.md`(출처 URL, 내려받은 날짜 포함)와 §2에 적는다. 10.4절의 출처 표시에 그대로 쓴다.

- [ ] **Step 6: Commit**

```bash
git add server/data/raw docs/analysis/api-verification.md
git commit -m "docs: 역사마스터 원본과 이름 예외 목록"
```

---

### Task 6: 안드로이드 뼈대와 카카오 지도 실험

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`, `android/local.properties.example`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/debug/AndroidManifest.xml`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/java/io/github/klanary/jigeumchulbal/App.kt`, `.../MainActivity.kt`, `.../map/MapExperiment.kt`, `android/app/src/test/java/io/github/klanary/jigeumchulbal/BuildConfigTest.kt`, `.github/workflows/android.yml`
- Modify: `README.md` (앱 CI 배지), `docs/analysis/api-verification.md` (§4)

**Interfaces:**
- Produces: `BuildConfig.SERVER_BASE_URL: String` (`local.properties`의 `server.baseUrl`, 없으면 `"http://10.0.2.2:8000/"`), `BuildConfig.KAKAO_NATIVE_APP_KEY: String` (`kakao.nativeAppKey`, 없으면 `"DUMMY_KAKAO_KEY"`)
- Produces: `class App : Application` — `onCreate`에서 `KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)`. `AndroidManifest`의 `android:name=".App"`, 권한 `INTERNET`.
- Produces: `gradle/libs.versions.toml` — P3·P4가 쓸 라이브러리 버전을 이 계획에서 한 곳에 정한다: compose-bom, material3, activity-compose, navigation-compose(2.8 이상, 타입 안전 경로), lifecycle-viewmodel-compose, lifecycle-runtime-compose, kotlinx-serialization-json, retrofit(2.11 이상)·converter-kotlinx-serialization, okhttp, room(runtime·ktx·compiler, KSP), datastore-preferences, play-services-location, kotlinx-coroutines-play-services, 카카오 지도(`com.kakao.maps.open:android`, 최신 2.x), junit, kotlinx-coroutines-test, robolectric, androidx-test-core. 이 작업의 앱 모듈은 Compose·카카오 지도·테스트만 적용한다.
- 저장소: `settings.gradle.kts`의 `dependencyResolutionManagement`에 `google()`, `mavenCentral()`, `maven("https://devrepo.kakao.com/nexus/repository/kakaomap-releases/")`

- [ ] **Step 1: Gradle 프로젝트 생성**

플러그인: Android application(AGP 8.7 이상), Kotlin Android(2.1 이상), Kotlin Compose, Kotlin Serialization, KSP. `namespace`·`applicationId` = `io.github.klanary.jigeumchulbal`, minSdk 26, `buildFeatures { compose = true; buildConfig = true }`, JVM 대상 17. 래퍼: `gradle wrapper --gradle-version <최신 8.x>`.

- [ ] **Step 2: 실패하는 테스트 작성**

```kotlin
// app/src/test/java/io/github/klanary/jigeumchulbal/BuildConfigTest.kt
class BuildConfigTest {
    @Test fun serverBaseUrlEndsWithSlash() =
        assertTrue(BuildConfig.SERVER_BASE_URL.endsWith("/"))   // Retrofit baseUrl 규칙
    @Test fun kakaoKeyIsNeverBlank() =
        assertTrue(BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank())
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew testDebugUnitTest`
Expected: FAIL (컴파일 오류: `Unresolved reference: SERVER_BASE_URL`)

- [ ] **Step 4: `local.properties` 읽기 구현**

`app/build.gradle.kts`에서 `rootProject.file("local.properties")`가 있으면 `Properties`로 읽고, `buildConfigField("String", "SERVER_BASE_URL", ...)`, `buildConfigField("String", "KAKAO_NATIVE_APP_KEY", ...)`에 위 기본값과 함께 넣는다. `local.properties.example`에 두 속성 이름과 예시 값을 적는다.

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: 지도 실험 화면 구현**

- `map/MapExperiment.kt`: `EXPERIMENT_PATH: List<Pair<Double, Double>>` = `kakao_route_subway_only.json`의 첫 지하철 단계 좌표 중 10개 이하를 (위도, 경도)로 옮긴 상수. Composable `MapExperiment()`은 `AndroidView`로 `MapView`를 만들고 `start(MapLifeCycleCallback, KakaoMapReadyCallback)`을 호출한다. 준비되면 경로선 API(`RouteLineOptions`·`RouteLineSegment`·`RouteLineStyles`)로 선 하나를 그리고 카메라를 그 좌표들에 맞춘다. `onMapError`면 지도 대신 "지도를 불러오지 못했어요: {원인}"을 보여준다. 생명주기 관찰자로 ON_RESUME → `resume()`, ON_PAUSE → `pause()`를 호출한다. 정확한 시그니처는 사용하는 SDK 버전의 문서를 따른다.
- `MainActivity`: `setContent { MapExperiment() }` (P3 Task 2에서 내비게이션으로 바뀐다)
- `src/debug/AndroidManifest.xml`: `android:usesCleartextTraffic="true"`(디버그에서 로컬 서버 `http://10.0.2.2:8000` 사용)

- [ ] **Step 6: (직접) 키 해시 등록과 실기기 실행**

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android | openssl sha1 -binary | openssl base64
```

카카오 콘솔 → 앱 → 플랫폼 Android에 패키지명 `io.github.klanary.jigeumchulbal`과 위 키 해시를 등록하고, 네이티브 앱 키를 `local.properties`의 `kakao.nativeAppKey`에 적는다. 실기기에서 실행해 선이 그려진 화면을 `docs/analysis/map-experiment.png`로 저장하고 §4를 채운다.

- [ ] **Step 7: 앱 CI 작성**

```yaml
# .github/workflows/android.yml
name: android
on:
  push:
    paths: ["android/**", ".github/workflows/android.yml"]
  pull_request:
    paths: ["android/**", ".github/workflows/android.yml"]
jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew testDebugUnitTest assembleDebug
```

README에 배지 `![android](https://github.com/klanary/subway_easy/actions/workflows/android.yml/badge.svg)`를 추가한다.

- [ ] **Step 8: `local.properties` 없이 빌드되는지 확인**

Run: `mv local.properties /tmp/lp.bak; ./gradlew testDebugUnitTest assembleDebug; mv /tmp/lp.bak local.properties`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add android .github/workflows/android.yml README.md docs/analysis docs/learning
git commit -m "feat(android): 앱 뼈대와 카카오 지도 경로선 실험, 앱 CI"
```

---

### Task 7: 카카오 쿼터 확인과 원본 앱 측정 (직접)

**Files:**
- Modify: `docs/analysis/api-verification.md` (§3 쿼터), `docs/measurements/ux-comparison.md`, `docs/analysis/original-app.md`

**Interfaces:**
- Produces: 원본 앱 T1~T3의 탭 수·시간 중앙값 — P5 Task 3의 비교 기준

- [ ] **Step 1: 쿼터 확인**

카카오 콘솔에서 이 앱에 카카오맵 무료 쿼터가 적용되는지(계정에서 처음 활성화한 앱인지), 좌표→주소의 실제 일일 쿼터를 확인해 §3 쿼터 표에 적는다.

- [ ] **Step 2: 원본 앱 측정**

T1(카카오맵: 앱 실행 직후부터 집→학교 경로 목록이 보일 때까지), T2(카카오맵: 경유지 1곳을 넣은 경로; 경유지를 지원하지 않으면 같은 목적을 이루는 조작 전체), T3(카카오지하철: 특정 역의 특정 방향 다음 열차 확인)을 각 3회 화면 녹화한다. 녹화 파일은 저장소에 넣지 않는다. 탭 수와 시간을 ux-comparison.md 원본 표에 적고 중앙값을 계산한다.

- [ ] **Step 3: 원본 앱 분석**

`original-app.md`에 두 앱의 화면 흐름과 불편 사항을 적는다. 불편 사항마다 지금출발의 대응 기능 번호(①~⑩)를 단다.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: 카카오 쿼터 확인과 원본 앱 측정·분석"
```

---

### Task 8: 확인 결과를 스펙과 계획에 반영

**Files:**
- Modify: `docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md`, `docs/superpowers/plans/2026-11-02-p2-server-core.md`

**Interfaces:**
- Consumes: `api-verification.md` §1~§5

- [ ] **Step 1: 스펙과 비교해 고치기**

6.1(쿼터 설정값), 6.5(실시간 이름 예외, 별도 역 표, 호선 표기), 6.6(카카오 경로 형식), 6.7(필드 이름·값 형식, 데이터 없음·한도 초과 코드), 10.1(잠듦 시간)을 확인 결과와 비교한다. 다르면 스펙을 고치고 부록 B에 이력을 남긴다.

- [ ] **Step 2: P2 계획의 값 채우기**

P2 Task 2 `SUBWAY_ID_TO_LINE`·`LINE_ALIASES`, Task 3 CSV 열 이름·`API_NAME_EXCEPTIONS`·`SEPARATE_STATIONS`, Task 9 서울시 응답 코드, Task 11·12의 카카오 대응표 참조와 오류 형식을 확인 결과로 바꾼다.

- [ ] **Step 3: 점검**

스펙 11절의 11/2~11/3 점검 항목 "지도에 경로선 1개 그리기 성공"이 Task 6 Step 6으로 확인됐는지 본다.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers
git commit -m "docs: 1단계 확인 결과를 스펙과 서버 계획에 반영"
```
