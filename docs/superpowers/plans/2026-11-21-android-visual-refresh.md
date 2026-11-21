# 안드로이드 화면 다듬기 2차(템플릿 느낌 벗기) Implementation Plan

**Goal:** Material 기본 테마를 걷어 내고 "깔끔한 교통 도구"(B) 기준(색·글꼴·크기·간격·모서리)을 세운 뒤, 앱 아이콘·시작 화면을 만들고, 홈을 한 손 배치로 바꾸고, 나머지 화면을 한 번에 합의해 손질한다.

**Architecture:** `ui/theme`에 색 구성·글꼴·글자 단계·모양·간격 토큰을 두고 `MaterialTheme`으로 전 화면에 퍼뜨린다(Task 1). 아이콘·시작 화면은 리소스와 `core-splashscreen`(Task 2). 홈은 `LineMapContent`의 위 막대·아래 탭 패널을 "아래 검색 패널"로 바꾸고 루틴 관리는 새 `RoutinesDest` 화면으로 옮긴다(Task 3). 나머지 화면은 1차처럼 한 번에 찍어 합의한 뒤 반영한다(Task 4). 확인은 상태별 스크린샷(30초) + 마지막 에뮬레이터 한 번(Task 5).

**Tech Stack:** Kotlin 2.2, Jetpack Compose(BOM 2025.06.00), Material 3, Navigation Compose(타입 안전 경로), Robolectric 4.16 + Roborazzi 1.79.0, `androidx.core:core-splashscreen`, Pretendard 글꼴.

**Spec:** `docs/superpowers/specs/2026-11-21-android-visual-refresh-design.md`

## Global Constraints

- 범위: `android/app` UI 계층, 앱 아이콘·시작 화면·글꼴 리소스. 서버·데이터 계층·알림 로직은 바꾸지 않는다.
- 새 의존성은 글꼴 파일과 `androidx.core:core-splashscreen` 외에 추가하지 않는다.
- 기존 단위 테스트는 계속 통과해야 한다(시작 시점 404개). 스크린샷 기준 이미지는 바뀌는 것이 정상이며 `recordRoborazziDebug`로 다시 기록한다.
- `gradle.properties`의 `android.overridePathCheck=true` 유지, `-Dfile.encoding=UTF-8` 넣지 않음.
- 문구는 `ui/common/Copy.kt`, 해요체.
- 색은 호선 색(`LineColors.kt`)과 강조색 하나(신호 파랑)에만 쓴다. 강조색은 라이트 `#2F5BEA` / 다크 `#7C9BFF`.
- 카드 안에 카드를 넣지 않는다. 터치 영역 최소 48dp. 그림자 대신 얇은 선, 떠 있는 것(아래 패널, 스낵바)만 옅은 그림자.
- 질문은 Task 2(아이콘)와 Task 4(화면별)에서만, 모아서 한다. 에뮬레이터 확인은 Task 5에서 한 번.
- 브랜치는 Task마다 이전 Task 브랜치 위에 `polish2/<번호>-<이름>`. `develop`에는 Task 5 끝에 사용자 확인 후 `--no-ff`로 합친다. 푸시는 사용자가 요청할 때만.
- 커밋 메시지는 한국어(`feat(app): …`).
- 글꼴 파일 내려받기는 사용자 허락이 있어야 한다(파일 이름·출처·용량을 먼저 밝힌다). 허락이 없으면 시스템 글꼴로 두고 나머지는 그대로 진행한다.

## Review Focus

1. **다크 모드 대비:** 새 색 구성에서 보조 글자(`onSurfaceVariant`)·강조 위 글자가 바탕과 4.5:1 이상이어야 한다. Task 1의 `PaletteContrastTest`.
2. **큰 글꼴(1.3배) 홈 패널:** 검색 줄·루틴 줄이 잘리거나 겹치지 않아야 한다. Task 3의 `home_panel_font130` 스크린샷.
3. **루틴도 자주 가는 경로도 없는 홈:** 패널이 비지 않고 시작 안내가 보여야 한다. Task 3의 `home_panel_empty` 스크린샷.
4. **경유지가 있는 루틴을 눌렀을 때:** 경로 결과가 경유지까지 넣어 열려야 한다. Task 3의 `routineWithViaOpensRouteWithVia`.
5. **자주 가는 경로가 10개일 때:** 패널이 노선도를 다 덮지 않아야 한다(화면 높이의 절반 이하). Task 3의 `panelNeverCoversHalfTheScreen`.

