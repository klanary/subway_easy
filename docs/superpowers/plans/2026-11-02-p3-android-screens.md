# P3. 앱 화면과 데이터 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스펙 4장·7장의 화면(홈, 경로 편집, 통합 검색, 지도 핀 선택, 경로 결과·상세, 역 상세, 설정)과 데이터 계층을 만들어, 서버로 경로를 찾고 역 실시간 도착을 보는 앱을 완성한다. (통학 세션·경유지·자주 가는 경로는 P4)

**Architecture:** 단일 Activity와 Navigation Compose. 화면마다 ViewModel이 `StateFlow`로 상태를 내보내고 데이터 화면은 `UiState` 다섯 상태를 쓴다. 저장소(repository)는 인터페이스와 `Default…` 구현으로 나눠 Retrofit·Room·DataStore를 감싸고, `AppContainer`가 손으로 연결한다. 서버 DTO를 앱 모델로 그대로 쓴다(서버가 이미 앱 전용 형식을 주므로 변환 계층을 두지 않는다). 지도는 카카오 SDK의 View를 `AndroidView`로 감싸고, 경로선·마커 계산은 SDK와 떨어진 순수 함수로 둔다.

**Tech Stack:** Kotlin, Jetpack Compose(Material 3), Navigation Compose(타입 안전 경로), ViewModel + StateFlow, Retrofit + OkHttp + kotlinx.serialization, Room(KSP), DataStore Preferences, 카카오 지도 SDK v2, FusedLocationProviderClient, JUnit4, kotlinx-coroutines-test, Robolectric

**Spec:** `docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md` (3.1 ①②③④, 4장, 5.4, 7장, 9.2)

**기간:** 11/9 ~ 11/15 · **선행:** P1(앱 뼈대·지도 실험·CI), P2 배포(공개 주소를 `local.properties`의 `server.baseUrl`에) · **점검일 11/15:** 끝나지 않은 ③·④는 3.4절 최소 버전(③ 주소 생략, ④ 단색 경로선)으로 마무리하고 README "알려진 한계"에 적는다.

**브랜치:** `feat/app-data`(Task 1~4) → `feat/app-search`(5~7) → `feat/app-routes`(8~9) → `feat/app-station-home`(10~12)

**경로 표기:** `<pkg>/` = `android/app/src/main/java/io/github/klanary/jigeumchulbal/`, `<test>/` = `android/app/src/test/java/io/github/klanary/jigeumchulbal/`

## Global Constraints

- 패키지 `io.github.klanary.jigeumchulbal`, minSdk 26. 앱 명령은 `android/`에서, git 명령은 저장소 루트에서 실행한다.
- 앱은 서버 형식(6.2)만 안다. DTO 필드 이름은 6.2의 JSON 키와 같다.
- 모든 데이터 화면은 `UiState` 다섯 상태(로딩, 내용, 빈 결과, 오류 + [다시 시도], 오프라인)를 가진다. `stale: true`면 내용을 흐리게(알파 0.5) 하고 "갱신 지연"을 붙인다. (4.5)
- 권한은 처음 쓸 때 요청한다. 위치는 "현재 위치"를 처음 쓸 때, 앱을 쓰는 동안만 허용으로 충분하다. 첫 실행에 한꺼번에 요청하지 않는다. (4.4)
- 내 장소·즐겨찾기·최근 검색은 기기(Room)에만 둔다. (5.4)
- 지도 초기화에 실패해도 목록과 단계 안내는 동작하고, 지도 자리에 "지도를 불러오지 못했어요"를 보여준다. (4.5, 7.3)
- 자동 갱신(역 상세 30초)은 화면이 보이는 동안만 한다: 화면이 `repeatOnLifecycle(Lifecycle.State.STARTED)` 안에서 ViewModel의 `runAutoRefresh()`를 부른다. (7.1)
- 사용자에게 보이는 문구는 `<pkg>/ui/common/Copy.kt`의 상수로 모은다(다국어는 범위 밖 — 3.3, ViewModel 테스트에서 그대로 비교). 서버 오류 문구는 서버 `message`를 그대로 보여준다.
- ViewModel 테스트는 `runTest`와 `MainDispatcherRule`(Main을 `StandardTestDispatcher`로 바꿈)로 가상 시간을 쓴다. ViewModel은 저장소 인터페이스에만 의존하고, 테스트는 `<test>/fakes/Fakes.kt`의 가짜를 쓴다.
- 작업마다 커밋에 학습 노트 초안 `docs/learning/NN-주제.md`(12절 형식)를 넣는다.

## Review Focus

1. Render 서버가 잠들어 첫 요청이 1분 가까이 걸림 → "오프라인"으로 보이지 않고 로딩을 유지하다, 시간이 넘으면 "서버에 연결하지 못했어요" 오류. (Task 1 `ioExceptionWhileOnlineIsNetworkError`, OkHttp 읽기 제한 65초)
2. 검색어를 빠르게 바꿀 때 늦게 도착한 이전 검색 결과가 새 결과를 덮어씀 → 항상 마지막 검색어의 결과만 보인다. (Task 5 `staleResponseNeverOverwritesNewerQuery`)
3. 같은 역·장소를 다시 고르면 최근 검색에 중복으로 쌓임 → 한 줄만 남아 맨 위로 오고 20개를 넘지 않는다(스펙 7.2). (Task 3 `sameStationTwiceKeepsOneRowOnTop`, `keepsOnlyTwentyNewest`)
4. 위치 권한은 있지만 위치를 얻지 못함(실내·에뮬레이터: 5초 안에 응답 없고 마지막 위치도 없음) → 출발 칸이 빈 칸과 안내로 남고 멈추지 않는다. (Task 4 `bothNullGivesNull`, Task 6 `originEmptyWhenLocationUnavailable`)
5. 코루틴 취소를 오류로 잡아 화면을 떠날 때 오류가 뜨거나 자동 갱신이 멈추지 않음 → 취소는 그대로 전파된다. (Task 1 `cancellationIsRethrown`, Task 10 `autoRefreshEvery30Seconds`)

---

### Task 1: 서버 통신 계층

**Files:**
- Create: `<pkg>/data/model/GeoPoint.kt`, `<pkg>/data/model/Endpoint.kt`, `<pkg>/data/remote/Dtos.kt`, `<pkg>/data/remote/ApiService.kt`, `<pkg>/data/remote/ApiResult.kt`, `<pkg>/data/remote/NetworkMonitor.kt`, `<pkg>/ui/common/Copy.kt`, `<pkg>/data/repository/StationRepository.kt`, `<pkg>/data/repository/PlaceRepository.kt`, `<pkg>/data/repository/RouteRepository.kt`, `<pkg>/data/repository/ServerRepository.kt`, `<pkg>/di/AppContainer.kt`, `<test>/MainDispatcherRule.kt`, `<test>/fixtures/SpecSamples.kt`, `<test>/data/remote/DtoDecodingTest.kt`, `<test>/data/remote/ApiResultTest.kt`, `<test>/data/repository/RouteRepositoryTest.kt`
- Modify: `android/app/build.gradle.kts`(retrofit, converter, okhttp, logging-interceptor, kotlinx-serialization-json, coroutines-test), `AndroidManifest.xml`(`ACCESS_NETWORK_STATE`), `<pkg>/App.kt`(`lateinit var container: AppContainer`를 `onCreate`에서 생성)

