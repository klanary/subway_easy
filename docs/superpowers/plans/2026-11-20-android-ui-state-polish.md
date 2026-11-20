# 안드로이드 화면별 상태 다듬기 Implementation Plan

**Goal:** 앱의 모든 화면에서 모든 상태(불러오는 중·빈 화면·오류·오프라인·오래된 정보·화면 고유 상태)가 일관된 모양으로 무언가를 보여 주도록, 흐름 순서대로 한 화면씩 사용자와 합의하며 다듬는다.

**Architecture:** 먼저 Roborazzi 스크린샷 테스트 틀을 깐다(Task 1). 그다음 공통 상태 조각(`StateMessage`·`StateLoading`·`StaleNotice`)을 만든다(Task 2). 이후 화면마다 같은 바퀴를 되풀이한다(Task 3~10). 바퀴마다 화면을 "상태를 받는 `XxxContent`"와 "ViewModel을 잇는 겉껍데기"로 나누고, 상태별 스크린샷을 찍어 사용자에게 보인다. **합의 단계에서 멈추고 기다린 뒤** 반영한다.

**Tech Stack:** Kotlin 2.2, Jetpack Compose(BOM 2025.06.00), Material 3, Robolectric 4.16, Roborazzi, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-20-android-ui-state-polish-design.md`

## Global Constraints

- 범위는 `android/app`의 UI 계층(`ui/**`, `ui/theme`, `ui/common`)이다. 서버·웹·데이터 계층은 바꾸지 않는다. 화면이 상태를 받는 데 필요한 최소한의 ViewModel 정리만 허용한다.
- 기존 단위 테스트(`./gradlew.bat :app:testDebugUnitTest`)는 바퀴마다 계속 통과해야 한다.
- `gradle.properties`의 `android.overridePathCheck=true`를 유지하고, `org.gradle.jvmargs`에 `-Dfile.encoding=UTF-8`을 넣지 않는다.
- 문구는 `ui/common/Copy.kt`에 두고 해요체("~했어요", "~해 주세요")로 쓴다.
- 스크린샷 기준 이미지는 라이트·다크 두 가지, 기본 글꼴 크기, Pixel 8 폭(`w411dp-h914dp-xxhdpi`)으로 남긴다.
- 노선도 SVG가 Robolectric에서 그려지지 않으면, 스크린샷에서는 노선도 영역을 자리표시로 두고 상태 표시층만 검증한다.
- 바퀴(Task)마다 `develop`에서 `polish/<번호>-<이름>` 브랜치를 딴다. 사용자가 확인하면 `develop`에 `--no-ff`로 합친다. 푸시는 사용자가 요청할 때만 한다.
- 커밋 메시지는 저장소 관례대로 한국어로 쓴다(`feat(app): …`, `test(app): …`).
- 각 바퀴의 "합의" 단계에서는 반드시 멈추고 사용자의 결정을 기다린다. 사용자가 고르지 않은 모양을 임의로 넣지 않는다.

## Review Focus

1. **다크 모드:** 다크 테마에서 상태 문구와 버튼이 배경과 대비되어 읽혀야 한다. Task 2의 다크 기준 이미지로 확인한다.
2. **큰 글꼴(fontScale 1.3):** 전체 화면용 오류 메시지의 문구와 "다시 시도" 버튼이 잘리거나 겹치지 않아야 한다. Task 2의 `screenMessage_largeFont`로 확인한다.
3. **긴 오류 문구(3줄 이상):** 가운데 정렬로 줄바꿈되고, 버튼을 밀어내 화면 밖으로 보내지 않아야 한다. Task 2의 `screenMessage_longText`로 확인한다.
4. **시트·목록 안의 인라인 상태:** 높이를 꽉 채워 시트가 화면 전체로 커지면 안 된다. Task 2의 `inlineMessage_doesNotFillHeight`로 확인한다.
5. **오래된 정보 칩:** 내용이 흐려져 있어도 "다시 시도" 동작이 눌려야 한다. Task 2의 `staleNotice_clickRetries`로 확인한다.

---

### Task 1: 스크린샷 테스트 틀 (브랜치 `polish/0-screenshot-setup`)

**Files:**
- Modify: `android/gradle/libs.versions.toml`(roborazzi 버전·라이브러리·플러그인, `androidx-compose-ui-test-junit4`, `androidx-compose-ui-test-manifest`)
- Modify: `android/build.gradle.kts`(roborazzi 플러그인 `apply false`)
- Modify: `android/app/build.gradle.kts`(플러그인 적용, `roborazzi { outputDir.set(file("src/test/screenshots")) }`, testImplementation: roborazzi·roborazzi-compose·roborazzi-junit-rule·compose ui-test-junit4(BOM), debugImplementation: ui-test-manifest)
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/theme/Theme.kt`
- Create: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/screenshot/Snap.kt`
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/common/StateContainerScreenshotTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`. 기존 호출부는 그대로 동작한다.
  - `fun snapBothThemes(name: String, fontScale: Float = 1f, content: @Composable () -> Unit)`(Snap.kt, 테스트 전용). `AppTheme(darkTheme = false/true)`로 감싼 `Surface` 안에 `content`를 그린다. 결과는 `src/test/screenshots/<name>_light.png`와 `<name>_dark.png`로 찍는다(`captureRoboImage`).
  - 스크린샷 테스트 클래스는 `@RunWith(AndroidJUnit4::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")`를 단다.

- [ ] **Step 1: 브랜치를 딴다**

```bash
git switch develop && git switch -c polish/0-screenshot-setup
```

- [ ] **Step 2: Roborazzi 버전을 정한다**

Maven Central에서 `io.github.takahirom.roborazzi:roborazzi`의 최신 안정판(1.43.0 이상)을 확인해 `libs.versions.toml`에 `roborazzi = "<버전>"`으로 넣는다. AGP 8.10, Robolectric 4.16과 맞는지 릴리스 노트로 확인한다.

- [ ] **Step 3: 의존성·플러그인을 추가하고, `AppTheme`에 `darkTheme` 매개변수를 연다**

- [ ] **Step 4: 첫 스크린샷 테스트를 쓴다(현재 `StateContainer`를 그대로 찍는다)**

```kotlin
class StateContainerScreenshotTest {
    @Test fun loading() = snapBothThemes("state_loading") { StateContainer(UiState.Loading, onRetry = {}, emptyText = Copy.NO_RESULTS) { } }
    @Test fun empty() = snapBothThemes("state_empty") { StateContainer(UiState.Empty, {}, Copy.NO_RESULTS) { } }
    @Test fun error() = snapBothThemes("state_error") { StateContainer(UiState.Error(Copy.SERVER_PROBLEM), {}, Copy.NO_RESULTS) { } }
    @Test fun offline() = snapBothThemes("state_offline") { StateContainer(UiState.Offline, {}, Copy.NO_RESULTS) { } }
    @Test fun stale() = snapBothThemes("state_stale") { StateContainer(UiState.Content("내용", stale = true), {}, Copy.NO_RESULTS) { Text(it) } }
}
```

- [ ] **Step 5: 기준 이미지를 기록한다**

Run: `./gradlew.bat :app:recordRoborazziDebug --tests "*ScreenshotTest"`
Expected: BUILD SUCCESSFUL. `app/src/test/screenshots/`에 PNG 10장(5 상태 × 라이트·다크)이 생긴다.

- [ ] **Step 6: PNG를 직접 열어 실제로 그려졌는지 본다(빈 흰 이미지가 아닌지). 다시 검증한다**

Run: `./gradlew.bat :app:verifyRoborazziDebug --tests "*ScreenshotTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 노선도 SVG 렌더 가능성을 확인한다**

`LineMapPictureCache`처럼 `SVG.getFromAsset(...).renderToPicture()`를 Canvas에 그리는 임시 테스트를 하나 찍어 본다. 그려지면 그대로 두고, 안 그려지면 테스트를 지우고 결과를 이 계획 Task 3 머리에 한 줄로 적는다(Global Constraints의 자리표시 규칙을 적용).

- [ ] **Step 8: 전체 단위 테스트를 돌린다**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. 실패 0.

- [ ] **Step 9: 커밋하고, 사용자에게 스크린샷 10장을 보인 뒤 `develop`에 합친다**

```bash
git add android/gradle/libs.versions.toml android/build.gradle.kts android/app/build.gradle.kts android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/theme/Theme.kt android/app/src/test
git commit -m "test(app): 상태별 스크린샷 테스트 틀(Roborazzi) 추가"
git switch develop && git merge --no-ff polish/0-screenshot-setup
```

---

### Task 2: 공통 상태 모양 (브랜치 `polish/1-common-states`)

**Files:**
- Create: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/common/StateViews.kt`
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/common/StateContainer.kt`
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/common/Copy.kt`
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/common/StateViewsScreenshotTest.kt`
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/common/StateViewsTest.kt`

**Interfaces:**
- Consumes: `snapBothThemes`, `AppTheme(darkTheme)` (Task 1)
- Produces (Task 3~10이 가져다 쓴다):
  - `enum class StateSize { Screen, Inline }`
  - `enum class StateKind { Empty, Error, Offline }`
  - `@Composable fun StateMessage(kind: StateKind, text: String, size: StateSize, onRetry: (() -> Unit)?, modifier: Modifier = Modifier)`. `Empty`는 `onRetry`를 받아도 버튼을 그리지 않는다. `Screen`은 높이를 채워 가운데 놓고, `Inline`은 내용 높이만 차지한다.
  - `@Composable fun StateLoading(size: StateSize, modifier: Modifier = Modifier)`. `contentDescription`은 `Copy.LOADING`이다.
  - `@Composable fun StaleNotice(onRetry: () -> Unit, modifier: Modifier = Modifier)`
  - `StateContainer(state, onRetry, emptyText, modifier, size: StateSize = StateSize.Screen, content)`. 내부를 위 조각으로 바꾼다.
  - `Copy.LOADING = "불러오는 중이에요"`

- [ ] **Step 1: 브랜치를 딴다**

```bash
git switch develop && git switch -c polish/1-common-states
```

- [ ] **Step 2: 현재 모습을 정리해 보인다**

Task 1의 기준 이미지 10장, 그리고 역 시트·검색이 인라인으로 그리는 상태(`StationSheet.kt:96-102`, `SearchScreen.kt:126-131`)를 사용자에게 보인다. 에뮬레이터 `pixel8`에서 역 시트 로딩·검색 결과 없음 화면도 캡처해 함께 보인다.

- [ ] **Step 3: 🛑 합의. 멈추고 사용자의 결정을 기다린다**

사용자에게 다음을 제안하고 고르게 한다. 각 항목은 두세 가지 안을 스크린샷이나 텍스트 스케치로 보인다.
- (a) 상태마다 아이콘을 쓸지와 아이콘 종류
- (b) 전체 화면용과 인라인용의 글자 크기·여백
- (c) 오래된 정보 표시: 지금처럼 50% 흐림 + 칩을 유지할지, 흐림 없이 위에 띠를 둘지 등
- (d) 문구 수정안(해요체 유지)

결정을 이 Task 아래 "합의 결과"에 적는다.

- [ ] **Step 4: 실패하는 동작 테스트를 쓴다(`StateViewsTest`, Robolectric + `createComposeRule`)**

```kotlin
@Test fun errorShowsRetry() { set { StateMessage(StateKind.Error, Copy.SERVER_PROBLEM, StateSize.Screen, onRetry = { retried++ }) }
    rule.onNodeWithText(Copy.RETRY).performClick(); assertEquals(1, retried) }
@Test fun offlineShowsRetry() { /* StateKind.Offline, Copy.OFFLINE → Copy.RETRY 노드 존재 */ }
@Test fun emptyHasNoRetry() { set { StateMessage(StateKind.Empty, Copy.NO_RESULTS, StateSize.Screen, onRetry = {}) }
    rule.onNodeWithText(Copy.RETRY).assertDoesNotExist() }