---

### Task 1: 테마 기준 (브랜치 `polish2/0-theme`, develop에서)

**Files:**
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/theme/Palette.kt`(색 값)
- Create: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/theme/Type.kt`(글꼴·글자 단계)
- Create: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/theme/Tokens.kt`(간격·모양)
- Create: `android/app/src/main/res/font/pretendard_regular.otf`, `pretendard_medium.otf`, `pretendard_semibold.otf`, `pretendard_bold.otf`(허락 시)
- Modify: 화면 코드에서 큰 숫자·버튼 모양 사용처(Step 6)
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/theme/PaletteContrastTest.kt`

**Interfaces:**
- Produces:
  - `val LightColors: ColorScheme`, `val DarkColors: ColorScheme`(Palette.kt). 스펙 6.1 값. `surfaceContainerLowest…Highest`는 라이트에서 모두 `#FFFFFF`, 다크에서 모두 `#171B22`(카드·시트가 회색으로 뜨지 않게). `tertiary = #B54708 / #FDB022`, `tertiaryContainer = #FEF0C7 / #3D2A0A`, `onTertiaryContainer = #B54708 / #FDB022`. `outlineVariant`도 outline과 같은 값.
  - `val AppTypography: Typography`(Type.kt). M3 칸에 스펙 6.2 단계를 이렇게 넣는다:

    | M3 칸 | 크기/굵기 | 스펙 이름 |
    |---|---|---|
    | headlineMedium | 32 / Bold, `fontFeatureSettings = "tnum"` | 큰 숫자 |
    | headlineSmall, titleLarge | 22 / Bold | 제목 |
    | titleMedium | 17 / SemiBold | 소제목 |
    | titleSmall | 15 / SemiBold | (소제목 작은 것) |
    | bodyLarge, bodyMedium | 15 / Regular | 본문 |
    | bodySmall | 13 / Regular | 보조 |
    | labelLarge | 14 / SemiBold | 버튼 글자 |
    | labelMedium, labelSmall | 12 / SemiBold | 라벨 |

    모든 칸의 글꼴은 `Pretendard` FontFamily(파일이 없으면 `FontFamily.Default`). 숫자 칸(headlineMedium, titleMedium, bodyLarge)은 `tnum`.
  - `object Spacing { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp }`(Tokens.kt)
  - `val AppShapes = Shapes(extraSmall = 8.dp, small = 12.dp, medium = 14.dp, large = 16.dp, extraLarge = 16.dp)` 모서리, `val ButtonShape = RoundedCornerShape(14.dp)`, `val PillShape = RoundedCornerShape(50)`.
  - `AppTheme(darkTheme, content)`는 위 셋을 `MaterialTheme(colorScheme, typography = AppTypography, shapes = AppShapes)`로 넣는다(시그니처 그대로).
  - `fun contrastRatio(a: Color, b: Color): Double`(Palette.kt, WCAG 상대 휘도 공식).

- [ ] **Step 1: 브랜치를 딴다** — `git switch develop && git switch -c polish2/0-theme`

- [ ] **Step 2: 실패하는 대비 테스트를 쓴다**

```kotlin
class PaletteContrastTest {
    private fun assertAA(fg: Color, bg: Color, name: String) =
        assertTrue("$name ${"%.2f".format(contrastRatio(fg, bg))}", contrastRatio(fg, bg) >= 4.5)

    @Test fun lightTextIsReadable() {
        assertAA(LightColors.onSurface, LightColors.surface, "onSurface")
        assertAA(LightColors.onSurfaceVariant, LightColors.surface, "onSurfaceVariant")
        assertAA(LightColors.onSurfaceVariant, LightColors.background, "onSurfaceVariant/bg")
        assertAA(LightColors.onPrimary, LightColors.primary, "onPrimary")
        assertAA(LightColors.onPrimaryContainer, LightColors.primaryContainer, "onPrimaryContainer")
        assertAA(LightColors.onTertiaryContainer, LightColors.tertiaryContainer, "onTertiaryContainer")
    }

    @Test fun darkTextIsReadable() { /* 같은 6쌍을 DarkColors로 */ }

    @Test fun accentIsNotLine1Blue() = assertTrue(contrastRatio(LightColors.primary, Color(0xFF0052A4)) > 1.4)
}
```

