package io.github.klanary.jigeumchulbal.ui.routeedit

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 경로 편집 버튼 정리(Task 6 합의): 바꾸기는 카드 오른쪽 ⇅, 현재 위치는 출발 칸 안 아이콘. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteEditContentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun swapIconSwaps() {
        var swapped = 0
        rule.setContent {
            AppTheme(darkTheme = false) {
                RouteEditContent(RouteFlowState(origin = StationRef("마포", "마포"), dest = StationRef("강남", "강남")), onSwap = { swapped++ })
            }
        }
        rule.onNodeWithContentDescription(Copy.SWAP).performClick()
        assertEquals(1, swapped)
    }

    @Test fun locationIconInOriginSlotUsesLocation() {
        var used = 0
        rule.setContent { AppTheme(darkTheme = false) { RouteEditContent(RouteFlowState(), onUseLocation = { used++ }) } }
        rule.onNodeWithContentDescription(Copy.CURRENT_LOCATION).performClick()
        assertEquals(1, used)
    }
}
