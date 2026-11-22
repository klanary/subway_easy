package io.github.klanary.jigeumchulbal.ui.linemap

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/** 역 시트 시각 고르기(수동 검토 2026-11-22): [시각 선택]으로 휠을 열고, [적용]을 눌러야 그 시각으로 바뀐다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StationSheetTimePickTest {
    @get:Rule val rule = createComposeRule()

    private var picked: Pair<Int, Int>? = null

    private fun show() = rule.setContent {
        AppTheme(darkTheme = false) {
            StationSheetContent(
                "시청", UiState.Content(StationSheetUi("시청", listOf("1호선"), emptyList(), emptyList())), TripDraft(),
                onPickTime = { h, m -> picked = h to m }, nowTime = { LocalTime.of(8, 30) },
            )
        }
    }

    @Test fun wheelsHiddenUntilAsked() {
        show()
        rule.onAllNodesWithTag("inline-hour").assertCountEquals(0)
        rule.onNodeWithText(Copy.SHEET_PICK_TIME).performClick()
        rule.onAllNodesWithTag("inline-hour").assertCountEquals(1)
    }

    @Test fun scrollingAloneChangesNothingUntilApply() {
        show()
        rule.onNodeWithText(Copy.SHEET_PICK_TIME).performClick()
        rule.onNodeWithTag("inline-ampm").performScrollToIndex(1)
        rule.onNodeWithTag("inline-hour").performScrollToIndex(7)
        rule.waitForIdle()
        assertNull(picked)
        rule.onNodeWithText(Copy.APPLY).performClick()
        assertEquals(19 to 30, picked)
        rule.onAllNodesWithTag("inline-hour").assertCountEquals(0)
    }

    @Test fun cancelKeepsTime() {
        show()
        rule.onNodeWithText(Copy.SHEET_PICK_TIME).performClick()
        rule.onNodeWithText(Copy.CANCEL).performClick()
        assertNull(picked)
        rule.onAllNodesWithTag("inline-hour").assertCountEquals(0)
    }
}
