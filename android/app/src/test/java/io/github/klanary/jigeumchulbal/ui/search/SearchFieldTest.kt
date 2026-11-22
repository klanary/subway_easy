package io.github.klanary.jigeumchulbal.ui.search

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 한국어 조합(수동 검토 2026-11-22): 검색 칸은 입력을 바로 그 칸에 보여야 한다.
 * 상태(StateFlow)가 한 박자 늦게 돌아오면 키보드가 조합 중인 글자를 덮어써 "방이"가 조합되지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun typedTextShowsImmediatelyEvenBeforeStateCatchesUp() {
        val sent = mutableListOf<String>()
        rule.setContent { AppTheme(darkTheme = false) { SearchContent(state = SearchUiState(), onQueryChange = { sent += it }) } }
        rule.onNode(hasSetTextAction()).performTextInput("방이")
        rule.onNode(hasSetTextAction()).assertTextContains("방이")
        assertEquals("방이", sent.last())
    }
}
