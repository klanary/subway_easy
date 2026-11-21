package io.github.klanary.jigeumchulbal.ui.theme

import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.klanary.jigeumchulbal.R
import io.github.klanary.jigeumchulbal.ui.screenshot.snapBothThemes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 앱 아이콘을 홈 화면처럼 원형·둥근 사각 마스크와 작은 크기로 그려 본다(2차 다듬기 Task 2). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class IconScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Masked(@DrawableRes icon: Int, shape: Shape, size: Dp) {
        Box(Modifier.size(size).clip(shape).background(Color(0xFF2F5BEA)), contentAlignment = Alignment.Center) {
            // 적응형 아이콘은 108dp 그림의 가운데 72dp가 보인다: 보이는 크기의 1.5배로 그린다.
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(size * 1.5f))
        }
    }

    private fun snapIcon(name: String, @DrawableRes icon: Int) = rule.snapBothThemes(name) {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Masked(icon, CircleShape, 96.dp)
            Masked(icon, RoundedCornerShape(24.dp), 96.dp)
            Masked(icon, CircleShape, 48.dp)
        }
    }

    @Test fun icon() = snapIcon("icon", R.drawable.ic_launcher_foreground)
}