- [ ] **Step 3: 실행해 실패를 확인한다** — `./gradlew.bat :app:testDebugUnitTest --tests "*PaletteContrastTest"` → 컴파일 실패(`LightColors` 없음).

- [ ] **Step 4: Palette.kt·Type.kt·Tokens.kt를 만들고 `AppTheme`이 쓰게 한다.** 대비가 4.5에 못 미치는 쌍이 나오면 그 값만 어둡게(라이트)/밝게(다크) 조정하고 스펙 6.1 표를 함께 고친다.

- [ ] **Step 5: 글꼴** — 사용자에게 묻는다: "Pretendard v1.3.9(SIL OFL) 공식 릴리스(github.com/orioncactus/pretendard)에서 Regular·Medium·SemiBold·Bold 4개(KS X 1001 축소판이 있으면 그것, 합계 약 N MB)를 내려받아 `res/font`에 넣어도 될까요?" 허락하면 내려받은 압축은 스크래치 폴더의 빈 폴더에 풀고, 파일 4개만 위 이름(소문자·밑줄)으로 복사한다. 허락이 없으면 `FontFamily.Default`로 두고 원장에 적는다.

- [ ] **Step 6: 큰 숫자·버튼 모양 사용처를 기준으로 바꾼다**
  - 남은 시간·총 시간·대기 시간 큰 숫자: `StationSheet.kt` DirectionRow 첫 열차 시간, `RoutesScreen.kt` 카드 총 시간, `SessionScreen.kt` 대기 줄 → `MaterialTheme.typography.headlineMedium`(굵기 덧씌움 제거).
  - `Button`·`OutlinedButton`·`FilledTonalButton`(13곳)에 `shape = ButtonShape`.
  - `Card` 사용처(`SessionBannerCard`, `RouteCard`)는 `OutlinedCard`(선, 그림자 0).
  - `LineBadge`·칩은 `PillShape`.

- [ ] **Step 7: 대비 테스트와 전체 테스트·스크린샷**

Run: `./gradlew.bat :app:recordRoborazziDebug`
Expected: BUILD SUCCESSFUL, 실패 0. 스크린샷이 새 색·글꼴로 다시 기록된다.

- [ ] **Step 8: 전후를 사용자에게 보인다**(질문 없이 결과 보고: 역 시트·경로 결과·노선도 홈 실패 화면 전후 각 1장). 커밋: `feat(app): 앱 테마 기준(색·글꼴·글자 단계·모양) 세우기`

---

### Task 2: 앱 아이콘·시작 화면 (브랜치 `polish2/1-icon`)

