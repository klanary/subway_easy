# P5. 측정과 제출 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 성공 기준 S1~S5의 증거(부하 테스트 보고서, 실기기 체크리스트, UX 비교, 서명된 APK 릴리스, README, 시연 영상)를 만들어 11/25에 제출한다.

**Architecture:** 코드 변경은 릴리스 서명 설정뿐이고 나머지는 측정·문서·배포 작업이다. 측정은 P2의 부하 테스트 스크립트와 P1의 측정 규칙을 그대로 쓴다.

**Tech Stack:** Python(`scripts/load_test.py`), Gradle 서명 설정, apksigner, GitHub Releases, 화면 녹화

**Spec:** `docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md` (2장 S1~S5, 4.6, 9.2, 9.3, 10.2, 10.4, 11절, 12절)

**기간:** 11/23 ~ 11/26 (11/23 기능 개발 중단, 11/25 제출) · **선행:** P4 완료 또는 3.4절 최소 버전으로 마감한 상태

## Global Constraints

- 11/23부터 기능을 더하지 않는다. 버그 수정만 Issue와 PR로 한다. (11)
- 부하 테스트는 배포 서버가 아닌 로컬 서버에서 `MOCK_UPSTREAM=true`로만 한다. (9.3)
- 릴리스 서명 키스토어는 로컬에만 두고 커밋하지 않으며 별도 위치에 백업한다. (10.2)
- README와 앱의 설정 > 정보 화면에 서울 열린데이터광장과 카카오를 출처로, 각 데이터의 이용허락 문구와 함께 표시한다. (10.4)
- 시연 영상·스크린샷에는 공공 장소만 쓴다. 본인 집·학교 위치가 드러나지 않게 한다.

## Review Focus

1. 릴리스 서명 키의 해시를 카카오 콘솔에 등록하지 않으면 릴리스 APK에서만 지도가 뜨지 않는다 → 체크리스트에 "릴리스 APK로 지도 표시"를 두고 릴리스 전에 확인한다. (Task 4 Step 5)
2. 두 앱의 준비 상태가 다르면(지금출발만 내 장소가 저장돼 있음 등) 탭 수 비교가 무의미하다 → 두 앱 모두 같은 준비 상태(집·학교 저장 여부)로 재고, 그 조건을 표에 적는다. (Task 3 Step 1)
3. 새로 설치한 사람(심사자)이 첫 실행·권한 거부 경로에서 막힌다 → 초기화한 기기에서 첫 실행부터 권한 거부까지 체크리스트로 확인한다. (Task 2)
4. Render가 잠든 상태에서 심사자가 처음 열면 1분 가까이 기다린다 → README 설치 안내에 첫 요청이 느릴 수 있다고 적는다. (Task 5)
5. 릴리스 빌드에서 코드 축소(R8)가 직렬화·Retrofit·지도 SDK를 깨뜨린다 → 1차 릴리스는 `isMinifyEnabled = false`로 둔다. (Task 4 Step 2)

---

### Task 1: 부하 테스트 실행과 보고서 (S3)

**Files:**
- Create: `docs/measurements/load-test-YYYYMMDD.md` (스크립트가 만듦)

**Interfaces:**
- Consumes: P2 Task 15 `scripts/load_test.py`, `FixtureTransport`

- [ ] **Step 1: 모의 모드로 로컬 서버 실행**

Run: `cd server && MOCK_UPSTREAM=true uvicorn app.main:app --port 8000`
Expected: `Uvicorn running on http://127.0.0.1:8000`

- [ ] **Step 2: 10분·50명 부하 테스트**

Run (다른 터미널): `cd server && python -m scripts.load_test --users 50 --minutes 10 --out ../docs/measurements/load-test-$(date +%Y%m%d).md`
Expected: 보고서 저장. 앱 요청은 약 3,500건(사용자당 실시간 60건 + 경로 10건), 외부 API 호출은 약 100건 이하(실시간 20초 캐시 × 역 3곳, 경로 10분 캐시 × 2개), 외부 API 호출 비율 약 0.03, 캐시 적중률 약 0.97. 크게 다르면 원인(캐시 키, 동시 요청 합치기)을 찾아 Issue로 남긴다.

- [ ] **Step 3: 해석 덧붙이기**

보고서 끝에 비율이 나온 이유(유효 시간, 동시 요청 합치기, 사용자별 시작 시각 분산)와 이 수치가 실제 한도(서울 1,000건/일)에서 의미하는 바를 적는다.

- [ ] **Step 4: Commit** — `git add docs/measurements && git commit -m "docs: 부하 테스트 결과 (S3)"`

---

### Task 2: 실기기 체크리스트와 절전 모드 확인 (9.2)

