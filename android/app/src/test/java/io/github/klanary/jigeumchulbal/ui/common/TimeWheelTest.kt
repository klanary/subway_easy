package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 시각 휠: 주어진 시각에서 시작하고, 굴린 값이 확인으로 넘어간다(수동 검토 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeWheelTest {
    @get:Rule val rule = createComposeRule()

    @Test fun startsAtGivenTime() {
        var got: Pair<Int, Int>? = null
        rule.setContent { AppTheme(darkTheme = false) { TimeWheelDialog(8, 30, onConfirm = { h, m -> got = h to m }, onDismiss = {}) } }
        rule.onNodeWithText(Copy.OK).performClick()
        assertEquals(8 to 30, got)
    }

    @Test fun scrolledValuesAreConfirmed() {
        var got: Pair<Int, Int>? = null
        rule.setContent { AppTheme(darkTheme = false) { TimeWheelDialog(8, 30, onConfirm = { h, m -> got = h to m }, onDismiss = {}) } }
        rule.onNodeWithTag("wheel-hour").performScrollToIndex(19)
        rule.onNodeWithTag("wheel-minute").performScrollToIndex(45)
        rule.waitForIdle()
        rule.onNodeWithText(Copy.OK).performClick()
        assertEquals(19 to 45, got)
    }
}
