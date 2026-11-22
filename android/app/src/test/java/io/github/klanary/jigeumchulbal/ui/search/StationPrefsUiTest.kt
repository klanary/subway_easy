package io.github.klanary.jigeumchulbal.ui.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import io.github.klanary.jigeumchulbal.data.local.RecentRouteEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.linemap.StationSheetContent
import io.github.klanary.jigeumchulbal.ui.linemap.StationSheetUi
import io.github.klanary.jigeumchulbal.ui.linemap.TripDraft
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 즐겨찾는 역과 최근 경로(수동 검토 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StationPrefsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sheetStarTogglesFavorite() {
        var toggled = 0
        rule.setContent {
            AppTheme(darkTheme = false) {
                StationSheetContent(
                    "시청", UiState.Content(StationSheetUi("시청", listOf("1호선"), emptyList(), emptyList())), TripDraft(),
                    isFavorite = false, onToggleFavorite = { toggled++ },
                )
            }
        }
        rule.onNodeWithContentDescription(Copy.ADD_FAVORITE_STATION).performClick()
        assertEquals(1, toggled)
    }

    @Test fun searchShowsFavoriteStationsFirst() {
        var picked: StationRef? = null
        rule.setContent {
            AppTheme(darkTheme = false) {
                SearchContent(SearchUiState(favorites = listOf(FavoriteStationEntity("방이", "방이", 1))), onPick = { picked = it })
            }
        }
        rule.onNodeWithText(Copy.FAVORITE_STATIONS).assertExists()
        rule.onNodeWithText("방이").performClick()
        assertEquals("방이", picked?.id)
    }

    @Test fun recentRoutesTabShowsRoutes() {
        var opened: RecentRouteEntity? = null
        val route = RecentRouteEntity(fromId = "방이", fromName = "방이", viaId = "", viaName = "", toId = "소사", toName = "소사", usedAt = 1)
        rule.setContent {
            AppTheme(darkTheme = false) {
                SearchContent(SearchUiState(recentRoutes = listOf(route)), onPickRoute = { opened = it })
            }
        }
        rule.onNodeWithText(Copy.RECENT_ROUTES, useUnmergedTree = true).performClick() // 왼쪽 아래 탭(버튼 안 글자)
        rule.onNodeWithText("방이 → 소사").performClick()
        assertEquals(route, opened)
    }
}
