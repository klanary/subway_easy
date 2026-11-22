package io.github.klanary.jigeumchulbal.ui.routine

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 루틴 편집(수동 검토 2026-11-22): 걷는 시간은 바로 고르는 선택지로도, 이름 칸은 한국어 조합이 깨지지 않게. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoutineWalkChoicesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun walkChoiceSetsMinutes() {
        var walk = -1
        rule.setContent { AppTheme(darkTheme = false) { RoutineEditContent(RoutineEditUi(), onWalk = { walk = it }) } }
        rule.onNodeWithText("10분").performScrollTo().performClick()
        assertEquals(10, walk)
    }

    @Test fun nameShowsTypedTextImmediately() {
        rule.setContent { AppTheme(darkTheme = false) { RoutineEditContent(RoutineEditUi(), onName = {}) } }
        rule.onNode(hasSetTextAction()).performTextInput("방이")
        rule.onNode(hasSetTextAction()).assertTextContains("방이")
    }
}