**Interfaces:**
- Produces (`data/model`): `@Serializable data class GeoPoint(val lat: Double, val lng: Double) { fun toQuery(): String = "$lat,$lng" }`; `@Serializable enum class EndpointKind { CURRENT_LOCATION, STATION, PLACE, PIN, MY_PLACE }`; `@Serializable data class Endpoint(val label: String, val point: GeoPoint, val kind: EndpointKind, val stationId: String? = null)`
- Produces (`Dtos.kt`, 모두 `@Serializable`, 이름은 6.2 그대로): `StationDto(id, name, lines: List<String>, lat, lng)`, `StationSearchDto(items)`, `TrainDto(destination: String? = null, remainingSec: Int? = null, message: String = "", isLast: Boolean = false, isExpress: Boolean = false)`, `ArrivalGroupDto(line, direction, towards: String? = null, trains: List<TrainDto>)`, `ArrivalsDto(stationId, updatedAt: String, stale: Boolean = false, groups)`, `PlaceDto(name, address: String? = null, lat, lng, category: String? = null)`, `PlaceSearchDto(items)`, `NearestStationDto(id, name, distanceM: Int)`, `ReverseDto(address: String? = null, nearestStation: NearestStationDto? = null)`, `enum class StepMode { WALK, SUBWAY, BUS }`, `StepDto(mode, durationSec, distanceM, guidance = "", path: List<List<Double>> = emptyList(), lineName: String? = null, routeName: String? = null, stops: List<String>? = null, boardStationId: String? = null, alightStationId: String? = null)`, `LegDto(steps)`, `FirstBoardingDto(stationId: String? = null, lineName, towards: String? = null, secToBoarding: Int, allWalkBeforeBoarding: Boolean, nextTrains: List<TrainDto>? = null)`, `RouteDto(id, type, totalSec, distanceM, transfers, fare: Int? = null, legs, firstBoarding: FirstBoardingDto? = null)`, `RoutesDto(updatedAt, stale = false, routes)`, `ErrorBodyDto(error: ErrorDto)`, `ErrorDto(code, reason: String? = null, message)`
- Produces (`ApiService.kt`, Retrofit): `health(): Map<String, String>`(`GET health`), `searchStations(q, limit = 20): StationSearchDto`, `arrivals(@Path("id") id, line: String?, towards: String?): ArrivalsDto`, `searchPlaces(q, lat: Double?, lng: Double?): PlaceSearchDto`, `reverse(lat, lng): ReverseDto`, `routes(from: String, to: String, via: String?, fromName: String?, toName: String?, viaName: String?): RoutesDto` — 모두 `suspend`
- Produces (`ApiResult.kt`): `sealed interface ApiError { data object Offline; data object Network; data class Server(val code: String, val reason: String?, val message: String); data class Unexpected(val cause: Throwable) }`, `sealed interface ApiResult<out T> { data class Ok<T>(val value: T); data class Err(val error: ApiError) }`, `suspend fun <T> safeApiCall(json: Json, network: NetworkMonitor, block: suspend () -> T): ApiResult<T>` — `CancellationException`은 다시 던진다. `HttpException`은 본문을 `ErrorBodyDto`로 읽어 `Server`, 읽지 못하면 `Server("HTTP_{상태}", null, Copy.SERVER_PROBLEM)`. `IOException`은 `network.isOnline()`이 false면 `Offline`, 아니면 `Network`. 그 밖은 `Unexpected`.
- Produces: `fun interface NetworkMonitor { fun isOnline(): Boolean }`, `ConnectivityNetworkMonitor(context)`(현재 네트워크가 `NET_CAPABILITY_INTERNET`을 가짐), `class ApiCaller(json: Json, network: NetworkMonitor) { suspend operator fun <T> invoke(block: suspend () -> T): ApiResult<T> }`(`safeApiCall`을 감싸 모든 `Default…` 저장소가 생성자로 받는다)
- Produces (저장소 — 인터페이스와 `Default…` 구현을 같은 파일에): `StationRepository { suspend fun search(q: String, limit: Int = 20): ApiResult<List<StationDto>>; suspend fun arrivals(stationId: String, line: String? = null, towards: String? = null): ApiResult<ArrivalsDto> }`, `PlaceRepository { suspend fun search(q: String, near: GeoPoint?): ApiResult<List<PlaceDto>>; suspend fun reverse(point: GeoPoint): ApiResult<ReverseDto> }`, `RouteRepository { suspend fun routes(origin: Endpoint, dest: Endpoint, via: Endpoint? = null): ApiResult<RoutesDto> }`(이름 파라미터는 `label`, 단 `CURRENT_LOCATION`이면 보내지 않음), `ServerRepository { suspend fun wake(): Boolean }`
- Produces (`AppContainer(context)`): `json = Json { ignoreUnknownKeys = true }`, OkHttp(연결 15초, 읽기 65초 — Render가 깨어나는 시간, 쓰기 15초, 디버그 빌드만 `HttpLoggingInterceptor` BASIC), Retrofit(`BuildConfig.SERVER_BASE_URL`, kotlinx 변환기), 위 저장소들의 `Default…` 인스턴스
- Produces (`Copy.kt`, `object Copy`): 이 작업에서는 `SERVER_PROBLEM = "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요."`만 둔다. 이후 작업이 상수를 더한다.
- Produces (테스트 공용): `MainDispatcherRule`, `SpecSamples.kt`의 `SPEC_ROUTES_JSON`, `SPEC_ARRIVALS_JSON`(스펙 6.2 예시 JSON을 그대로 붙인 문자열)과 `specRoute(): RouteDto`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class DtoDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun routesExampleFromSpecDecodes() {
        val route = json.decodeFromString<RoutesDto>(SPEC_ROUTES_JSON).routes.single()
        assertEquals(1980, route.totalSec)
        assertEquals(StepMode.SUBWAY, route.legs[0].steps[1].mode)
        assertEquals(listOf(37.5550, 126.9200), route.legs[0].steps[0].path[0])
        assertEquals(180, route.firstBoarding!!.nextTrains!![0].remainingSec)
    }

    @Test fun arrivalsExampleFromSpecDecodes() {
        val group = json.decodeFromString<ArrivalsDto>(SPEC_ARRIVALS_JSON).groups.single()
        assertEquals("내선", group.direction)
        assertEquals(2, group.trains.size)
    }

    @Test fun nullsAndUnknownFieldsDecode() {
        val dto = json.decodeFromString<ReverseDto>("""{"address":null,"nearestStation":null,"extra":1}""")
        assertNull(dto.address)
        assertNull(dto.nearestStation)
    }
}

class ApiResultTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val online = NetworkMonitor { true }
    private val offline = NetworkMonitor { false }
    private fun httpError(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test fun serverErrorBodyBecomesServerError() = runTest {
        val body = """{"error":{"code":"NO_ROUTE","reason":"NO_START_STOP","message":"출발지 근처에서 탈 수 있는 대중교통이 없어요."}}"""
        assertEquals(
            ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_START_STOP", "출발지 근처에서 탈 수 있는 대중교통이 없어요.")),
            safeApiCall(json, online) { throw httpError(404, body) })
    }

    @Test fun nonJsonErrorBodyGetsGenericMessage() = runTest {
        assertEquals(ApiResult.Err(ApiError.Server("HTTP_502", null, Copy.SERVER_PROBLEM)),
            safeApiCall(json, online) { throw httpError(502, "<html>") })
    }

    @Test fun ioExceptionWhileOfflineIsOffline() = runTest {
        assertEquals(ApiResult.Err(ApiError.Offline), safeApiCall(json, offline) { throw IOException() })
    }

    @Test fun ioExceptionWhileOnlineIsNetworkError() = runTest {
        assertEquals(ApiResult.Err(ApiError.Network), safeApiCall(json, online) { throw SocketTimeoutException() })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() = runTest {
        safeApiCall(json, online) { throw CancellationException("화면을 떠남") }
    }

    @Test fun queryUsesDotDecimalInAnyLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try { assertEquals("37.5571,126.9245", GeoPoint(37.5571, 126.9245).toQuery()) }
        finally { Locale.setDefault(saved) }
    }
}

class RouteRepositoryTest {
    @Test fun currentLocationSendsNoNameButOthersDo() = runTest {
        val api = RecordingApiService()      // 마지막 routes 인자를 기록하는 테스트용 ApiService
        DefaultRouteRepository(api, ApiCaller(Json, NetworkMonitor { true })).routes(
            Endpoint("현재 위치", GeoPoint(37.5571, 126.9245), EndpointKind.CURRENT_LOCATION),
            Endpoint("강남", GeoPoint(37.4979, 127.0276), EndpointKind.STATION, "강남"))
        assertEquals(RoutesArgs("37.5571,126.9245", "37.4979,127.0276", null, null, "강남", null), api.lastRoutes)
    }
}
```

(`ApiCaller(json, network)`는 `safeApiCall`을 감싼 작은 클래스로 저장소들이 공유한다.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL (컴파일 오류)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 서버 통신 계층과 오류 분류"`

---

### Task 2: 화면 상태, 표시 형식, 내비게이션 뼈대

**Files:**
- Create: `<pkg>/ui/common/UiState.kt`, `<pkg>/ui/common/StateContainer.kt`, `<pkg>/ui/common/Format.kt`, `<pkg>/ui/common/AppViewModel.kt`, `<pkg>/ui/theme/Theme.kt`, `<pkg>/ui/Destinations.kt`, `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/PickResult.kt`, `<test>/ui/common/UiStateTest.kt`, `<test>/ui/common/FormatTest.kt`
- Modify: `<pkg>/ui/common/Copy.kt`, `<pkg>/MainActivity.kt`(`setContent { AppTheme { AppNavHost() } }`), `android/app/build.gradle.kts`(navigation-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose)

