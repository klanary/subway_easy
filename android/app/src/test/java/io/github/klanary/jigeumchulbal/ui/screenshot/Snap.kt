package io.github.klanary.jigeumchulbal.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme
import java.util.WeakHashMap

/** 기준 이미지 폴더. app/build.gradle.kts의 roborazzi.outputDir과 같다. */
private const val DIR = "src/test/screenshots"

/** 한 테스트 안에서 그릴 내용·테마를 바꿔 가며 찍는다(setContent는 테스트마다 한 번만 부를 수 있다). */
private class SnapHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var dark by mutableStateOf(false)
    var fontScale by mutableFloatStateOf(1f)
}

/** 스크린샷 테스트가 쓰는 규칙: `@get:Rule val rule = createAndroidComposeRule<ComponentActivity>()`. */
typealias SnapRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

private val hosts = WeakHashMap<SnapRule, SnapHost>()

/**
 * [content]를 라이트·다크 테마로 한 번씩 그려 `<name>_light.png`, `<name>_dark.png`로 찍는다.
 * 시계를 손으로 돌린다: 로딩 원처럼 끝없이 도는 애니메이션이 있으면 "멈출 때까지 기다리기"가 장당 10분 넘게 걸렸다(Task 3).
 * 테스트 클래스에는 `@get:Rule val rule = createAndroidComposeRule<ComponentActivity>()`, @RunWith(RobolectricTestRunner), @GraphicsMode(NATIVE),
 * @Config(sdk=[35], qualifiers=Pixel 8 폭)를 단다.
 */
fun SnapRule.snapBothThemes(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val host = hosts.getOrPut(this) {
        SnapHost().also { h ->
            mainClock.autoAdvance = false
            setContent {
                val shown = h.content ?: return@setContent
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, h.fontScale)) {
                    AppTheme(darkTheme = h.dark) {
                        Surface(Modifier.fillMaxSize()) { key(shown) { shown() } }
                    }
                }
            }
        }
    }
    for (dark in listOf(false, true)) {
        host.content = content
        host.fontScale = fontScale
        host.dark = dark
        // 처음 한 번은 창이 붙고 첫 프레임이 그려질 때까지 두 번 돌려야 한다(한 번이면 라이트가 빈 그림으로 찍혔다).
        repeat(2) {
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        // 창 전체를 직접 그려 찍는다(compose 루트 찍기는 시계를 멈추면 그림을 못 찾았다).
        activity.window.decorView.captureRoboImage("$DIR/${name}_${if (dark) "dark" else "light"}.png")
    }
}
