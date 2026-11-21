package io.github.klanary.jigeumchulbal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * 2차 다듬기 색 구성(스펙 docs/superpowers/specs/2026-11-21-android-visual-refresh-design.md 6.1).
 * 방향 B "깔끔한 교통 도구": 흰 바탕·검정 글자, 색은 호선 색과 강조색(신호 파랑) 하나에만 쓴다.
 * 카드·시트가 회색으로 뜨지 않게 surfaceContainer* 단계는 모두 면(surface)과 같게 둔다.
 */

private val LightSurface = Color(0xFFFFFFFF)
private val LightOutline = Color(0xFFE6E8EC)

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EEFF),
    onPrimaryContainer = Color(0xFF1E3FB0),
    secondary = Color(0xFF2F5BEA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EEFF),
    onSecondaryContainer = Color(0xFF1E3FB0),
    tertiary = Color(0xFFB54708),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF0C7),
    onTertiaryContainer = Color(0xFFB54708),
    error = Color(0xFFD92D20),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111418),
    surface = LightSurface,
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF5B6472),
    outline = LightOutline,
    outlineVariant = LightOutline,
    inverseSurface = Color(0xFF111418),
    inverseOnSurface = Color(0xFFF2F4F7),
    inversePrimary = Color(0xFF7C9BFF),
    surfaceTint = LightSurface,
    surfaceBright = LightSurface,
    surfaceDim = Color(0xFFF1F2F4),
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurface,
)

private val DarkSurface = Color(0xFF171B22)
private val DarkOutline = Color(0xFF2C3340)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C9BFF),
    onPrimary = Color(0xFF0B1B4D),
    primaryContainer = Color(0xFF1F2E66),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF7C9BFF),
    onSecondary = Color(0xFF0B1B4D),
    secondaryContainer = Color(0xFF1F2E66),
    onSecondaryContainer = Color(0xFFDCE4FF),
    tertiary = Color(0xFFFDB022),
    onTertiary = Color(0xFF3D2A0A),
    tertiaryContainer = Color(0xFF3D2A0A),
    onTertiaryContainer = Color(0xFFFDB022),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0B0B),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFF2F4F7),
    surface = DarkSurface,
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFF9AA3AF),
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    inverseSurface = Color(0xFFF2F4F7),
    inverseOnSurface = Color(0xFF111418),
    inversePrimary = Color(0xFF2F5BEA),
    surfaceTint = DarkSurface,
    surfaceBright = Color(0xFF232833),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = DarkSurface,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurface,
)

/** WCAG 대비 비율(1~21). 글자와 바탕이 4.5 이상이면 AA. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
