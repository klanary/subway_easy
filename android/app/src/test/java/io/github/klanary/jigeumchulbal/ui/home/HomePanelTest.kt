package io.github.klanary.jigeumchulbal.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 홈 아래 패널(2차 다듬기 Task 3, 스펙 4.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class HomePanelTest {
    @get:Rule val rule = createComposeRule()

    private val routine = RoutineEntity(
        id = 7, name = "통학", fromId = "마포", fromName = "마포", viaId = null, viaName = null,
        toId = "공덕", toName = "공덕", routeJson = "", days = 0b1111111, minuteOfDay = 8 * 60 + 10,
    )
    private fun fav(id: Long) = FavoriteRouteEntity(id, "a$id", "출발$id", null, null, "b$id", "도착$id", 0)

    private fun set(routines: List<RoutineEntity> = listOf(routine), favorites: List<FavoriteRouteEntity> = emptyList(), onRoutine: (RoutineEntity) -> Unit = {}, onAll: () -> Unit = {}) =
        rule.setContent {
            AppTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    HomePanel(
                        todayRoutines = routines, favorites = favorites,
                        onSearch = {}, onLocate = {}, onRoute = {}, onRoutine = onRoutine, onAllRoutines = onAll, onFavorite = {},
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("panel"),
                    )
                }
            }
        }

    @Test fun tappingRoutineCallsOnRoutine() {
        val tapped = mutableListOf<Long>()
        set(onRoutine = { tapped += it.id })
        rule.onNodeWithText("08:10 통학", substring = true).performClick()
        assertEquals(listOf(7L), tapped)
    }

    @Test fun allRoutinesLinkOpens() {
        var opened = 0
        set(onAll = { opened++ })
        rule.onNodeWithText(Copy.ALL_ROUTINES).performClick()
        assertEquals(1, opened)
    }

    /** 자주 가는 경로가 많아도 패널이 노선도를 절반 넘게 덮지 않는다(Review Focus 5). */
    @Test fun panelNeverCoversHalfTheScreen() {
        set(favorites = (1L..10L).map(::fav))
        val b = rule.onNodeWithTag("panel").getUnclippedBoundsInRoot()
        val h = b.bottom - b.top
        assertTrue("panel height $h", h <= 457.dp)
    }
}
