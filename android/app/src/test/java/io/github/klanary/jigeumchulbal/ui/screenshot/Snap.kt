package io.github.klanary.jigeumchulbal.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.klanary.jigeumchulbal.ui.theme.AppTheme

/** 기준 이미지 폴더. app/build.gradle.kts의 roborazzi.outputDir과 같다. */
private const val DIR = "src/test/screenshots"

/**
 * [content]를 라이트·다크 테마로 한 번씩 그려 `<name>_light.png`, `<name>_dark.png`로 찍는다.
 * 테스트 클래스에는 @RunWith(RobolectricTestRunner), @GraphicsMode(NATIVE), @Config(sdk=[35], qualifiers=Pixel 8 폭)를 단다.
 */
fun snapBothThemes(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
    for (dark in listOf(false, true)) {
        captureRoboImage("$DIR/${name}_${if (dark) "dark" else "light"}.png") {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}
