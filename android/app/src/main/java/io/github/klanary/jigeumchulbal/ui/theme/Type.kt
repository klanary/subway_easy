package io.github.klanary.jigeumchulbal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 앱 글꼴. Pretendard 파일을 res/font에 넣으면 여기서 바꾼다(스펙 6.2, 내려받기는 사용자 허락 뒤).
 * 그 전에는 시스템 글꼴을 쓴다.
 */
val AppFont: FontFamily = FontFamily.Default

/** 분이 바뀌어도 줄이 흔들리지 않게 숫자 폭을 고정한다. */
private const val TABULAR = "tnum"

private fun style(size: Int, weight: FontWeight, line: Int, tabular: Boolean = false) = TextStyle(
    fontFamily = AppFont,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontFeatureSettings = if (tabular) TABULAR else null,
)

/**
 * 글자 단계(스펙 6.2): 큰 숫자 32 · 제목 22 · 소제목 17 · 본문 15 · 보조 13 · 라벨 12.
 * 화면 코드는 M3 칸 이름을 그대로 쓴다. 큰 숫자(남은 시간·총 시간)는 headlineMedium.
 */
val AppTypography: Typography = Typography(
    displayLarge = style(40, FontWeight.Bold, 48, tabular = true),
    displayMedium = style(36, FontWeight.Bold, 44, tabular = true),
    displaySmall = style(32, FontWeight.Bold, 40, tabular = true),
    headlineLarge = style(32, FontWeight.Bold, 40, tabular = true),
    headlineMedium = style(32, FontWeight.Bold, 40, tabular = true),
    headlineSmall = style(22, FontWeight.Bold, 30),
    titleLarge = style(22, FontWeight.Bold, 30),
    titleMedium = style(17, FontWeight.SemiBold, 24, tabular = true),
    titleSmall = style(15, FontWeight.SemiBold, 22),
    bodyLarge = style(15, FontWeight.Normal, 22, tabular = true),
    bodyMedium = style(15, FontWeight.Normal, 22),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(14, FontWeight.SemiBold, 20),
    labelMedium = style(12, FontWeight.SemiBold, 16),
    labelSmall = style(12, FontWeight.SemiBold, 16),
)
