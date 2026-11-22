package io.github.klanary.jigeumchulbal.ui.linemap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.klanary.jigeumchulbal.R

/** 이전역에서 이번 역까지 걸린다고 보는 시간. 남은 시간을 선로 위 위치로 바꿀 때 쓴다. */
private const val SEGMENT_SEC = 150f

private fun bare(name: String) = name.substringBefore("(").removeSuffix("역")

/**
 * 열차가 이전역(0)→이번 역(1) 사이 어디쯤인지(수동 검토 2026-11-22). 남은 시간을 모르면 null.
 * 이전역보다 먼 곳에 있으면 이전역 쪽 끝(0)에 둔다(그때는 [trainFarAway]로 역 이름을 따로 보인다).
 */
fun trainProgress(remainingSec: Int?, current: String?, prev: String?, here: String): Float? {
    if (remainingSec == null) return null
    val cur = current?.let(::bare)
    if (cur != null && cur == bare(here)) return 1f
    if (cur != null && prev != null && cur != bare(prev)) return 0f
    return 1f - (remainingSec / SEGMENT_SEC).coerceIn(0f, 1f)
}

/** 열차가 이전역보다 먼 곳에 있을 때만 그 역 이름. */
fun trainFarAway(current: String?, prev: String?, here: String): String? {
    val cur = current?.let(::bare) ?: return null
    if (cur == bare(here) || (prev != null && cur == bare(prev))) return null
    return current
}

private val EDGE = 12.dp
private val ICON = 22.dp

/**
 * 선로 그림: 위에는 열차(이전역→이번 역 사이를 미끄러지듯 다가온다), 아래에는 이전역·이번 역·다음역.
 * 열차는 이전역 쪽에서 출발해 [progress] 자리까지 움직인다.
 */
@Composable
fun TrainTrack(prev: String?, here: String, next: String?, progress: Float?, farAway: String?, color: Color, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val moving = remember { Animatable(0f) }
    LaunchedEffect(progress) { progress?.let { moving.animateTo(it, tween(1200)) } }
    val description = buildString {
        prev?.let { append("이전역 $it, ") }
        append("이번 역 $here")
        next?.let { append(", 다음역 $it") }
        farAway?.let { append(". 열차는 지금 $it") }
    }
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
            val span = (maxWidth - EDGE * 2) / 2
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height - 6.dp.toPx()
                val x0 = EDGE.toPx()
                val x2 = size.width - EDGE.toPx()
                val x1 = (x0 + x2) / 2
                val w = 3.dp.toPx()
                drawLine(outline, Offset(x0, y), Offset(x2, y), w, StrokeCap.Round)
                drawLine(color, Offset(x0, y), Offset(x1, y), w, StrokeCap.Round)
                listOf(x0, x2).forEach {
                    drawCircle(surface, 5.dp.toPx(), Offset(it, y))
                    drawCircle(outline, 5.dp.toPx(), Offset(it, y), style = Stroke(2.dp.toPx()))
                }
                drawCircle(surface, 7.dp.toPx(), Offset(x1, y))
                drawCircle(color, 7.dp.toPx(), Offset(x1, y), style = Stroke(3.dp.toPx()))
            }
            if (progress != null) {
                Icon(
                    painterResource(R.drawable.ic_notification),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.offset(x = EDGE + span * moving.value - ICON / 2).size(ICON),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val style = MaterialTheme.typography.labelMedium
            Text(prev.orEmpty(), Modifier.weight(1f), style = style, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(here, Modifier.weight(1f), style = style, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(next.orEmpty(), Modifier.weight(1f), style = style, color = muted, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        farAway?.let { Text("열차는 지금 $it", style = MaterialTheme.typography.labelMedium, color = muted) }
    }
}