@Test fun loadingIsAnnounced() { set { StateLoading(StateSize.Screen) }
    rule.onNodeWithContentDescription(Copy.LOADING).assertExists() }
@Test fun staleNotice_clickRetries() { set { StaleNotice(onRetry = { retried++ }) }
    rule.onNodeWithText(Copy.STALE).performClick(); assertEquals(1, retried) }
@Test fun inlineMessage_doesNotFillHeight() { set { Box(Modifier.height(800.dp)) { StateMessage(StateKind.Empty, Copy.NO_RESULTS, StateSize.Inline, null, Modifier.testTag("m")) } }
    rule.onNodeWithTag("m").assertHeightIsAtMost(200.dp) }
```

- [ ] **Step 5: 테스트가 실패하는지 확인한다**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StateViewsTest"`
Expected: 컴파일 실패(`StateMessage` 없음).

- [ ] **Step 6: Step 3의 합의 결과대로 `StateViews.kt`를 구현하고, `StateContainer`가 이를 쓰게 바꾼다**

- [ ] **Step 7: 동작 테스트가 통과하는지 확인한다**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StateViewsTest"`
Expected: PASS 6건.

- [ ] **Step 8: 스크린샷 테스트를 쓰고 기록한다(`StateViewsScreenshotTest`)**