**Files:**
- Create: `res/drawable/ic_launcher_foreground_a.xml`, `res/drawable/ic_launcher_foreground_b.xml`(후보 두 개, 108dp 적응형 아이콘 앞 그림, 안전 영역 66dp 안)
- Create: `res/values/colors.xml`(`brand_blue #2F5BEA`, `splash_bg_light #F6F7F9`, `splash_bg_dark #0E1116`)
- Create: `res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`(바탕 `@color/brand_blue`, 앞 `@drawable/ic_launcher_foreground`)
- Create: `res/values/themes.xml`, `res/values-night/themes.xml`(`Theme.App`, `Theme.App.Starting`)
- Modify: `AndroidManifest.xml`(`android:icon`, `android:roundIcon`, `android:theme="@style/Theme.App.Starting"`), `MainActivity.kt`(`installSplashScreen()`를 `super.onCreate` 전), `gradle/libs.versions.toml`·`app/build.gradle.kts`(`androidx.core:core-splashscreen:1.0.1`)
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/theme/IconScreenshotTest.kt`

**Interfaces:**
- Produces: `@drawable/ic_launcher_foreground`(고른 안), `@style/Theme.App`(라이트: parent `android:Theme.Material.Light.NoActionBar`, 다크: `android:Theme.Material.NoActionBar`, `android:windowBackground` = 바탕색), `@style/Theme.App.Starting`(parent `Theme.SplashScreen`, `windowSplashScreenBackground` = 바탕색, `windowSplashScreenAnimatedIcon` = `@mipmap/ic_launcher`, `postSplashScreenTheme` = `@style/Theme.App`).

- [ ] **Step 1: 브랜치** — `git switch -c polish2/1-icon`
- [ ] **Step 2: 후보 A·B를 그린다.** A: 열차 앞모습(둥근 사각 차체 + 창 + 두 불빛)에 오른쪽 위로 향하는 화살표. B: 굵은 시계 테두리 안에 오른쪽 화살표(지금 출발). 둘 다 흰색 단색, 파랑 바탕.
- [ ] **Step 3: 스크린샷 테스트로 두 후보를 찍는다** — `icon_a`, `icon_b`: 원형 마스크·사각 마스크·작은 크기(48dp)로 각각 그린 줄. `recordRoborazziDebug --tests "*IconScreenshotTest"`.
- [ ] **Step 4: 🛑 사용자에게 두 후보를 보이고 고르게 한다.** 고른 것을 `ic_launcher_foreground.xml`로 두고 다른 것은 지운다(테스트도 고른 것 하나만 남김).
- [ ] **Step 5: 시작 화면·매니페스트·의존성 반영.** 다크 모드 기기에서 흰 화면으로 시작하지 않아야 한다.
- [ ] **Step 6: 전체 테스트** — `./gradlew.bat :app:recordRoborazziDebug` → 실패 0. `./gradlew.bat :app:assembleDebug` → 성공.
- [ ] **Step 7: 커밋** — `feat(app): 앱 아이콘과 시작 화면`

---

### Task 3: 홈 한 손 배치와 루틴 목록 화면 (브랜치 `polish2/2-home`)

**Files:**
- Modify: `ui/linemap/LineMapScreen.kt`(위 막대 제거, 새 아래 패널), `ui/Destinations.kt`(`RoutinesDest`), `ui/AppNavHost.kt`, `ui/routine/RoutineScreens.kt`(`RoutinesScreen`)
- Create: `ui/home/HomePanel.kt`(아래 패널), `ui/home/TodayRoutines.kt`(순수 함수)
- Modify: `ui/common/Copy.kt`
- Test: `test/.../ui/home/TodayRoutinesTest.kt`, `test/.../ui/AppNavGraphTest.kt`, `test/.../ui/linemap/LineMapContentScreenshotTest.kt`, `test/.../ui/home/HomePanelTest.kt`

**Interfaces:**
- Consumes: Task 1 토큰(`Spacing`, `PillShape`, `ButtonShape`).
- Produces:
  - `fun todayRoutines(routines: List<RoutineEntity>, nowMinute: Int, today: DayOfWeek, limit: Int = 2): List<RoutineEntity>` — 켜져 있고 오늘 요일이며 아직 지나지 않은(`minuteOfDay > nowMinute`) 루틴을 시각 순으로 최대 `limit`개.
  - `fun RoutineEntity.toRouteGraph(): RouteFlowGraph` — `RouteFlowGraph(fromId, fromName, viaId, viaName, toId, toName)`(경로 결과를 바로 연다).
  - `@Serializable object RoutinesDest` → `RoutinesScreen(navController)`: 제목 "루틴", 뒤로, 안에 기존 `RoutineList(onEdit)`.
  - `@Composable internal fun HomePanel(todayRoutines: List<RoutineEntity>, favorites: List<FavoriteRouteEntity>, onSearch: () -> Unit, onLocate: () -> Unit, onRoute: () -> Unit, onRoutine: (RoutineEntity) -> Unit, onAllRoutines: () -> Unit, onFavorite: (FavoriteRouteEntity) -> Unit, modifier: Modifier = Modifier)` — 위에서 아래로: 손잡이, 검색 줄(`[🔍 어디로 갈까요?] [경로 찾기]` + `[📍]` 아이콘 버튼), 오늘 루틴 줄들(`08:10 통학 · 마포 → 공덕  ›`), 자주 가는 경로 줄들(`★ 홍대입구 → 잠실  ›`), `[루틴 전체 보기]` 글자 버튼. 둘 다 없으면 `StateMessage(Empty, Copy.HOME_EMPTY_HINT, Inline)`. 목록 부분은 `heightIn(max = 0.5 * 화면 높이 - 검색 줄)`로 스크롤.
  - `LineMapContent` 새 매개변수: `todayRoutines: List<RoutineEntity>`, `onRoutine`, `onAllRoutines`. 지우는 것: `nextRoutine`, `routineList`, `panelExpanded`, `panelTab`. 위에는 설정 아이콘(오른쪽)과 출발·도착 띠만. 진행 중 알림 카드와 스낵바는 패널 바로 위(`Column(Alignment.BottomCenter) { snackbar; sessionBanner; HomePanel }`).
  - Copy: `SEARCH_BAR = "어디로 갈까요?"`(있음), `ALL_ROUTINES = "루틴 전체 보기"`, `HOME_EMPTY_HINT = "역을 검색하거나 노선도에서 역을 눌러 보세요"`, `ROUTINES_TITLE = "루틴"`.

- [ ] **Step 1: 브랜치** — `git switch -c polish2/2-home`
- [ ] **Step 2: 실패하는 테스트를 쓴다**

```kotlin
class TodayRoutinesTest {
    private val mon = DayOfWeek.MONDAY
    private fun r(id: Long, min: Int, days: Int = dayBit(mon), on: Boolean = true, viaId: String? = null) = RoutineEntity(
        id = id, name = "r$id", fromId = "마포", fromName = "마포", viaId = viaId, viaName = viaId,
        toId = "공덕", toName = "공덕", routeJson = "", days = days, minuteOfDay = min, enabled = on,
    )

