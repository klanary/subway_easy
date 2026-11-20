package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StateViewsTest {
    @get:Rule val rule = createComposeRule()
    private var retried = 0

    private fun set(content: @Composable () -> Unit) = rule.setContent { AppTheme(darkTheme = false) { content() } }

    @Test fun errorShowsRetry() {
        set { StateMessage(StateKind.Error, Copy.SERVER_PROBLEM, StateSize.Screen, onRetry = { retried++ }) }
        rule.onNodeWithText(Copy.RETRY).performClick()
        assertEquals(1, retried)
    }

    @Test fun offlineShowsRetry() {
        set { StateMessage(StateKind.Offline, Copy.OFFLINE, StateSize.Screen, onRetry = { retried++ }) }
        rule.onNodeWithText(Copy.RETRY).performClick()
        assertEquals(1, retried)
    }

    @Test fun inlineErrorShowsRetry() {
        set { StateMessage(StateKind.Error, Copy.NETWORK_ERROR, StateSize.Inline, onRetry = { retried++ }) }
        rule.onNodeWithText(Copy.RETRY).performClick()
        assertEquals(1, retried)
    }

    @Test fun emptyHasNoRetry() {
        set { StateMessage(StateKind.Empty, Copy.NO_RESULTS, StateSize.Screen, onRetry = {}) }
        rule.onNodeWithText(Copy.RETRY).assertDoesNotExist()
    }

    @Test fun loadingIsAnnounced() {
        set { StateLoading(StateSize.Screen) }
        rule.onNodeWithContentDescription(Copy.LOADING).assertExists()
    }

    @Test fun staleNotice_clickRetries() {
        set { StaleNotice(onRetry = { retried++ }) }
        rule.onNodeWithText(Copy.STALE).performClick()
        assertEquals(1, retried)
    }

    @Test fun inlineMessage_doesNotFillHeight() {
        set {
            Box(Modifier.height(800.dp)) {
                StateMessage(StateKind.Empty, Copy.NO_RESULTS, StateSize.Inline, null, Modifier.testTag("m"))
            }
        }
        val b = rule.onNodeWithTag("m").getUnclippedBoundsInRoot()
        val h = b.bottom - b.top
        assert(h <= 200.dp) { "inline height $h" }
    }
}