**Files:**
- Create: `docs/measurements/device-checklist.md` (P4 Task 7·9에서 적은 기록이 있으면 합친다)

**Interfaces:**
- Produces: 항목마다 결과(✓/✗), 날짜, 기기·Android 버전, 실패 시 Issue 번호

- [ ] **Step 1: 체크리스트 작성**

항목: (1) 앱을 지우고 새로 설치 → 첫 실행에 권한 요청이 없다 (2) 홈 → 내 장소 칩 → 경로 결과 → 경로 상세(구간별 색, 출발·환승·도착 마커) (3) 통합 검색: 초성 "ㅎㄷㅇㄱ", 장소 검색, 지도에서 선택(주소·가까운 역), 역의 [실시간] → 역 상세 (4) 역 상세: 30초 갱신과 "n초 전 갱신", 홈으로 나가면 갱신 멈춤(서버 접근 로그), [★] → 홈에 표시 (5) 위치 권한 거부 → 출발 칸 빈 칸과 안내 (6) 알림 권한 거부 → 앱 안 안내 (7) 정확한 알람 거부 → "알림이 늦을 수 있어요" (8) 하차 알림: 지점마다 2분 전 알림, 잠금 화면 표시, 진동, 진행 중 알림 카운트다운, [종료] (9) 승차 전 알림: 경로의 출발 타이밍, 역의 N분 전, 알림의 [탑승했어요] (10) 절전 모드: `adb shell dumpsys battery unplug` → `adb shell dumpsys deviceidle force-idle`에서 대기·탑승 알람이 울림, 끝나면 `adb shell dumpsys deviceidle unforce`와 `adb shell dumpsys battery reset` (11) 재부팅: 탑승 단계 복원, 대기 단계는 "대기 알림이 중단됐어요" (12) 비행기 모드: 오프라인 화면과 [다시 시도] (13) 서버가 잠든 뒤 첫 요청: 로딩 뒤 결과 또는 "서버에 연결하지 못했어요" (14) 지도 키를 틀리게 한 빌드: 지도 자리 대체 문구, 목록 동작 (15) 릴리스 APK에서 지도 표시(Task 4 뒤)

- [ ] **Step 2: (직접) 수행과 기록**

실패 항목은 버그 템플릿으로 Issue를 만들고, 고친 PR에서 Issue를 닫는다(S4, 12절).

- [ ] **Step 3: Commit** — `git add docs/measurements && git commit -m "docs: 실기기·절전 모드 체크리스트 결과"`

---

### Task 3: 지금출발 UX 측정과 비교 (S2)

**Files:**
- Modify: `docs/measurements/ux-comparison.md`

**Interfaces:**
- Consumes: P1 Task 7의 원본 앱 측정값과 규칙

- [ ] **Step 1: 준비 상태 맞추기**

원본 측정 때와 같은 준비 상태(집·학교 저장 여부, 즐겨찾기 여부, 로그인 여부)를 지금출발에도 만들고, 그 조건을 표 위에 적는다.

- [ ] **Step 2: (직접) 측정**

T1~T3을 지금출발로 각 3회 녹화해 탭 수와 시간을 적고 중앙값을 낸다(4.6).

- [ ] **Step 3: 비교 정리**

과제별 원본·지금출발 중앙값과 차이(탭 수 차, 시간 차, 줄어든 비율)를 한 표로 만들고, 차이가 난 이유를 기능과 연결해 적는다(예: 내 장소 칩, 경로 편집에서 검색 자동 열기, 초성 검색, 검색 결과의 [실시간] 버튼, 자주 가는 경로 1탭). 지금출발이 더 느린 과제가 있으면 그대로 적는다.

- [ ] **Step 4: Commit** — `git add docs/measurements && git commit -m "docs: 원본 대비 UX 측정 비교 (S2)"`

---

### Task 4: 릴리스 서명과 GitHub Release (S1, 10.2)

**Files:**
- Modify: `android/app/build.gradle.kts`, `android/local.properties.example`

**Interfaces:**
- Produces: `local.properties`의 `release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword`를 읽는 `signingConfigs.release`. 넷 중 하나라도 없으면 릴리스 빌드를 서명하지 않는다(CI는 릴리스를 빌드하지 않는다).

- [ ] **Step 1: (직접) 키스토어 만들기와 백업**

```bash
keytool -genkeypair -v -keystore ~/keys/jigeumchulbal-release.jks -alias jigeumchulbal -keyalg RSA -keysize 2048 -validity 10000
```

저장소 밖에 두고, 다른 곳(개인 클라우드나 외장 저장소)에 한 벌 더 보관한다. 경로와 비밀번호를 `local.properties`에 적는다.