    @Test fun onlyEnabledTodayNotPassedInTimeOrder() =
        assertEquals(listOf(3L, 1L), todayRoutines(listOf(r(1, 600), r(2, 400), r(3, 500), r(4, 700, on = false), r(5, 800, days = dayBit(DayOfWeek.TUESDAY))), 450, mon).map { it.id })

    @Test fun limitsToTwo() = assertEquals(2, todayRoutines((1L..5L).map { r(it, 600 + it.toInt()) }, 0, mon).size)

    @Test fun routineWithViaOpensRouteWithVia() =
        assertEquals(RouteFlowGraph(fromId = "마포", fromName = "마포", viaId = "홍대입구", viaName = "홍대입구", toId = "공덕", toName = "공덕"), r(1, 600, viaId = "홍대입구").toRouteGraph())
}
```

`AppNavGraphTest`에 `routinesDestRoundTrips`: `nav.navigate(RoutinesDest)` 뒤 현재 목적지가 `RoutinesDest`. `HomePanelTest`(Robolectric 컴포즈): `tappingRoutineCallsOnRoutine`(오늘 루틴 줄 텍스트를 눌러 콜백 1회), `allRoutinesLinkOpens`(`Copy.ALL_ROUTINES`를 눌러 콜백), `panelNeverCoversHalfTheScreen`(자주 가는 경로 10개, 914dp 창에서 패널 높이 ≤ 457dp).

- [ ] **Step 3: 실행해 실패 확인** — `./gradlew.bat :app:testDebugUnitTest --tests "*TodayRoutinesTest" --tests "*HomePanelTest" --tests "*AppNavGraphTest"` → 컴파일 실패.
- [ ] **Step 4: 구현한다**(위 Interfaces). 오늘 루틴 줄과 자주 가는 경로 줄을 누르면 `navController.navigate(routine.toRouteGraph())` / `RouteFlowGraph(favoriteRouteId = …)`.
- [ ] **Step 5: 테스트 통과 확인** — 같은 명령 → PASS.
- [ ] **Step 6: 스크린샷 갱신** — `LineMapContentScreenshotTest`를 새 매개변수로 고치고 다음을 추가: `home_panel_routines`(루틴 2 + 자주 가는 경로 2), `home_panel_empty`(둘 다 없음), `home_panel_font130`(fontScale 1.3), `home_panel_session`(진행 중 알림 카드가 패널 위). 루틴 목록 화면은 `RestScreensScreenshotTest`의 `routines_*` 유지.

Run: `./gradlew.bat :app:recordRoborazziDebug` → 실패 0.
- [ ] **Step 7: 커밋** — `feat(app): 홈을 한 손 배치로(검색·오늘 루틴·자주 가는 경로를 아래로), 루틴 목록 화면`

---

### Task 4: 화면별 손질 — 한 번에 합의 (브랜치 `polish2/3-screens`)

**정해진 것(스펙 부록 UI 항목, 합의 없이 반영):**
- 경로 상세: 지하철 단계에서 서버 안내 문구(`step.guidance`)가 새 단계 줄과 같은 내용이면 숨긴다. 함수 `fun shouldShowGuidance(step: StepDto): Boolean`(StepText.kt). 테스트 `StepPartsTest.subwayGuidanceDuplicateIsHidden`: 지하철 `lineName="1호선", stops=["시청","종각","종로3가"], guidance="1호선 (시청 > 종로3가)"` → false, 도보 `guidance="환승 통로로 1분"` → true.
- `StaleNotice`: `heightIn(min = 48.dp)`. 테스트 `StateViewsTest.staleNoticeIsTallEnoughToTap`(높이 ≥ 48dp).
- 노선도 저장본 바탕: `LineMapPictureLoader.saveSnapshot`에 `background: Int` 매개변수(지금 테마의 `background` 색)를 받아 `Color.WHITE` 대신 쓴다.
- 역 시트 시각 칩: `FlowRow`로 줄바꿈. 스크린샷 `sheet_realtime_font130`.
- 설정: 허용된 권한 줄도 줄 전체를 누르면 설정이 열린다(✓는 유지). 테스트 `SettingsContentTest.grantedRowOpensSettingsToo`.

**Steps:**
- [ ] **Step 1: 브랜치** — `git switch -c polish2/3-screens`
- [ ] **Step 2: 정해진 것을 테스트 먼저(위 테스트 RED → 구현 → GREEN)로 반영한다.**
- [ ] **Step 3: 새 기준으로 찍는다** — `./gradlew.bat :app:recordRoborazziDebug`. 역 시트, 검색, 경로 편집·결과·상세, 루틴 목록·편집, 알림 대기·탑승, 설정의 라이트 기준 이미지를 화면당 1~2장 골라 보인다.
- [ ] **Step 4: 🛑 합의 — 멈추고 기다린다.** 화면마다 "새 기준에서도 남은 어색한 점"을 2~4개씩 제안해 `AskUserQuestion`(질문당 화면 묶음, 여러 개 선택)으로 한 번에 묻는다. 고른 내용을 이 Task 아래 "합의 결과"에 적는다.
- [ ] **Step 5: 반영한다.** 동작이 바뀌는 항목은 테스트를 먼저 쓴다. 모양만 바뀌면 스크린샷으로 확인한다.
- [ ] **Step 6: 전체** — `./gradlew.bat :app:recordRoborazziDebug` → 실패 0.
- [ ] **Step 7: 커밋** — `feat(app): 화면별 손질(2차 합의)`

**합의 결과:** (Step 4에서 채운다)

---

### Task 5: 마지막 확인과 합치기

- [ ] **Step 1: 에뮬레이터** — `pixel8`에 설치하고 라이트에서 처음부터 끝까지(시작 화면 → 홈 → 아래 검색 → 역 시트 → 출발·도착 → 경로 결과 → 상세 → 오늘 루틴 줄 → 루틴 전체 보기 → 설정) 한 번, 다크(`adb shell cmd uimode night yes`)에서 홈·역 시트·상세를 한 번. 캡처를 사용자에게 보인다.
- [ ] **Step 2: 최종 검토자** — 전체 범위(`develop..HEAD`) 검토, Review Focus 5개를 명시해 맡긴다. 중요 이상은 테스트로 재현 후 고친다.
- [ ] **Step 3: 스펙 부록에 화면별 결과를 짧게 적고, 이 계획 체크박스를 실제와 맞춘다.**
- [ ] **Step 4: 🛑 사용자 확인 후** `git switch develop && git merge --no-ff polish2/3-screens`.
