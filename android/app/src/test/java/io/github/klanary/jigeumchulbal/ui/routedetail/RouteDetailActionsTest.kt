package io.github.klanary.jigeumchulbal.ui.routedetail

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowState
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 경로 상세의 [★]·[루틴으로 만들기]가 제목 띠 아이콘으로 옮겨도 그대로 동작한다(2차 다듬기 Task 4 합의). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteDetailActionsTest {
    @get:Rule val rule = createComposeRule()

    private val step = StepDto(mode = StepMode.SUBWAY, durationSec = 240, distanceM = 0, lineName = "5호선", stops = listOf("마포", "공덕"))
    private val route = RouteDto(id = "r", type = "SUBWAY", totalSec = 240, distanceM = 0, transfers = 0, legs = listOf(LegDto(listOf(step))))
    private val state = RouteFlowState(
        origin = StationRef("마포", "마포"), dest = StationRef("공덕", "공덕"),
        results = UiState.Content(RoutesDto("2026-11-18T08:00:00+09:00", routes = listOf(route))), selectedRouteId = "r",
    )

    @Test fun favoriteIconToggles() {
        var toggled = 0
        rule.setContent { AppTheme(darkTheme = false) { RouteDetailContent(state, onToggleFavorite = { toggled++ }) } }
        rule.onNodeWithContentDescription(Copy.FAVORITE_ADD_ACTION).performClick()
        assertEquals(1, toggled)
    }

    @Test fun routineIconMakesRoutine() {
        val made = mutableListOf<String>()
        rule.setContent { AppTheme(darkTheme = false) { RouteDetailContent(state, onMakeRoutine = { made += it.id }) } }
        rule.onNodeWithContentDescription(Copy.MAKE_ROUTINE).performClick()
        assertEquals(listOf("r"), made)
    }

    /** 이미 넣은 경로는 "빼기"로 읽힌다(상태를 색이 아니라 모양·이름으로도 알린다, 최종 리뷰). */
    @Test fun favoritedRouteOffersRemove() {
        rule.setContent { AppTheme(darkTheme = false) { RouteDetailContent(state.copy(isFavorite = true)) } }
        rule.onNodeWithContentDescription(Copy.FAVORITE_REMOVE_ACTION).assertExists()
    }
}