- [ ] **Step 2: 서명 설정**

`signingConfigs.release`를 위 Interfaces대로 만들고 `buildTypes.release`에 연결한다. `isMinifyEnabled = false`. `versionName = "0.1.0"`, `versionCode = 1`.

- [ ] **Step 3: 빌드와 서명 확인**

Run: `./gradlew assembleRelease && "$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
Expected: `BUILD SUCCESSFUL`, 서명 인증서 정보 출력(CN이 키스토어 생성 때 입력한 값)

- [ ] **Step 4: (직접) 릴리스 키 해시 등록**

```bash
keytool -exportcert -alias jigeumchulbal -keystore ~/keys/jigeumchulbal-release.jks | openssl sha1 -binary | openssl base64
```

카카오 콘솔의 Android 플랫폼에 디버그 해시와 함께 등록한다(10.2).

- [ ] **Step 5: (직접) 릴리스 APK로 지도 확인** — 실기기에 `app-release.apk`를 설치해 체크리스트 (15)를 확인한다.

- [ ] **Step 6: 릴리스 올리기**

`git tag v0.1.0 && git push origin v0.1.0` 뒤 GitHub Releases에 `v0.1.0`을 만들어 APK와 변경 내역(완성 기능 ①~⑩, 최소 버전으로 바꾼 기능, 알려진 한계)을 올린다.

- [ ] **Step 7: Commit** — `git add android/app/build.gradle.kts android/local.properties.example docs/learning && git commit -m "build(android): 릴리스 서명 설정"`

---

### Task 5: README 완성 (S1, S5, 10.2, 10.4)

**Files:**
- Modify: `README.md`
- Create: `docs/images/*.png`(공공 장소로 찍은 스크린샷)

- [ ] **Step 1: 절 작성**

순서: 배지(server·android CI) / 한 줄 소개, 시연 영상 링크, 스크린샷 / 주요 기능 ①~⑩과 원본 대비 개선(Task 3 표 요약) / 설치(Releases의 APK 링크, "출처를 알 수 없는 앱 설치 허용" 방법, 서버가 잠들어 있으면 첫 요청이 1분 가까이 걸릴 수 있음) / 아키텍처(5.2 데이터 흐름, 5.3 기술 스택) / 서버(공개 `/docs` 링크, 캐시·한도 보호·요청 제한 요약, Task 1 결과) / 개발 과정(스펙·계획·학습 노트 링크, Issue·PR로 기록한 버그 목록) / 알려진 한계(3.4 최소 버전으로 바꾼 기능, Render 잠듦, 서버 재시작 시 일일 호출 수 초기화, 버스 실시간 없음) / 데이터 출처와 이용허락(10.4) / 라이선스

- [ ] **Step 2: 링크 확인**

Run: `grep -oE '\]\(([^)]+)\)' README.md | sed -E 's/\]\((.*)\)/\1/' | grep -v '^http' | while read p; do [ -e "$p" ] || echo "깨진 링크: $p"; done`
Expected: 출력 없음

- [ ] **Step 3: Commit** — `git add README.md docs/images && git commit -m "docs: README 완성"`

---

### Task 6: 시연 영상과 제출 (S1, S4, S5)

**Files:**
- Modify: `README.md`(영상 링크, 사용 기간)

- [ ] **Step 1: (직접) 시연 영상**

S1 시나리오(출발지 → 목적지 검색 → 지도 확인 → 탑승 → 하차 알림)를 공공 장소(예: 홍대입구역 → 강남역)로 2~3분 녹화해 올리고(예: YouTube 일부 공개) README에 링크한다.

- [ ] **Step 2: 사용 기록 정리 (S4)**

본인 통학에 쓴 기간(시작일~제출일)과 그동안 만든 Issue·PR 목록을 README "개발 과정"에 적는다. 2주에 못 미치면 실제 기간을 그대로 적고 제출 뒤에도 기록을 이어 간다.

- [ ] **Step 3: 학습 노트 확인 (S5)**

Run: `ls docs/learning/*.md | grep -v README | wc -l`
Expected: 코드를 바꾼 작업 수와 비슷한 개수. 빠진 노트를 채우고, Claude Code가 쓴 초안은 본인이 고쳐 쓴다(12절).

- [ ] **Step 4: 제출 점검**

S1(배포 서버 동작, Releases APK, 시연 영상), S2(Task 3 표), S3(Task 1 보고서), S4(Step 2), S5(Step 3)를 확인하고 11/25에 제출한다. 고친 버그가 있으면 `v0.1.1`로 다시 릴리스한다.

- [ ] **Step 5: Commit** — `git add README.md docs && git commit -m "docs: 시연 영상과 제출 점검"`
