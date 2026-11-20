package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 호선 이름을 호선 색 알약으로(역 시트·검색·경로 카드·경로 상세가 함께 쓴다). */
@Composable
fun LineBadge(line: String, modifier: Modifier = Modifier) {
    Text(
        line,
        color = badgeTextColor(lineColor(line)),
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(Color(lineColor(line)), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 밝은 호선 색(9호선·수인분당선 등)에는 검정, 어두운 색에는 흰 글자(최종 리뷰: 흰 글자 대비 1.7~2.2:1). */
fun badgeTextColor(argb: Long): Color = if (Color(argb).luminance() > 0.4f) Color.Black else Color.White

/** 호선 배지를 나란히. [arrow]면 사이에 → 를 둔다("5호선 → 9호선"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineBadges(lines: List<String>, arrow: Boolean = false, modifier: Modifier = Modifier) {
    // 큰 글꼴·호선이 많은 역에서도 넘치지 않게 줄을 바꾼다(최종 리뷰).
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { i, line ->
            if (arrow && i > 0) Text("→", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineBadge(line)
        }
    }
}

/** 시계 아이콘(최근 검색 표시). 기본 아이콘 모음에 없어 직접 그린다. */
val ClockIcon: ImageVector = ImageVector.Builder("Clock", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        // 원
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 11.99f, y1 = 3f)
        // 바늘
        moveTo(12f, 7f)
        lineTo(12f, 12f)
        lineTo(15.5f, 14f)
    }
}.build()

/** 위아래 바꾸기 아이콘(⇅). */
val SwapIcon: ImageVector = ImageVector.Builder("Swap", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(8f, 20f); lineTo(8f, 4f); moveTo(4f, 8f); lineTo(8f, 4f); lineTo(12f, 8f)
        moveTo(16f, 4f); lineTo(16f, 20f); moveTo(12f, 16f); lineTo(16f, 20f); lineTo(20f, 16f)
    }
}.build()
