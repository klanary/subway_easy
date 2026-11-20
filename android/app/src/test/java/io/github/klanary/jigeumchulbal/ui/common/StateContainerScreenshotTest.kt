package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.material3.Text
import org.robolectric.RobolectricTestRunner
import io.github.klanary.jigeumchulbal.ui.screenshot.snapBothThemes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class StateContainerScreenshotTest {
    @Test fun loading() = snapBothThemes("state_loading") { StateContainer(UiState.Loading, onRetry = {}, emptyText = Copy.NO_RESULTS) { } }
    @Test fun empty() = snapBothThemes("state_empty") { StateContainer(UiState.Empty, {}, Copy.NO_RESULTS) { } }
    @Test fun error() = snapBothThemes("state_error") { StateContainer(UiState.Error(Copy.SERVER_PROBLEM), {}, Copy.NO_RESULTS) { } }
    @Test fun offline() = snapBothThemes("state_offline") { StateContainer(UiState.Offline, {}, Copy.NO_RESULTS) { } }
    @Test fun stale() = snapBothThemes("state_stale") {
        StateContainer(UiState.Content("내용", stale = true), {}, Copy.NO_RESULTS) { Text(it) }
    }
}