**Interfaces:**
- Consumes: Task 1 `ApiResult`, `ApiError`, `Endpoint`
- Produces (`UiState.kt`): `sealed interface UiState<out T> { data object Loading; data class Content<T>(val data: T, val stale: Boolean = false); data object Empty; data class Error(val message: String); data object Offline }`, `fun <T> ApiResult<T>.toUiState(isEmpty: (T) -> Boolean = { false }, isStale: (T) -> Boolean = { false }): UiState<T>` — `Offline` → `Offline`, `Server` → `Error(message)`, `Network` → `Error(Copy.NETWORK_ERROR)`, `Unexpected` → `Error(Copy.UNEXPECTED_ERROR)`
- Produces: `@Composable fun <T> StateContainer(state: UiState<T>, onRetry: () -> Unit, emptyText: String, modifier: Modifier = Modifier, content: @Composable (T) -> Unit)` — 로딩 표시, 빈 결과 문구, 오류 문구 + [다시 시도], 오프라인 문구 + [다시 시도], `stale`이면 알파 0.5와 "갱신 지연" 칩
- Produces (`Format.kt`): `formatDuration(sec: Int): String`(분 = 올림, 최소 1분), `formatRemaining(sec: Int?): String`, `formatFare(fare: Int?): String`, `formatUpdatedAgo(sec: Long): String`, `formatDistance(m: Int): String`
- Produces (`Copy.kt`, 이번 작업분): `OFFLINE = "인터넷에 연결되어 있지 않아요."`, `NETWORK_ERROR = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."`, `UNEXPECTED_ERROR = "문제가 생겼어요. 다시 시도해 주세요."`, `SERVER_PROBLEM`(Task 1), `RETRY = "다시 시도"`, `STALE = "갱신 지연"`, `MAP_FAILED = "지도를 불러오지 못했어요"`, `NO_INFO = "정보 없음"`, `ARRIVING = "곧 도착"`. 이후 작업이 상수를 더한다.
- Produces (`Destinations.kt`, 모두 `@Serializable`): `object HomeDest`, `data class RouteFlowGraph(val destMyPlaceId: Long? = null, val favoriteRouteId: Long? = null)`, `object RouteEditDest`, `object RoutesDest`, `data class RouteDetailDest(val routeId: String)`, `enum class Slot { ORIGIN, VIA, DEST }`, `data class SearchDest(val slot: Slot? = null)`, `data class MapPickDest(val slot: Slot? = null)`(slot이 없으면 하나만 고르는 모드 — 설정의 내 장소용), `data class StationDest(val stationId: String)`, `object SettingsDest`
- Produces (`PickResult.kt`): `@Serializable data class PickResult(val slot: Slot?, val endpoint: Endpoint)`, `fun NavController.returnPick(result: PickResult)`(이전 화면의 `savedStateHandle["pick_result"]`에 JSON으로 넣고 뒤로), `@Composable fun NavBackStackEntry.OnPickResult(onResult: (PickResult) -> Unit)`(꺼낸 뒤 지움)
- Produces: `@Composable inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM`(`LocalContext`의 `App`에서 컨테이너를 꺼내는 `viewModelFactory`)
- Produces: `AppNavHost` — 위 목적지를 모두 등록하고, 아직 없는 화면은 제목만 있는 자리 표시 화면으로 둔다(각 작업이 바꾼다). `RouteFlowGraph`는 `RouteEditDest`에서 시작하는 중첩 그래프다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class UiStateTest {
    @Test fun okEmptyIsEmpty() =
        assertEquals(UiState.Empty, ApiResult.Ok(emptyList<Int>()).toUiState(isEmpty = { it.isEmpty() }))
    @Test fun okStaleIsStaleContent() =
        assertEquals(UiState.Content(listOf(1), stale = true), ApiResult.Ok(listOf(1)).toUiState(isStale = { true }))
    @Test fun offlineMapsToOffline() =
        assertEquals(UiState.Offline, ApiResult.Err(ApiError.Offline).toUiState<Int>())
    @Test fun serverMessageShownAsIs() =
        assertEquals(UiState.Error("대중교통 경로를 찾지 못했어요."),
            ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_RESULTS", "대중교통 경로를 찾지 못했어요.")).toUiState<Int>())
    @Test fun networkErrorUsesCopy() =
        assertEquals(UiState.Error(Copy.NETWORK_ERROR), ApiResult.Err(ApiError.Network).toUiState<Int>())
}

class FormatTest {
    @Test fun duration() {
        assertEquals("33분", formatDuration(1980)); assertEquals("1시간 5분", formatDuration(3900))
        assertEquals("1시간", formatDuration(3600)); assertEquals("1분", formatDuration(59))
    }
    @Test fun remaining() {
        assertEquals("정보 없음", formatRemaining(null)); assertEquals("곧 도착", formatRemaining(45))
        assertEquals("3분", formatRemaining(180)); assertEquals("1분", formatRemaining(119))
    }
    @Test fun fareAndDistanceAndAgo() {
        assertEquals("1,550원", formatFare(1550)); assertEquals("요금 정보 없음", formatFare(null))
        assertEquals("320m", formatDistance(320)); assertEquals("1.2km", formatDistance(1234))
        assertEquals("5초 전 갱신", formatUpdatedAgo(5)); assertEquals("1분 전 갱신", formatUpdatedAgo(75))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL (컴파일 오류)
- [ ] **Step 3: 구현** — 위 Interfaces 그대로. `MainActivity`는 더 이상 `MapExperiment`를 쓰지 않는다(파일은 Task 7에서 지운다).
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`. 실기기에서 홈 자리 표시 화면이 뜬다.
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 화면 상태 모델, 표시 형식, 내비게이션 뼈대"`

---

### Task 3: 기기 저장소 (Room, DataStore)

**Files:**
- Create: `<pkg>/data/local/Entities.kt`, `<pkg>/data/local/AppDatabase.kt`, `<pkg>/data/local/MyPlaceDao.kt`, `<pkg>/data/local/FavoriteStationDao.kt`, `<pkg>/data/local/FavoriteRouteDao.kt`, `<pkg>/data/local/RecentSearchDao.kt`, `<pkg>/data/local/SettingsStore.kt`, `<pkg>/data/repository/MyPlaceRepository.kt`, `<pkg>/data/repository/FavoriteRepository.kt`, `<pkg>/data/repository/RecentSearchRepository.kt`, `<test>/data/local/RecentSearchDaoTest.kt`, `<test>/data/local/MyPlaceDaoTest.kt`, `<test>/data/local/FavoriteDaoTest.kt`, `<test>/data/local/SettingsStoreTest.kt`
- Modify: `android/app/build.gradle.kts`(room-runtime, room-ktx, room-compiler(ksp), datastore-preferences, robolectric, androidx-test-core, `testOptions.unitTests.isIncludeAndroidResources = true`), `<pkg>/di/AppContainer.kt`

**Interfaces:**
- Produces (`Entities.kt`, 테이블 이름은 7.2 그대로):
  - `enum class MyPlaceType { HOME, SCHOOL, WORK, CUSTOM }`, `@Entity("my_places") MyPlaceEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val type: MyPlaceType, val label: String, val lat: Double, val lng: Double, val address: String?, val stationId: String?)`
  - `@Entity("favorite_stations") FavoriteStationEntity(@PrimaryKey val stationId: String, val line: String? = null, val towards: String? = null, val createdAt: Long)`
  - `@Entity("favorite_routes") FavoriteRouteEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val originLabel: String, val originLat: Double, val originLng: Double, val viaLabel: String?, val viaLat: Double?, val viaLng: Double?, val destLabel: String, val destLat: Double, val destLng: Double, val createdAt: Long)`
  - `@Entity("route_usages", foreignKeys = [ForeignKey(FavoriteRouteEntity::class, ["id"], ["routeId"], onDelete = ForeignKey.CASCADE)], indices = [Index("routeId")]) RouteUsageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val routeId: Long, val usedAt: Long)`
  - `enum class RecentKind { STATION, PLACE, PIN }`, `@Entity("recent_searches", indices = [Index(value = ["dedupeKey"], unique = true)]) RecentSearchEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: RecentKind, val label: String, val lat: Double, val lng: Double, val stationId: String?, val usedAt: Long, val dedupeKey: String)`
  - `fun recentDedupeKey(kind: RecentKind, label: String, lat: Double, lng: Double, stationId: String?): String` — STATION `"S:$stationId"`, PLACE `"P:$label@%.5f,%.5f"`, PIN `"M:%.5f,%.5f"`(`Locale.US`)
- Produces: `AppDatabase`(버전 1, 다섯 테이블 모두 — P4가 `favorite_routes`·`route_usages`를 마이그레이션 없이 쓴다)
- Produces (DAO):
  - `MyPlaceDao`: `observeAll(): Flow<List<MyPlaceEntity>>`(HOME, SCHOOL, WORK, CUSTOM 순, 같은 종류는 id순), `@Transaction suspend fun saveTyped(place: MyPlaceEntity)`(CUSTOM이 아니면 같은 종류를 지우고 넣음), `suspend fun delete(id: Long)`, `suspend fun get(id: Long): MyPlaceEntity?`
  - `FavoriteStationDao`: `observeAll()`(createdAt순), `observeIsFavorite(stationId): Flow<Boolean>`, `insert(entity)`, `delete(stationId)`
  - `FavoriteRouteDao`: `observeAll(): Flow<List<FavoriteRouteEntity>>`, `insert(route): Long`, `delete(id: Long)`, `insertUsage(usage: RouteUsageEntity)`, `usagesSince(sinceMillis: Long): List<RouteUsageEntity>`, `observeUsagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>>`
  - `RecentSearchDao`: `observeRecent(): Flow<List<RecentSearchEntity>>`(usedAt 내림차순 20개), `@Transaction suspend fun record(entry: RecentSearchEntity)`(같은 `dedupeKey`는 바꿔 넣고, 20개를 넘는 오래된 행은 지움), `suspend fun count(): Int`
- Produces: `data class AppSettings(val departMarginSec: Int = 60, val alightLeadSec: Int = 120, val stationLeadSec: Int = 180, val maxWaitMin: Int = 30, val permissionIntroShown: Boolean = false)`(8.6 기본값, 마지막 값은 P4가 씀), `class SettingsStore(dataStore: DataStore<Preferences>) { val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings) }`
- Produces (저장소 인터페이스 + `Default…`): `MyPlaceRepository { val places: Flow<List<MyPlaceEntity>>; suspend fun save(type: MyPlaceType, label: String, endpoint: Endpoint, address: String?); suspend fun delete(id: Long); suspend fun get(id: Long): MyPlaceEntity? }`, `FavoriteRepository { val stations: Flow<List<FavoriteStationEntity>>; fun isFavorite(stationId: String): Flow<Boolean>; suspend fun toggleStation(stationId: String) }`(P4가 경로 함수를 더함), `RecentSearchRepository { val recent: Flow<List<RecentSearchEntity>>; suspend fun record(endpoint: Endpoint) }`(`CURRENT_LOCATION`은 기록하지 않음, `MY_PLACE`와 `PLACE`는 PLACE로)

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@RunWith(RobolectricTestRunner::class)
class RecentSearchDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()
    private fun station(id: String, at: Long) = RecentSearchEntity(kind = RecentKind.STATION, label = id,
        lat = 37.5, lng = 127.0, stationId = id, usedAt = at,
        dedupeKey = recentDedupeKey(RecentKind.STATION, id, 37.5, 127.0, id))

    @Test fun keepsOnlyTwentyNewest() = runTest {
        repeat(25) { db.recentSearchDao().record(station("역$it", it.toLong())) }
        assertEquals(20, db.recentSearchDao().count())
        assertEquals("역24", db.recentSearchDao().observeRecent().first().first().label)
    }

    @Test fun sameStationTwiceKeepsOneRowOnTop() = runTest {
        listOf("홍대입구" to 1L, "합정" to 2L, "홍대입구" to 3L).forEach { (id, at) -> db.recentSearchDao().record(station(id, at)) }
        assertEquals(listOf("홍대입구", "합정"), db.recentSearchDao().observeRecent().first().map { it.label })
    }
}

// MyPlaceDaoTest (같은 준비 코드)
@Test fun savingHomeTwiceReplacesIt() = runTest {
    dao.saveTyped(place(MyPlaceType.HOME, "집", 37.1)); dao.saveTyped(place(MyPlaceType.HOME, "집", 37.2))
    assertEquals(listOf(37.2), dao.observeAll().first().filter { it.type == MyPlaceType.HOME }.map { it.lat })
}
@Test fun customPlacesAccumulateAfterTypedOnes() = runTest {
    dao.saveTyped(place(MyPlaceType.CUSTOM, "도서관", 37.3)); dao.saveTyped(place(MyPlaceType.CUSTOM, "학원", 37.4))
    dao.saveTyped(place(MyPlaceType.SCHOOL, "학교", 37.5))
    assertEquals(listOf("학교", "도서관", "학원"), dao.observeAll().first().map { it.label })
}

// FavoriteDaoTest
@Test fun stationFavoriteToggles() = runTest { /* insert → observeIsFavorite true, delete → false */ }
@Test fun deletingRouteDeletesItsUsages() = runTest {
    val id = dao.insert(route()); dao.insertUsage(RouteUsageEntity(routeId = id, usedAt = 1)); dao.delete(id)
    assertTrue(dao.usagesSince(0).isEmpty())
}

// SettingsStoreTest (임시 파일의 PreferenceDataStoreFactory)
@Test fun defaultsMatchSpec() = runTest { assertEquals(AppSettings(60, 120, 180, 30, false), store.settings.first()) }
@Test fun updatePersists() = runTest {
    store.update { it.copy(alightLeadSec = 180) }
    assertEquals(180, store.settings.first().alightLeadSec)
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL (컴파일 오류)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 내 장소·즐겨찾기·최근 검색 저장소와 설정 저장"`

---

### Task 4: 현재 위치

**Files:**
- Create: `<pkg>/location/LocationProvider.kt`, `<pkg>/location/LocationPermission.kt`, `<test>/location/CurrentOrLastTest.kt`
- Modify: `AndroidManifest.xml`(`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`), `android/app/build.gradle.kts`(play-services-location, kotlinx-coroutines-play-services), `<pkg>/di/AppContainer.kt`

**Interfaces:**
- Produces: `interface LocationProvider { fun hasPermission(): Boolean; suspend fun current(): GeoPoint? }`, `class FusedLocationProvider(context: Context) : LocationProvider` — 권한이 없으면 `null`. 있으면 `currentOrLast(current = { getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 취소 토큰).await() }, last = { lastLocation.await() })` (7.4)
- Produces: `suspend fun currentOrLast(current: suspend () -> GeoPoint?, last: suspend () -> GeoPoint?, timeoutMs: Long = 5_000): GeoPoint?` — `withTimeoutOrNull(timeoutMs) { current() } ?: last()`
- Produces: `@Composable fun rememberLocationPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit` — `RequestMultiplePermissions`로 COARSE·FINE을 요청, 둘 중 하나라도 허용이면 true

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class CurrentOrLastTest {
    private val here = GeoPoint(37.5571, 126.9245)
    private val before = GeoPoint(37.0, 127.0)

    @Test fun currentWithinTimeoutWins() = runTest { assertEquals(here, currentOrLast({ delay(4_000); here }, { before })) }
    @Test fun slowCurrentFallsBackToLast() = runTest { assertEquals(before, currentOrLast({ delay(6_000); here }, { before })) }
    @Test fun nullCurrentFallsBackToLast() = runTest { assertEquals(before, currentOrLast({ null }, { before })) }
    @Test fun bothNullGivesNull() = runTest { assertNull(currentOrLast({ null }, { null })) }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 현재 위치(5초 제한, 마지막 위치 대체)"`

---

### Task 5: 통합 검색 화면

**Files:**
- Create: `<pkg>/ui/search/SearchViewModel.kt`, `<pkg>/ui/search/SearchScreen.kt`, `<test>/fakes/Fakes.kt`, `<test>/ui/search/SearchViewModelTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Consumes: Task 1 `StationRepository`·`PlaceRepository`, Task 3 `MyPlaceRepository`·`RecentSearchRepository`, Task 4 `LocationProvider`, Task 2 `PickResult`·`Slot`·`UiState`
- Produces: `class SearchViewModel(slot: Slot?, stations: StationRepository, places: PlaceRepository, myPlaces: MyPlaceRepository, recents: RecentSearchRepository, location: LocationProvider) : ViewModel` — `val state: StateFlow<SearchUiState>`, `fun onQueryChange(q: String)`, `suspend fun select(endpoint: Endpoint): PickResult`(최근 검색 기록 후 `PickResult(slot, endpoint)`)
- Produces: `data class SearchUiState(val query: String = "", val myPlaces: List<MyPlaceEntity> = emptyList(), val recents: List<RecentSearchEntity> = emptyList(), val stations: UiState<List<StationDto>> = UiState.Empty, val places: UiState<List<PlaceDto>> = UiState.Empty)`
  - 검색어가 비면 내 장소·최근 검색 전부, 역·장소는 `Empty`이고 서버를 부르지 않는다.
  - 검색어가 있으면 300ms 디바운스 뒤 역 검색과 장소 검색을 동시에 하고(장소는 위치 권한이 있으면 `near = location.current()`), 새 검색어가 오면 이전 검색을 취소한다(`flatMapLatest` — 늦게 온 이전 결과가 덮어쓰지 않게). 내 장소·최근 검색은 `label`에 검색어가 든 것만 보인다.
  - 역·장소는 각자 `UiState`를 가져 한쪽 실패가 다른 쪽을 가리지 않는다.
- Produces (`SearchScreen`): 자동 포커스 검색창, 구역 순서 내 장소 / 최근 검색 / 역 / 장소, 역 행의 오른쪽 [실시간] 버튼 → `StationDest(id)`, "지도에서 선택" → `MapPickDest(slot)`, 항목을 고르면 `navController.returnPick(...)`. Endpoint 변환: 역 → `Endpoint(name, GeoPoint(lat, lng), STATION, id)`, 장소 → `PLACE`, 내 장소 → `MY_PLACE`(stationId 유지), 최근 검색 → 종류대로.
- Produces (`Fakes.kt`): `FakeStationRepository`, `FakePlaceRepository`(검색어별 지연 `delays: Map<String, Long>`, 호출 기록), `FakeMyPlaceRepository`, `FakeRecentSearchRepository`(`recorded`), `FakeLocationProvider(permission, point)` — 이후 작업이 더한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stations = FakeStationRepository()
    private val places = FakePlaceRepository()
    private val recents = FakeRecentSearchRepository()
    private val here = GeoPoint(37.5571, 126.9245)
    private fun vm(location: LocationProvider = FakeLocationProvider(true, here)) =
        SearchViewModel(Slot.DEST, stations, places, FakeMyPlaceRepository(), recents, location)

    @Test fun blankQueryDoesNotCallServer() = runTest {
        val vm = vm(); vm.onQueryChange("  "); advanceUntilIdle()
        assertTrue(stations.searchCalls.isEmpty() && places.searchCalls.isEmpty())
    }

    @Test fun typingIsDebouncedTo300ms() = runTest {
        val vm = vm(); vm.onQueryChange("ㅎㄷ"); advanceTimeBy(100); vm.onQueryChange("ㅎㄷㅇㄱ"); advanceUntilIdle()
        assertEquals(listOf("ㅎㄷㅇㄱ"), stations.searchCalls)
    }

    @Test fun staleResponseNeverOverwritesNewerQuery() = runTest {
        places.delays = mapOf("홍" to 2_000L)
        val vm = vm(); vm.onQueryChange("홍"); advanceTimeBy(301); vm.onQueryChange("홍대"); advanceUntilIdle()
        assertEquals(listOf("홍대 결과"), (vm.state.value.places as UiState.Content).data.map { it.name })
    }

    @Test fun placeSearchUsesCurrentLocationWhenPermitted() = runTest {
        val vm = vm(); vm.onQueryChange("카페"); advanceUntilIdle()
        assertEquals(here, places.lastNear)
    }

    @Test fun stationErrorDoesNotHidePlaces() = runTest {
        stations.result = ApiResult.Err(ApiError.Network)
        val vm = vm(); vm.onQueryChange("홍대"); advanceUntilIdle()
        assertTrue(vm.state.value.stations is UiState.Error && vm.state.value.places is UiState.Content)
    }

    @Test fun selectingRecordsRecentAndReturnsPick() = runTest {
        val endpoint = Endpoint("홍대입구", here, EndpointKind.STATION, "홍대입구")
        assertEquals(PickResult(Slot.DEST, endpoint), vm().select(endpoint))
        assertEquals(listOf(endpoint), recents.recorded)
    }
}
```

(`FakePlaceRepository`는 검색어 q에 대해 `PlaceDto("$q 결과", …)` 하나를 돌려준다.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`. 실기기에서 "ㅎㄷㅇㄱ" 입력 시 역 구역 첫 줄에 홍대입구.
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 역·장소 통합 검색 화면"`

---

### Task 6: 경로 편집과 경로 흐름 상태

**Files:**
- Create: `<pkg>/ui/routeedit/RouteFlowViewModel.kt`, `<pkg>/ui/routeedit/RouteEditScreen.kt`, `<test>/ui/routeedit/RouteFlowViewModelTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`(중첩 그래프 `RouteFlowGraph`에서 ViewModel 공유, 선택 결과 반영), `<pkg>/ui/common/Copy.kt`, `<test>/fakes/Fakes.kt`(`FakeRouteRepository`)

**Interfaces:**
- Consumes: Task 1 `RouteRepository`, Task 3 `MyPlaceRepository`, Task 4 `LocationProvider`, Task 2 `PickResult`
- Produces: `class RouteFlowViewModel(routes: RouteRepository, myPlaces: MyPlaceRepository, location: LocationProvider, clock: () -> Long = System::currentTimeMillis) : ViewModel` — `val state: StateFlow<RouteFlowState>`, `fun init(destMyPlaceId: Long?)`(한 번만 실행), `fun useCurrentLocation()`, `fun apply(pick: PickResult)`, `fun swap()`, `fun search()`, `fun select(routeId: String)`, `fun consumeAutoOpenSearch(): Boolean`(처음 한 번만 true — 도착 칸이 비어 있으면 경로 편집에 들어오자마자 검색 화면을 연다. 검색에서 그냥 돌아와도 다시 열지 않는다)
- Produces: `data class RouteFlowState(val origin: Endpoint? = null, val dest: Endpoint? = null, val originHint: String? = null, val results: UiState<RoutesDto>? = null, val receivedAt: Long? = null, val selectedRouteId: String? = null) { val canSearch: Boolean; val selectedRoute: RouteDto? }` (`results`가 null이면 아직 검색 안 함. 경유지는 P4가 더한다)
  - 출발 기본값: 위치 권한이 있고 위치를 얻으면 `Endpoint(Copy.CURRENT_LOCATION, 위치, CURRENT_LOCATION)`, 아니면 `null`과 `originHint = Copy.ORIGIN_HINT`
  - `search()`: `results = Loading` → `routes.routes(origin, dest)` → `toUiState(isEmpty = { it.routes.isEmpty() }, isStale = { it.stale })`, `receivedAt = clock()`
- Copy: `CURRENT_LOCATION = "현재 위치"`, `ORIGIN_HINT = "출발지를 검색하거나 현재 위치를 허용해 주세요"`
- Produces (`RouteEditScreen`): 출발·도착 칸(누르면 `SearchDest(slot)`), 출발이 비었을 때 [현재 위치] 칩(→ 위치 권한 요청 → `useCurrentLocation()`), 출발↔도착 바꾸기, [검색](`canSearch`일 때만) → `search()` 후 `RoutesDest`. 홈 검색창은 `RouteFlowGraph()`로, 내 장소 칩은 `RouteFlowGraph(destMyPlaceId)`로 들어온다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class RouteFlowViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val here = GeoPoint(37.5571, 126.9245)
    private val routes = FakeRouteRepository()
    private val myPlaces = FakeMyPlaceRepository(listOf(MyPlaceEntity(7, MyPlaceType.SCHOOL, "학교", 37.4979, 127.0276, null, "강남")))
    private fun vm(location: LocationProvider) = RouteFlowViewModel(routes, myPlaces, location) { 1_000L }

    @Test fun originDefaultsToCurrentLocationWhenPermitted() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(null); advanceUntilIdle()
        assertEquals(Endpoint("현재 위치", here, EndpointKind.CURRENT_LOCATION), vm.state.value.origin)
    }

    @Test fun originEmptyWhenPermissionMissing() = runTest {
        val vm = vm(FakeLocationProvider(false, here)); vm.init(null); advanceUntilIdle()
        assertNull(vm.state.value.origin); assertEquals(Copy.ORIGIN_HINT, vm.state.value.originHint)
    }

    @Test fun originEmptyWhenLocationUnavailable() = runTest {
        val vm = vm(FakeLocationProvider(true, null)); vm.init(null); advanceUntilIdle()
        assertNull(vm.state.value.origin); assertEquals(Copy.ORIGIN_HINT, vm.state.value.originHint)
    }

    @Test fun destPrefilledFromMyPlaceAndSwap() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
        assertEquals("학교", vm.state.value.dest!!.label)
        vm.swap()
        assertEquals("학교", vm.state.value.origin!!.label); assertEquals("현재 위치", vm.state.value.dest!!.label)
    }

    @Test fun searchNeedsBothEndsAndShowsServerMessage() = runTest {
        val vm = vm(FakeLocationProvider(false, null)); vm.init(7); advanceUntilIdle()
        assertFalse(vm.state.value.canSearch)
        vm.apply(PickResult(Slot.ORIGIN, Endpoint("홍대입구", here, EndpointKind.STATION, "홍대입구")))
        routes.result = ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_RESULTS", "대중교통 경로를 찾지 못했어요."))
        vm.search(); advanceUntilIdle()
        assertEquals(UiState.Error("대중교통 경로를 찾지 못했어요."), vm.state.value.results)
    }

    @Test fun autoOpenSearchOnlyOnce() = runTest {
        val vm = vm(FakeLocationProvider(true, here)); vm.init(null); advanceUntilIdle()
        assertTrue(vm.consumeAutoOpenSearch()); assertFalse(vm.consumeAutoOpenSearch())
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 경로 편집 화면과 경로 흐름 상태"`

---

### Task 7: 지도 감싸개와 지도 핀 선택

**Files:**
- Create: `<pkg>/map/KakaoMapView.kt`, `<pkg>/ui/mappick/MapPickViewModel.kt`, `<pkg>/ui/mappick/MapPickScreen.kt`, `<test>/ui/mappick/MapPickViewModelTest.kt`
- Delete: `<pkg>/map/MapExperiment.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Consumes: Task 1 `PlaceRepository.reverse`, Task 3 `RecentSearchRepository`, Task 2 `formatDistance`, `PickResult`
- Produces: `@Composable fun KakaoMapView(modifier: Modifier = Modifier, onReady: (KakaoMap) -> Unit, onCameraIdle: ((GeoPoint) -> Unit)? = null)` — `AndroidView`로 `MapView`를 만들고 `start(...)`를 부른다. 생명주기 ON_RESUME → `resume()`, ON_PAUSE → `pause()`. `onMapError`나 생성 중 예외가 나면 지도 대신 `Copy.MAP_FAILED`를 보여준다(앱은 계속 동작). 카메라 이동이 끝나면(SDK의 카메라 이동 종료 리스너) 가운데 좌표로 `onCameraIdle`. 시그니처는 사용하는 SDK 버전 문서를 따른다.
- Produces: `class MapPickViewModel(places: PlaceRepository, recents: RecentSearchRepository) : ViewModel` — `fun onCameraIdle(point: GeoPoint)`(500ms 안에 다시 움직이면 마지막 위치만 조회 — 7.3), `val card: StateFlow<UiState<PinCard>>`, `suspend fun pick(slot: Slot?): PickResult`(`Endpoint(address ?: Copy.PIN_LABEL, point, PIN)`, 최근 검색 기록)
- Produces: `data class PinCard(val point: GeoPoint, val address: String?, val addressText: String, val stationText: String)` — `addressText = address ?: Copy.NO_ADDRESS`, `stationText = "가장 가까운 역: {이름} · {formatDistance(m)}"` 또는 `Copy.NO_STATION_NEARBY`
- Copy: `NO_ADDRESS = "주소 정보 없음"`, `NO_STATION_NEARBY = "근처 2km 안에 역이 없어요"`, `PIN_LABEL = "지도에서 선택한 위치"`
- Produces (`MapPickScreen`): 지도 + 가운데 고정 핀 + 하단 카드. `MapPickDest(slot)`의 slot이 있으면 [출발] [도착](P4가 [경유]를 더함), 없으면 [이 위치로 지정]. 처음 카메라는 현재 위치, 없으면 서울시청(37.5666, 126.9784).
- 최소 버전(3.4, 11/15까지 못 끝내면): 주소 조회를 빼고 카드에 좌표만 보여 핀 좌표로 바로 지정

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class MapPickViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val places = FakePlaceRepository()
    private val p1 = GeoPoint(37.55, 126.92)
    private val p2 = GeoPoint(37.5571, 126.9245)

    @Test fun onlyLastCameraPositionIsReversed() = runTest {
        val vm = MapPickViewModel(places, FakeRecentSearchRepository())
        vm.onCameraIdle(p1); advanceTimeBy(300); vm.onCameraIdle(p2); advanceUntilIdle()
        assertEquals(listOf(p2), places.reverseCalls)
    }

    @Test fun seaShowsNoAddressAndNoStation() = runTest {
        places.reverseResult = ApiResult.Ok(ReverseDto(null, null))
        val vm = MapPickViewModel(places, FakeRecentSearchRepository()); vm.onCameraIdle(p1); advanceUntilIdle()
        val card = (vm.card.value as UiState.Content).data
        assertEquals("주소 정보 없음" to "근처 2km 안에 역이 없어요", card.addressText to card.stationText)
    }

    @Test fun nearestStationText() = runTest {
        places.reverseResult = ApiResult.Ok(ReverseDto("서울 마포구 양화로 160", NearestStationDto("홍대입구", "홍대입구", 320)))
        val vm = MapPickViewModel(places, FakeRecentSearchRepository()); vm.onCameraIdle(p2); advanceUntilIdle()
        assertEquals("가장 가까운 역: 홍대입구 · 320m", (vm.card.value as UiState.Content).data.stationText)
    }

    @Test fun pickUsesAddressAsLabelAndRecordsRecent() = runTest {
        places.reverseResult = ApiResult.Ok(ReverseDto("서울 마포구 양화로 160", null))
        val recents = FakeRecentSearchRepository()
        val vm = MapPickViewModel(places, recents); vm.onCameraIdle(p2); advanceUntilIdle()
        val pick = vm.pick(Slot.ORIGIN)
        assertEquals(Endpoint("서울 마포구 양화로 160", p2, EndpointKind.PIN), pick.endpoint)
        assertEquals(listOf(pick.endpoint), recents.recorded)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 지도 실패 대체 확인** — `local.properties`의 `kakao.nativeAppKey`를 일부러 틀리게 해 설치하면 지도 자리에 "지도를 불러오지 못했어요"가 보이고 하단 카드는 동작한다. 확인 뒤 되돌린다.
- [ ] **Step 6: Commit** — `git add android docs/learning && git commit -m "feat(android): 지도 감싸개와 지도 핀 선택"`

---

### Task 8: 경로 결과 화면

**Files:**
- Create: `<pkg>/ui/common/LineColors.kt`, `<pkg>/ui/routes/RouteCardUi.kt`, `<pkg>/ui/routes/RoutesScreen.kt`, `<test>/ui/routes/RouteCardUiTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Consumes: Task 6 `RouteFlowViewModel`(`results`, `receivedAt`, `select`), Task 2 형식 함수, Task 1 `specRoute()`
- Produces (`LineColors.kt`, 7.3의 호선 색 표를 앱 안에 둔다): `fun lineColor(lineName: String?): Long` — `1호선 0xFF0052A4`, `2호선 0xFF00A84D`, `3호선 0xFFEF7C1C`, `4호선 0xFF00A5DE`, `5호선 0xFF996CAC`, `6호선 0xFFCD7C2F`, `7호선 0xFF747F00`, `8호선 0xFFE6186C`, `9호선 0xFFBDB092`, `경의중앙선 0xFF77C4A3`, `공항철도 0xFF0090D2`, `경춘선 0xFF0C8E72`, `수인분당선 0xFFFABE00`, `신분당선 0xFFD4003B`, `우이신설선 0xFFB0CE18`, `서해선 0xFF8FC31F`, `신림선 0xFF6789CA`, `경강선 0xFF0054A6`, `GTX-A 0xFF9A6292`, 그 밖 `0xFF888888`; `const val WALK_COLOR = 0xFF9E9E9E`, `const val BUS_COLOR = 0xFF33A23D`
- Produces: `data class Segment(val weight: Float, val color: Long, val label: String?)`, `data class RouteCardUi(val id: String, val totalText: String, val transfersText: String, val fareText: String, val segments: List<Segment>, val nextTrainText: String?)`, `fun RouteDto.toCardUi(receivedAt: Long, now: Long): RouteCardUi`
  - `transfersText`: 0이면 "환승 없음", 아니면 "환승 N회". 구간 막대는 단계마다 하나(가중치 = 소요 시간 비율, 색은 도보 `WALK_COLOR`·지하철 호선 색·버스 `BUS_COLOR`, 라벨은 `lineName ?: routeName`)
  - `nextTrainText`: `firstBoarding?.nextTrains?.firstOrNull()?.remainingSec`가 있으면 `"다음 열차 " + formatRemaining(remainingSec − (now − receivedAt) / 1000)`, 없으면 `null`(⑩ 서버 결합은 P4 — 그 전까지는 보이지 않는다)
- Produces (`RoutesScreen`): `StateContainer`(빈 결과 문구 `Copy.NO_ROUTES = "경로가 없어요"`) 안의 카드 목록, 카드를 누르면 `select(id)` 후 `RouteDetailDest(id)`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class RouteCardUiTest {
    private val route = specRoute()      // 스펙 6.2 예시: 1980초, 환승 1, 1550원, 도보 420 + 2호선 600

    @Test fun cardTexts() {
        val ui = route.toCardUi(receivedAt = 0, now = 0)
        assertEquals(listOf("33분", "환승 1회", "1,550원", "다음 열차 3분"),
            listOf(ui.totalText, ui.transfersText, ui.fareText, ui.nextTrainText))
    }
    @Test fun segmentWeightsAndColors() {
        val s = route.toCardUi(0, 0).segments
        assertEquals(0.41f, s[0].weight, 0.01f); assertEquals(0.59f, s[1].weight, 0.01f)
        assertEquals(listOf(WALK_COLOR, lineColor("2호선")), s.map { it.color })
    }
    @Test fun nextTrainCountsDownFromReceivedAt() =
        assertEquals("다음 열차 2분", route.toCardUi(receivedAt = 0, now = 60_000).nextTrainText)
    @Test fun noFirstBoardingHidesNextTrain() =
        assertNull(route.copy(firstBoarding = null).toCardUi(0, 0).nextTrainText)
    @Test fun lineColorTable() {
        assertEquals(0xFF00A84D, lineColor("2호선")); assertEquals(0xFF888888, lineColor("없는선"))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 경로 결과 카드 목록"`

---

### Task 9: 경로 상세 화면 (지도와 단계 안내)

**Files:**
- Create: `<pkg>/map/RouteOverlay.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`, `<pkg>/ui/routedetail/StepText.kt`, `android/app/src/main/res/drawable/marker_start.xml`, `marker_transfer.xml`, `marker_end.xml`, `<test>/map/RouteOverlayTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`

**Interfaces:**
- Consumes: Task 7 `KakaoMapView`, Task 8 `lineColor`·`WALK_COLOR`·`BUS_COLOR`, Task 6 `selectedRoute`
- Produces (`RouteOverlay.kt`): `data class PolylineSpec(val points: List<GeoPoint>, val color: Long, val dashed: Boolean)`, `enum class MarkerKind { START, TRANSFER, END }`, `data class MarkerSpec(val point: GeoPoint, val kind: MarkerKind)`, `data class OverlayModel(val polylines: List<PolylineSpec>, val markers: List<MarkerSpec>, val bounds: List<GeoPoint>)`, `fun buildOverlay(route: RouteDto): OverlayModel` — 모든 구간의 단계를 차례로 보고, 경로 좌표가 2개 이상인 단계마다 선(도보: 회색 점선, 지하철: 호선 색, 버스: 초록). START = 첫 좌표, END = 마지막 좌표, TRANSFER = 두 번째 대중교통 단계부터 각 대중교통 단계의 첫 좌표. `fun KakaoMap.render(overlay: OverlayModel)` — 이전 경로선·마커를 지우고 경로선 API로 그린 뒤(점선은 SDK의 선 패턴), 마커 세 종류를 라벨로 찍고, 카메라를 `bounds`에 맞춘다(여백 48dp). `render`는 단위 테스트하지 않는다.
- Produces (`StepText.kt`): `fun stepTitle(step: StepDto): String` — WALK `"도보 N분"`, SUBWAY `"{lineName} {첫 역} → {마지막 역} · {정차역 수 − 1}정거장"`, BUS `"{routeName}번 버스 · {정차역 수 − 1}정거장"`
- Produces (`RouteDetailScreen`): 위는 `KakaoMapView`(준비되면 `render(buildOverlay(route))`), 아래는 끌어 올리는 시트(`BottomSheetScaffold`)에 단계 목록(`stepTitle` + `guidance`). [승차 전 알림]·[출발(하차 알림)]·[★] 버튼은 P4가 더한다.
- 최소 버전(3.4): 구간별 색과 마커 없이 단색 선 하나

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class RouteOverlayTest {
    private fun step(mode: StepMode, path: List<List<Double>>, line: String? = null) =
        StepDto(mode = mode, durationSec = 60, distanceM = 100, path = path, lineName = line,
                stops = if (mode == StepMode.WALK) null else listOf("가", "나"))
    private val route = specRoute().copy(legs = listOf(LegDto(listOf(
        step(StepMode.WALK, listOf(listOf(37.0, 127.0), listOf(37.1, 127.0))),
        step(StepMode.SUBWAY, listOf(listOf(37.1, 127.0), listOf(37.2, 127.0)), "2호선"),
        step(StepMode.WALK, listOf(listOf(37.2, 127.0), listOf(37.2, 127.1))),
        step(StepMode.SUBWAY, listOf(listOf(37.2, 127.1), listOf(37.3, 127.1)), "3호선"),
    ))))

    @Test fun walkIsDashedGreyAndSubwayUsesLineColor() {
        val lines = buildOverlay(route).polylines
        assertEquals(listOf(true, false, true, false), lines.map { it.dashed })
        assertEquals(listOf(WALK_COLOR, lineColor("2호선"), WALK_COLOR, lineColor("3호선")), lines.map { it.color })
    }
    @Test fun markersAtStartTransferEnd() {
        assertEquals(listOf(
            MarkerSpec(GeoPoint(37.0, 127.0), MarkerKind.START),
            MarkerSpec(GeoPoint(37.2, 127.1), MarkerKind.TRANSFER),
            MarkerSpec(GeoPoint(37.3, 127.1), MarkerKind.END)), buildOverlay(route).markers)
    }
    @Test fun stepTitles() {
        val sub = StepDto(StepMode.SUBWAY, 600, 5200, lineName = "2호선", stops = listOf("홍대입구", "합정", "당산"))
        assertEquals("2호선 홍대입구 → 당산 · 2정거장", stepTitle(sub))
        assertEquals("도보 7분", stepTitle(StepDto(StepMode.WALK, 420, 480)))
        assertEquals("7016번 버스 · 3정거장", stepTitle(StepDto(StepMode.BUS, 300, 2000, routeName = "7016", stops = listOf("a", "b", "c", "d"))))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`. 실기기에서 홍대입구 → 강남 경로의 구간별 색 선과 마커가 보인다.
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 경로 상세 지도와 단계 안내"`

---

### Task 10: 역 상세 화면

**Files:**
- Create: `<pkg>/ui/station/StationViewModel.kt`, `<pkg>/ui/station/StationScreen.kt`, `<test>/ui/station/StationViewModelTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`, `<test>/fakes/Fakes.kt`(`FakeFavoriteRepository`)

**Interfaces:**
- Consumes: Task 1 `StationRepository.arrivals`, Task 3 `FavoriteRepository`, Task 2 형식 함수
- Produces: `class StationViewModel(stationId: String, stations: StationRepository, favorites: FavoriteRepository, clock: () -> Long = System::currentTimeMillis) : ViewModel` — `val state: StateFlow<StationUiState>`, `suspend fun runAutoRefresh()`(새로 고침 → 30,000ms 대기 반복, 취소되면 끝), `fun refresh()`, `fun toggleFavorite()`
- Produces: `data class StationUiState(val stationId: String, val arrivals: UiState<ArrivalsDto> = UiState.Loading, val receivedAt: Long? = null, val isFavorite: Boolean = false)` — 빈 `groups`는 `Empty`, `stale`은 `Content(stale = true)`
- Produces (순수 함수, `StationViewModel.kt`): `fun groupTitle(g: ArrivalGroupDto): String` = `"{line} {direction} · {towards} 방면"`(towards가 없으면 `"{line} {direction}"`), `fun displayRemainingSec(train: TrainDto, receivedAt: Long, now: Long): Int?` = `remainingSec − (now − receivedAt) / 1000`의 0 이상 값, 없으면 `null`
- Produces (`StationScreen`): 제목 = 역 id, 상단 [★], "n초 전 갱신"(1초마다 `formatUpdatedAgo`), 그룹마다 제목과 열차 2대(`"{destination}행 · {formatRemaining(displayRemainingSec)}"`, 급행·막차 표시), `Copy.NO_ARRIVALS = "도착 정보가 없어요"`. 화면은 `repeatOnLifecycle(STARTED)` 안에서 `runAutoRefresh()`를 부른다. 그룹별 [승차 전 알림]은 P4가 더한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class StationViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stations = FakeStationRepository()
    private val favorites = FakeFavoriteRepository()
    private fun train(sec: Int?) = TrainDto("성수", sec, "", false, false)

    @Test fun autoRefreshEvery30Seconds() = runTest {
        val vm = StationViewModel("홍대입구", stations, favorites) { 0L }
        val job = launch { vm.runAutoRefresh() }
        advanceTimeBy(60_001); runCurrent()
        assertEquals(3, stations.arrivalCalls.size)
        job.cancel(); advanceTimeBy(60_000); runCurrent()
        assertEquals(3, stations.arrivalCalls.size)
    }
    @Test fun remainingCountsDownFromReceivedAt() {
        assertEquals(150, displayRemainingSec(train(180), receivedAt = 0, now = 30_000))
        assertEquals(0, displayRemainingSec(train(10), 0, 30_000))
        assertNull(displayRemainingSec(train(null), 0, 0))
    }
    @Test fun staleAndEmptyStates() = runTest {
        stations.arrivalsResult = ApiResult.Ok(ArrivalsDto("홍대입구", "t", stale = true, groups = listOf(GROUP)))
        val vm = StationViewModel("홍대입구", stations, favorites) { 0L }; vm.refresh(); advanceUntilIdle()
        assertTrue((vm.state.value.arrivals as UiState.Content).stale)
        stations.arrivalsResult = ApiResult.Ok(ArrivalsDto("홍대입구", "t", groups = emptyList()))
        vm.refresh(); advanceUntilIdle()
        assertEquals(UiState.Empty, vm.state.value.arrivals)
    }
    @Test fun groupTitleText() =
        assertEquals("2호선 내선 · 합정 방면", groupTitle(ArrivalGroupDto("2호선", "내선", "합정", emptyList())))
    @Test fun toggleFavorite() = runTest {
        val vm = StationViewModel("홍대입구", stations, favorites) { 0L }; vm.toggleFavorite(); advanceUntilIdle()
        assertTrue(vm.state.value.isFavorite)
    }
}
```

(`GROUP`은 테스트 안의 아무 `ArrivalGroupDto` 상수)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`. 실기기에서 역 상세를 열어 두면 30초마다 갱신되고, 홈 버튼으로 나가면 갱신이 멈춘다(서버 접근 로그로 확인).
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 역 상세 실시간 도착과 역 즐겨찾기"`

---

### Task 11: 홈 화면

**Files:**
- Create: `<pkg>/ui/home/HomeViewModel.kt`, `<pkg>/ui/home/HomeScreen.kt`, `<test>/ui/home/HomeViewModelTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`, `<test>/fakes/Fakes.kt`(`FakeServerRepository`)

**Interfaces:**
- Consumes: Task 1 `ServerRepository`·`StationRepository`, Task 3 `MyPlaceRepository`·`FavoriteRepository`
- Produces: `class HomeViewModel(myPlaces: MyPlaceRepository, favorites: FavoriteRepository, stations: StationRepository, server: ServerRepository) : ViewModel` — 만들어질 때 `server.wake()`를 한 번 부른다(10.1, 응답을 기다리지 않고 기기 데이터부터 보여준다). `val state: StateFlow<HomeUiState>`, `suspend fun runAutoRefresh()`(30초마다 즐겨찾는 역 도착 갱신)
- Produces: `data class HomeUiState(val chips: List<MyPlaceEntity> = emptyList(), val favoriteStations: List<FavoriteStationUi> = emptyList())`, `data class FavoriteStationUi(val stationId: String, val arrivals: UiState<ArrivalsDto>)` — 역마다 따로 불러 한 역의 실패가 다른 역을 가리지 않는다. 즐겨찾기에 `line`·`towards`가 있으면 그 값으로 요청한다.
- Produces (`HomeScreen`): 검색창(→ `RouteFlowGraph()`), 내 장소 칩(집·학교·회사 이름은 "집"/"학교"/"회사", 저장 안 된 종류는 "+ 집" 등 → 설정의 내 장소 편집, 저장된 칩 → `RouteFlowGraph(destMyPlaceId)`), 즐겨찾는 역 카드(그룹 제목과 첫 열차, 누르면 `StationDest`), 상단 설정 아이콘(→ `SettingsDest`). 자주 가는 경로 카드와 세션 배너는 P4가 더한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun wakesServerOnce() = runTest {
        val server = FakeServerRepository()
        HomeViewModel(FakeMyPlaceRepository(), FakeFavoriteRepository(), FakeStationRepository(), server); advanceUntilIdle()
        assertEquals(1, server.wakeCalls)
    }
    @Test fun oneFailingFavoriteDoesNotBreakOthers() = runTest {
        val favorites = FakeFavoriteRepository(stations = listOf(fav("홍대입구"), fav("합정")))
        val stations = FakeStationRepository().apply { arrivalsByStation = mapOf("홍대입구" to ApiResult.Err(ApiError.Network)) }
        val vm = HomeViewModel(FakeMyPlaceRepository(), favorites, stations, FakeServerRepository()); advanceUntilIdle()
        assertEquals(listOf(true, false), vm.state.value.favoriteStations.map { it.arrivals is UiState.Error })
    }
    @Test fun favoriteWithLineRequestsThatLine() = runTest {
        val favorites = FakeFavoriteRepository(stations = listOf(FavoriteStationEntity("홍대입구", "2호선", null, 0)))
        val stations = FakeStationRepository()
        HomeViewModel(FakeMyPlaceRepository(), favorites, stations, FakeServerRepository()); advanceUntilIdle()
        assertEquals(Triple("홍대입구", "2호선", null), stations.arrivalCalls.single())
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 홈 화면(검색창, 내 장소 칩, 즐겨찾는 역)"`

---

### Task 12: 설정 화면

**Files:**
- Create: `<pkg>/ui/settings/SettingsViewModel.kt`, `<pkg>/ui/settings/SettingsScreen.kt`, `<pkg>/ui/settings/PermissionRows.kt`, `<test>/ui/settings/SettingsViewModelTest.kt`
- Modify: `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Consumes: Task 3 `MyPlaceRepository`·`SettingsStore`, Task 5·7의 하나만 고르는 모드(`SearchDest(null)`, `MapPickDest(null)`)
- Produces: `class SettingsViewModel(myPlaces: MyPlaceRepository, store: SettingsStore) : ViewModel` — `val state: StateFlow<SettingsUiState>`(`places`, `settings`), `fun savePlace(type: MyPlaceType, label: String, endpoint: Endpoint)`(주소는 `PIN`이면 `endpoint.label`), `fun deletePlace(id: Long)`, `fun setDepartMargin(sec: Int)`, `fun setAlightLead(sec: Int)`, `fun setStationLead(sec: Int)`, `fun setMaxWait(min: Int)` — 값이 아래 선택지에 없으면 `IllegalArgumentException`
- Produces: 선택지 `DEPART_MARGIN_OPTIONS = listOf(0, 30, 60, 90, 120)`, `ALIGHT_LEAD_OPTIONS = listOf(60, 120, 180, 240)`, `STATION_LEAD_OPTIONS = listOf(60, 120, 180, 300)`, `MAX_WAIT_OPTIONS = listOf(15, 30, 45, 60)`
- Produces (`PermissionRows.kt`): `data class PermissionRow(val title: String, val granted: Boolean)`, `fun permissionRows(location: Boolean, notifications: Boolean?, exactAlarm: Boolean?): List<PermissionRow>` — `null`은 그 기기에 해당 없음(알림은 Android 13 미만, 정확한 알람은 12 미만)이라 행을 뺀다. 제목 "위치", "알림", "알람 및 리마인더". 화면은 행마다 "허용됨"/"허용 안 됨"과 [설정 열기](앱 상세 설정 또는 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`)를 보여준다.
- Produces (`SettingsScreen`): 내 장소 관리(집·학교·회사·직접 추가: 검색 또는 지도에서 지정, 삭제), 알림 시점 네 항목(위 선택지), 권한 상태, 정보(앱 버전과 출처 문구 `Copy.ATTRIBUTION = "이 앱은 서울 열린데이터광장의 '서울시 지하철 실시간 도착정보'·'서울시 역사마스터 정보'와 카카오의 지도·로컬·길찾기 API를 사용합니다."` + P1 §2의 이용허락 문구 — 10.4)

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun defaultsComeFromStore() = runTest {
        val vm = SettingsViewModel(FakeMyPlaceRepository(), fakeSettingsStore()); advanceUntilIdle()
        assertEquals(AppSettings(), vm.state.value.settings)
    }
    @Test fun savingHomeUsesHomeType() = runTest {
        val places = FakeMyPlaceRepository()
        SettingsViewModel(places, fakeSettingsStore()).savePlace(MyPlaceType.HOME, "집",
            Endpoint("서울 마포구 양화로 160", GeoPoint(37.5571, 126.9245), EndpointKind.PIN)); advanceUntilIdle()
        assertEquals(Triple(MyPlaceType.HOME, "집", "서울 마포구 양화로 160"), places.saved.single())
    }
    @Test(expected = IllegalArgumentException::class)
    fun valueOutsideOptionsIsRejected() = SettingsViewModel(FakeMyPlaceRepository(), fakeSettingsStore()).setDepartMargin(45)
    @Test fun permissionRowsHideNotApplicable() =
        assertEquals(listOf(PermissionRow("위치", true)), permissionRows(location = true, notifications = null, exactAlarm = null))
}
```

(`fakeSettingsStore()`는 임시 파일 DataStore로 만든 `SettingsStore`, `FakeMyPlaceRepository.saved`는 (종류, 이름, 주소) 기록)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 점검일 확인(11/15)** — 실기기에서 홈 → 집 칩 → 검색 → 경로 결과 → 경로 상세(지도) 흐름과 역 상세를 확인한다. 이 주말부터 본인 통학에 쓰고(S4), 발견한 버그는 Issue로 남긴다.
- [ ] **Step 6: Commit** — `git add android docs/learning && git commit -m "feat(android): 설정 화면(내 장소, 알림 시점, 권한, 출처)"`
