package io.github.klanary.jigeumchulbal.ui.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.session.AlightPoint
import io.github.klanary.jigeumchulbal.session.PointKind
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.TrainPlan
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.WaitTarget
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.routedetail.RouteDetailContent
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteEditContent
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowState
import io.github.klanary.jigeumchulbal.ui.routes.RoutesContent
import io.github.klanary.jigeumchulbal.ui.routine.RoutineEditContent
import io.github.klanary.jigeumchulbal.ui.routine.RoutineEditUi
import io.github.klanary.jigeumchulbal.ui.routine.RoutineListContent
import io.github.klanary.jigeumchulbal.ui.search.SearchContent
import io.github.klanary.jigeumchulbal.ui.search.SearchUiState
import io.github.klanary.jigeumchulbal.ui.session.SessionContent
import io.github.klanary.jigeumchulbal.ui.settings.PermissionRow
import io.github.klanary.jigeumchulbal.ui.settings.SettingsContent
import io.github.klanary.jigeumchulbal.ui.settings.SettingsUiState
import java.time.OffsetDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 남은 화면(검색·경로 편집·경로 결과·경로 상세·루틴·세션·설정)의 상태별 현재 모습(화면 다듬기 Task 5~10, 한꺼번에 합의용).
 * 시각은 2026-11-18 08:00 KST에 고정한다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class RestScreensScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun t(hhmm: String) = "2026-11-18T$hhmm:00+09:00"
    private val now = OffsetDateTime.parse(t("08:00")).toInstant().toEpochMilli()

    // --- 검색
    private val stations = listOf(
        StationDto("시청", "시청", listOf("1호선", "2호선"), 37.56, 126.97),
        StationDto("신촌", "신촌", listOf("2호선"), 37.55, 126.93),
        StationDto("신촌(경의중앙선)", "신촌(경의중앙선)", listOf("경의중앙선"), 37.55, 126.94),
    )
    private val recents = listOf(RecentSearchEntity(1, "마포", "마포", 0), RecentSearchEntity(2, "강남", "강남", 0))

    @Test fun searchEmpty() = rule.snapBothThemes("search_empty") { SearchContent(SearchUiState()) }
    @Test fun searchRecents() = rule.snapBothThemes("search_recents") { SearchContent(SearchUiState(recents = recents)) }
    @Test fun searchLoading() = rule.snapBothThemes("search_loading") { SearchContent(SearchUiState(query = "ㅅㅊ", stations = UiState.Loading)) }
    @Test fun searchResults() = rule.snapBothThemes("search_results") { SearchContent(SearchUiState(query = "ㅅㅊ", stations = UiState.Content(stations))) }
    @Test fun searchNone() = rule.snapBothThemes("search_none") { SearchContent(SearchUiState(query = "qqq", stations = UiState.Empty)) }
    @Test fun searchOffline() = rule.snapBothThemes("search_offline") { SearchContent(SearchUiState(query = "시청", stations = UiState.Offline)) }

    // --- 경로
    private fun c(no: String, dep: String, arr: String, express: Boolean = false) =
        CandidateDto(trainNo = no, departsAt = t(dep), arrivesAt = t(arr), isExpress = express)

    private val five = listOf(c("5301", "08:05", "08:09"), c("5303", "08:15", "08:20"))
    private val nine = listOf(c("9101", "08:10", "08:14"), c("9501", "08:20", "08:24", express = true), c("9103", "08:30", "08:34"))

    private fun subway(line: String, from: String, to: String, train: CandidateDto, candidates: List<CandidateDto>) = StepDto(
        mode = StepMode.SUBWAY, durationSec = 240, distanceM = 3000, lineName = line, boardStationId = from, alightStationId = to,
        stops = listOf(from, to), departsAt = train.departsAt, arrivesAt = train.arrivesAt, trainNo = train.trainNo,
        destination = "종점", candidates = candidates, source = "official",
    )

    private val walk = StepDto(mode = StepMode.WALK, durationSec = 60, distanceM = 80, guidance = "환승 통로로 1분")

    private val route = RouteDto(
        id = "r1", type = "SUBWAY", totalSec = 840, distanceM = 6000, transfers = 1,
        legs = listOf(LegDto(listOf(subway("5호선", "마포", "여의도", five[0], five), walk, subway("9호선", "여의도", "노량진", nine[0], nine)))),
        departAt = t("08:05"), arriveAt = t("08:14"),
    )
    private val route2 = route.copy(id = "r2", totalSec = 1260, transfers = 0, legs = listOf(LegDto(listOf(subway("5호선", "마포", "노량진", five[1], five)))))

    private val trip = RouteFlowState(origin = StationRef("마포", "마포"), dest = StationRef("노량진", "노량진"))

    @Test fun editEmpty() = rule.snapBothThemes("edit_empty") { RouteEditContent(RouteFlowState()) }
    @Test fun editFilled() = rule.snapBothThemes("edit_filled") { RouteEditContent(trip) }
    @Test fun editVia() = rule.snapBothThemes("edit_via") { RouteEditContent(trip.copy(viaVisible = true)) }

    private val found = UiState.Content(RoutesDto(t("08:00"), routes = listOf(route, route2)))
    @Test fun routesLoading() = rule.snapBothThemes("routes_loading") { RoutesContent(trip.copy(results = UiState.Loading), now) }
    @Test fun routesFound() = rule.snapBothThemes("routes_found") { RoutesContent(trip.copy(results = found, receivedAt = now), now) }
    @Test fun routesNone() = rule.snapBothThemes("routes_none") { RoutesContent(trip.copy(results = UiState.Empty), now) }
    @Test fun routesError() = rule.snapBothThemes("routes_error") { RoutesContent(trip.copy(results = UiState.Error(Copy.SERVER_PROBLEM)), now) }

    @Test fun detail() = rule.snapBothThemes("detail") {
        RouteDetailContent(trip.copy(results = found, selectedRouteId = "r1"), now = now)
    }
    @Test fun detailGone() = rule.snapBothThemes("detail_gone") { RouteDetailContent(trip, now = now) }

    // --- 루틴
    private fun routine(id: Long, minute: Int, enabled: Boolean) = RoutineEntity(
        id = id, name = "통학", fromId = "마포", fromName = "마포", viaId = null, viaName = null, toId = "노량진", toName = "노량진",
        routeJson = "", days = 0b0011111, minuteOfDay = minute, enabled = enabled,
    )

    @Test fun routinesEmpty() = rule.snapBothThemes("routines_empty") { RoutineListContent(emptyList(), exactAlarm = true) }
    @Test fun routinesList() = rule.snapBothThemes("routines_list") {
        RoutineListContent(listOf(routine(1, 7 * 60 + 30, true), routine(2, 18 * 60, false)), exactAlarm = false)
    }

    private val edit = RoutineEditUi(name = "통학", from = StationRef("마포", "마포"), to = StationRef("노량진", "노량진"), preview = listOf("08:14", "08:22"))
    @Test fun routineEdit() = rule.snapBothThemes("routine_edit") { RoutineEditContent(edit) }
    @Test fun routineEditNoDays() = rule.snapBothThemes("routine_edit_nodays") { RoutineEditContent(edit.copy(days = 0, preview = emptyList())) }

    // --- 세션
    private val target = WaitTarget("마포", "마포", "5호선", "여의도", "하행")
    private fun waiting(plan: TrainPlan?, failures: Int = 0, updatedAt: Long? = now) = SessionState.Waiting(
        target, WaitMode.DEPARTURE_TIMING, null, 420, 60, 180, 120, startedAt = now, deadlineAt = now + 1_800_000,
        plan = plan, failures = failures, nextTrainSec = 240, updatedAt = updatedAt,
    )

    @Test fun sessionIdle() = rule.snapBothThemes("session_idle") { SessionContent(SessionState.Idle, now, inAppOnly = false, exactAlarms = true) }
    @Test fun sessionWaiting() = rule.snapBothThemes("session_waiting") {
        SessionContent(waiting(TrainPlan(now + 420_000, now + 900_000, "방화")), now, inAppOnly = false, exactAlarms = true)
    }
    @Test fun sessionLoading() = rule.snapBothThemes("session_loading") {
        SessionContent(waiting(null, updatedAt = null), now, inAppOnly = true, exactAlarms = true)
    }
    @Test fun sessionDisconnected() = rule.snapBothThemes("session_disconnected") {
        SessionContent(waiting(TrainPlan(now + 420_000, now + 900_000, null), failures = 3), now, inAppOnly = false, exactAlarms = false)
    }
    @Test fun sessionRiding() = rule.snapBothThemes("session_riding") {
        val points = listOf(
            AlightPoint("여의도", false, PointKind.TRANSFER, alightAt = now + 540_000, alertAt = now + 420_000, stopsToGo = 3, nextLine = "9호선"),
            AlightPoint("노량진", false, PointKind.FINAL, alightAt = now + 840_000, alertAt = now + 720_000, stopsToGo = 2),
        )
        SessionContent(SessionState.Riding(points, nextIndex = 1), now, inAppOnly = false, exactAlarms = true)
    }

    // --- 설정
    @Test fun settings() = rule.snapBothThemes("settings") {
        SettingsContent(
            SettingsUiState(AppSettings()),
            listOf(PermissionRow(Copy.PERMISSION_LOCATION, true), PermissionRow(Copy.PERMISSION_NOTIFICATIONS, false), PermissionRow(Copy.PERMISSION_EXACT_ALARM, false)),
        )
    }
}
