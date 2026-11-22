package io.github.klanary.jigeumchulbal.ui.common

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

private val ROW = 48.dp

/**
 * 시각 고르기(수동 검토 2026-11-22): 지금 시각에서 시작해 시·분을 위아래로 굴려 맞춘다.
 * 가운데 줄이 고른 값이다.
 */
@Composable
fun TimeWheelDialog(initialHour: Int, initialMinute: Int, onConfirm: (hour: Int, minute: Int) -> Unit, onDismiss: () -> Unit) {
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Copy.PICK_TIME_TITLE) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(List(24) { "%02d".format(it) }, initialHour, { hour = it }, "wheel-hour")
                Text(":", style = MaterialTheme.typography.headlineMedium)
                Wheel(List(60) { "%02d".format(it) }, initialMinute, { minute = it }, "wheel-minute")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hour, minute) }) { Text(Copy.OK) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Copy.CANCEL) } },
    )
}

/**
 * 화면 안 시각 휠(수동 검토 2026-11-22): 오전·오후, 시(12시간), 분을 위아래로 굴린다.
 * 처음에는 [initialHour]:[initialMinute](0~23시)에서 시작하고, 바뀔 때마다 [onChange]로 24시간 값을 넘긴다.
 * 고른 값을 실제로 쓰는 건 부르는 쪽의 [적용] 버튼이다(굴리기만으로 다시 불러오지 않는다).
 */
@Composable
fun InlineTimeWheels(initialHour: Int, initialMinute: Int, onChange: (hour: Int, minute: Int) -> Unit, modifier: Modifier = Modifier) {
    var pm by remember { mutableIntStateOf(if (initialHour >= 12) 1 else 0) }
    var hour12 by remember { mutableIntStateOf(initialHour % 12) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    val hour = hour12 + pm * 12
    LaunchedEffect(hour, minute) { onChange(hour, minute) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Wheel(listOf("오전", "오후"), pm, { pm = it }, "inline-ampm", INLINE_ROW, 56.dp, MaterialTheme.typography.titleMedium)
        Wheel(List(12) { if (it == 0) "12" else "$it" }, hour12, { hour12 = it }, "inline-hour", INLINE_ROW, 44.dp, MaterialTheme.typography.titleMedium)
        Text(":", style = MaterialTheme.typography.titleMedium)
        Wheel(List(60) { "%02d".format(it) }, initialMinute, { minute = it }, "inline-minute", INLINE_ROW, 44.dp, MaterialTheme.typography.titleMedium)
    }
}

private val INLINE_ROW = 36.dp

@Composable
private fun Wheel(
    labels: List<String>,
    initial: Int,
    onSelected: (Int) -> Unit,
    tag: String,
    row: Dp = ROW,
    width: Dp = 72.dp,
    bigStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    val count = labels.size
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val rowPx = with(LocalDensity.current) { row.toPx() }
    // 위아래 여백이 한 줄씩이라 맨 위에 보이는 줄이 곧 가운데 줄이다.
    val centered by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 }
    }
    LaunchedEffect(centered) { onSelected(centered.coerceIn(0, count - 1)) }
    LazyColumn(
        Modifier.width(width).height(row * 3).testTag(tag),
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = row),
    ) {
        items(count) { i ->
            Box(Modifier.fillMaxWidth().height(row), contentAlignment = Alignment.Center) {
                Text(
                    labels[i],
                    style = if (i == centered) bigStyle else MaterialTheme.typography.bodyLarge,
                    color = if (i == centered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
