package io.github.klanary.jigeumchulbal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 간격 단계(스펙 6.3): 4 · 8 · 12 · 16 · 24 dp. 화면 좌우 여백은 [l]. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

/** 모서리(스펙 6.3): 카드·시트 16, 버튼·입력 14, 칩·배지 완전 둥근. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

val ButtonShape = RoundedCornerShape(14.dp)
val PillShape = RoundedCornerShape(50)
