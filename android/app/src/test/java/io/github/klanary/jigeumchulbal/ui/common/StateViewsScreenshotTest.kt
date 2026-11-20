package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.klanary.jigeumchulbal.ui.screenshot.snapBothThemes
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class StateViewsScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val texts = mapOf(StateKind.Empty to Copy.NO_RESULTS, StateKind.Error to Copy.SERVER_PROBLEM, StateKind.Offline to Copy.OFFLINE)

    @Test fun screenMessages() = texts.forEach { (kind, text) ->
        rule.snapBothThemes("views_screen_${kind.name.lowercase()}") { StateMessage(kind, text, StateSize.Screen, onRetry = {}) }
    }

    /** 시트 안처럼 위에 내용이 있고 그 아래에 인라인 상태가 이어지는 모습. */
    @Test fun inlineMessages() = texts.forEach { (kind, text) ->
        rule.snapBothThemes("views_inline_${kind.name.lowercase()}") {
            Column(Modifier.padding(16.dp)) {
                Text("시청")
                StateMessage(kind, text, StateSize.Inline, onRetry = {})
            }
        }
    }

    @Test fun loading() {
        rule.snapBothThemes("views_loading_screen") { StateLoading(StateSize.Screen) }
        rule.snapBothThemes("views_loading_inline") { Column(Modifier.padding(16.dp)) { Text("시청"); StateLoading(StateSize.Inline) } }
    }

    @Test fun staleNotice() = rule.snapBothThemes("views_stale") {
        Column(Modifier.padding(16.dp)) {
            StaleNotice(onRetry = {})
            Text("1호선 상행 종각 방면")
            Text("청량리행 곧 도착 · 그다음 1분")
        }
    }

    @Test fun screenMessage_largeFont() = rule.snapBothThemes("views_screen_error_font130", fontScale = 1.3f) {
        StateMessage(StateKind.Error, Copy.SERVER_PROBLEM, StateSize.Screen, onRetry = {})
    }

    @Test fun screenMessage_longText() = rule.snapBothThemes("views_screen_error_long") {
        StateMessage(
            StateKind.Error,
            "열차 정보를 가져오는 서버가 지금 응답하지 않아요. 보통 1~2분 안에 돌아오니 잠시 기다려 주세요. 계속 안 되면 앱을 다시 열어 주세요.",
            StateSize.Screen,
            onRetry = {},
        )
    }
}