`StateKind` 3 × `StateSize` 2 + `StateLoading` 2 + `StaleNotice` 1을 `snapBothThemes`로 찍는다. 다음 둘도 넣는다.
- `screenMessage_largeFont`: `fontScale = 1.3f`, Error, Screen
- `screenMessage_longText`: 50자 이상 세 문장 오류 문구, Screen

Task 1의 `StateContainerScreenshotTest` 기준 이미지는 다시 기록한다.

Run: `./gradlew.bat :app:recordRoborazziDebug --tests "*ScreenshotTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: 전체 단위 테스트 실행 후, 에뮬레이터에서 경로 결과 화면(`RoutesScreen`, 이미 `StateContainer` 사용)을 확인한다**

Run: `./gradlew.bat :app:testDebugUnitTest :app:installDebug`
Expected: BUILD SUCCESSFUL. 에뮬레이터에서 경로 결과 로딩과 결과가 정상으로 보인다.

- [ ] **Step 10: 커밋하고, 전후 스크린샷을 보여 확인받은 뒤 `develop`에 합친다**

```bash
git add android/app/src
git commit -m "feat(app): 공통 상태 모양(StateMessage·StateLoading·StaleNotice) 정리"
git switch develop && git merge --no-ff polish/1-common-states
```

**합의 결과:** (Step 3에서 채운다)

---

### 화면 바퀴(Task 3~10에 공통)

각 화면 Task는 아래 단계를 그대로 따른다. `<화면>`, `<Content>`, `<파일>`은 Task별 표를 따른다.

- [ ] **Step 1: 브랜치를 딴다.** `git switch develop && git switch -c polish/<번호>-<이름>`
- [ ] **Step 2: 상태 목록을 만든다.** 해당 파일과 ViewModel에서 나올 수 있는 상태를 모두 뽑는다. Task 표의 "출발점 상태"에 더해, 코드에서 찾은 상태를 Task 아래 "상태 표"(상태 → 지금 보이는 것)에 적는다.
- [ ] **Step 3: 화면을 나눈다.** 겉껍데기(`<화면>`)는 ViewModel 수집·내비게이션·권한 요청만 맡게 한다. 그리는 일은 `internal fun <Content>(state: …, 콜백들)`로 옮긴다. 동작은 바꾸지 않는다.

  Run: `./gradlew.bat :app:testDebugUnitTest`
  Expected: PASS(기존 테스트 그대로).

  커밋: `refactor(app): <화면>을 상태를 받는 <Content>로 나누기`
- [ ] **Step 4: 현재 모습을 찍는다.** `<Content>ScreenshotTest`에 상태 표의 상태마다 `snapBothThemes("<이름>_<상태>")`를 쓰고 `recordRoborazziDebug`로 기록한다. 에뮬레이터 `pixel8`에서 흐름을 직접 써 보며 화면을 캡처하고, 큰 글꼴(설정 → 글꼴 크기 최대)에서도 한 번 본다.
- [ ] **Step 5: 🛑 합의. 멈추고 사용자의 결정을 기다린다.** 상태별 스크린샷과 에뮬레이터 캡처를 보이고, 상태마다 고칠 점(보이는 것, 할 수 있는 일, 문구)과 쓰다가 걸린 불편(터치 영역, 버튼 위치, 흐름 끊김)을 제안한다. 사용자가 고른 내용을 Task 아래 "합의 결과"에 적는다. 이 결과에 따라 Step 6의 구체적 변경 목록을 Task 아래에 덧붙인다(파일·함수·문구 단위).
- [ ] **Step 6: 반영한다.** 공통 상태는 Task 2의 `StateMessage`·`StateLoading`·`StaleNotice`·`StateContainer`를 쓴다. 동작 변경(눌렀을 때 일어나는 일)이 있으면, 그 동작에 대한 실패하는 테스트(Robolectric 컴포즈 테스트 또는 ViewModel 테스트)를 먼저 쓴 뒤 고친다.
- [ ] **Step 7: 확인한다.**

  Run: `./gradlew.bat :app:recordRoborazziDebug --tests "*ScreenshotTest"` 후 `./gradlew.bat :app:testDebugUnitTest :app:installDebug`
  Expected: BUILD SUCCESSFUL. 에뮬레이터에서 Step 4의 흐름을 다시 써 보고 전후 캡처를 사용자에게 보인다.
- [ ] **Step 8: 커밋하고, 확인받은 뒤 합친다.** 상태 표의 "지금 보이는 것"을 "바뀐 뒤"로 고쳐 스펙 부록(`docs/superpowers/specs/2026-11-20-android-ui-state-polish-design.md` "부록: 화면별 상태 표")에 옮긴다.

```bash
git add android/app/src docs/superpowers
git commit -m "feat(app): <화면> 상태 다듬기"
git switch develop && git merge --no-ff polish/<번호>-<이름>
```

---

### Task 3: 노선도 홈 (브랜치 `polish/2-linemap-home`)

> Task 1 확인: 노선도 SVG(AndroidSVG `renderToPicture` → `nativeCanvas.drawPicture`)는 Robolectric NATIVE 그래픽에서 그대로 그려진다. 자리표시 없이 실제 노선도를 기준 이미지에 넣는다.

- 파일: `ui/linemap/LineMapScreen.kt`, `ui/linemap/LineMapViewModel.kt`, `ui/linemap/TripDraft.kt`, `ui/home/HomeViewModel.kt`
- `<화면>` = `LineMapScreen`, `<Content>` = `LineMapContent`
- 출발점 상태:
  - 노선도 준비 중(`state.map == null` 또는 SVG 그림 없음). 에뮬레이터 첫 실행에서 몇 초간 검색창·하단 탭만 보였다.
  - 지도 불러오기 실패(`Copy.MAP_FAILED`)
  - 위치 권한 없음 / 거부
  - 가까운 역 없음(`state.message`)
  - 출발·경유·도착 고르는 중(`TripBanner`)
  - 진행 중 세션 있음(`SessionBanner`)
  - 하단 패널 접힘/펼침 × 루틴 없음/있음 × 자주 가는 경로 없음/있음

**합의 결과(2026-11-20):**
- 불러오는 중: 원 아래 "노선도를 준비하고 있어요". 첫 실행 빈 화면의 원인을 에뮬레이터에서 찾아 고친다.
- 노선도 실패: 앱이 꺼지지 않고 공통 오류 화면("지도를 불러오지 못했어요" + 다시 시도).
- 출발 고르는 중 띠: 할 일을 말하는 문장("마포에서 출발 · 도착역을 눌러 주세요").
- 아래 패널: 빈 목록에 만드는 방법 한 줄, 경로 줄에 → 표시.
- 위치 권한 거부: 안내 + [설정 열기].
- 잠깐 뜨는 안내: 아래 패널 위 스낵바(4초).
- 다크용 노선도: 보류(우선순위 낮음, 나중에 결정).
- 노선도 저장·갱신: 사용자 요청. 범위 확인 중.

### Task 4: 역 시트 (브랜치 `polish/3-station-sheet`)

- 파일: `ui/linemap/StationSheet.kt`, `ui/linemap/StationSheetViewModel.kt`
- `<화면>` = `StationSheet`, `<Content>` = `StationSheetContent`(`ModalBottomSheet` 안쪽 `Column`)
- 출발점 상태: Loading(지금은 진행 막대), Content(방향별 열차), Content stale, Empty(`Copy.NO_TRAIN_INFO`), Error, Offline, 미지원 노선 포함(`unsupportedLines`), 시간표 없는 노선(`noTimetableLines`), 시각 NOW/+1h/+2h/직접, 막차·급행 표시, 역할 버튼 비활성(같은 역, 경유는 출발·도착 뒤)
- 인라인 상태는 `StateSize.Inline`을 쓴다.

**합의 결과(2026-11-20):**
- 오류·오프라인: 공통 한 줄 상태 + [다시 시도], 오류 때도 호선 줄 유지.
- 오래된 정보: 공통 띠(정보가 늦어지고 있어요 · 다시 시도).
- 막차·급행: 열차 옆 작은 표식.
- [닫기]: 그대로 둔다.
- 열차 줄: 호선 색 배지 아래 방향을 묶고, 첫 열차 남은 시간을 크고 굵게, 그다음은 작게.
- 근거: 실시간이 아닌 방향 줄마다 작게. "실측"은 추상적이라 "운행 기록"으로 바꾼다(변경 가능).
- [시각 선택]: 고른 뒤 "19:30 기준"처럼 보인다.

### Task 5~10 합의 결과(2026-11-21, A+B 방식: 한 번에 합의)

- 검색: 결과에 호선 색 배지, 처음 화면 안내("역 이름이나 초성(ㅅㅊ)으로 찾아보세요"), 빈 결과·오류 공통 한 줄 상태, 최근 검색 줄에 시계 아이콘.
- 경로 결과·편집: 카드에 탈 호선 배지(5호선 → 9호선)와 출발·도착 강조, 편집 버튼 정리(⇅ 아이콘·출발 칸 안 현재 위치 아이콘·카드 아래 + 경유지), 경로 없음 문구에 할 일.
- 경로 상세: 단계 줄 정리(배지 + "마포 → 여의도" 크게, 방면·정거장·시간 작게), 제목 "마포 → 노량진 · 9분", 열차 고르기 줄(아이콘 버튼, 시각 크게, 번호 작게), 알림 버튼 아래 고정.
- 루틴 목록: 꺼진 루틴 흐리게, 빈 목록 안내.
- 알림 대기: 남은 시간 크게, [탑승했어요] 화면 폭 큰 버튼. 알림 탑승: 지난 지점 ✓, 다음 지점 강조.
- 설정: 허용된 권한은 ✓, 허용 안 된 것만 버튼.

### Task 5: 검색 (브랜치 `polish/4-search`)

- 파일: `ui/search/SearchScreen.kt`, `ui/search/SearchViewModel.kt`
- `<화면>` = `SearchScreen`, `<Content>` = `SearchContent`
- 출발점 상태: 입력 없음 + 최근 검색 없음/있음, 입력 중 Loading, 결과 있음, 결과 없음(`Copy.NO_RESULTS`), 오류, 오프라인, 초성 검색

### Task 6: 경로 편집·결과 (브랜치 `polish/5-routes`)

- 파일: `ui/routeedit/RouteEditScreen.kt`, `ui/routeedit/RouteFlowViewModel.kt`, `ui/routes/RoutesScreen.kt`, `ui/routes/RouteCardUi.kt`
- `<Content>` = `RouteEditContent`, `RoutesContent`
- 출발점 상태: 출발/도착 비어 있음, 경유 있음/없음, 같은 역 지정, 경로 Loading/결과/없음(`Copy.NO_ROUTES`)/오류/오프라인/stale, 환승 횟수별 카드 여러 개

### Task 7: 경로 상세·열차 고르기 (브랜치 `polish/6-route-detail`)

- 파일: `ui/routedetail/RouteDetailScreen.kt`, `ui/routedetail/TrainPicker.kt`, `ui/routedetail/StepText.kt`
- `<Content>` = `RouteDetailContent`
- 출발점 상태: 경로 정보 없음(`Copy.ROUTE_GONE`), 구간 경고(`RouteDetailScreen.kt:254`의 "⚠" 문구), 열차 고정됨/안 됨, 이미 떠난 열차, 늦은 열차 불러오는 중, 더 없음(`Copy.NO_LATER_TRAIN`), 급행/일반 필터, 놓침

### Task 8: 루틴 목록·편집 (브랜치 `polish/7-routine`)

- 파일: `ui/routine/RoutineScreens.kt`, `ui/routine/RoutineEditViewModel.kt`
- `<Content>` = `RoutineListContent`, `RoutineEditContent`
- 출발점 상태: 루틴 없음, 여러 개, 켜짐/꺼짐, 편집 중 요일 미선택, 시각 미정, 저장 실패, 삭제 확인

### Task 9: 세션·시작 흐름 (브랜치 `polish/8-session`)

- 파일: `ui/session/SessionScreen.kt`, `ui/session/SessionViewModel.kt`, `ui/session/StartSessionFlow.kt`, `ui/session/StartGate.kt`
- `<Content>` = `SessionContent`
- 출발점 상태: 대기 중, 감지 중, 출발하세요, 놓침, 끝남, 앱 안에서만 알림(`inAppOnly`), 시작 대화상자(`StartDialog`: INTRO·EXACT_ALARM·REPLACE)
- 단위 테스트가 많은 영역이다. Step 3의 분리 뒤 `session` 패키지 테스트를 꼭 돌린다.

### Task 10: 설정·권한 (브랜치 `polish/9-settings`)

- 파일: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `ui/settings/PermissionRows.kt`
- `<Content>` = `SettingsContent`
- 출발점 상태: 권한 허용/거부/영구 거부(알림·정확한 알람·위치), 설정 값별 표시

---

### 마무리

- [ ] 모든 바퀴가 `develop`에 합쳐진 뒤 `./gradlew.bat :app:testDebugUnitTest :app:verifyRoborazziDebug`를 실행한다. Expected: BUILD SUCCESSFUL.
- [ ] 에뮬레이터에서 처음부터 끝까지(홈 → 역 시트 → 검색 → 경로 → 상세 → 루틴 → 세션 → 설정) 한 번 써 보고 사용자에게 보고한다.
