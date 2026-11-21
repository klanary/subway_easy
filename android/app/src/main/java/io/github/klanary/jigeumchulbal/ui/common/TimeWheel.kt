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
                Wheel(count = 24, initial = initialHour, onSelected = { hour = it }, tag = "wheel-hour")
                Text(":", style = MaterialTheme.typography.headlineMedium)
                Wheel(count = 60, initial = initialMinute, onSelected = { minute = it }, tag = "wheel-minute")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hour, minute) }) { Text(Copy.OK) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Copy.CANCEL) } },
    )
}

@Composable
private fun Wheel(count: Int, initial: Int, onSelected: (Int) -> Unit, tag: String) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    // 위아래 여백이 한 줄씩이라 맨 위에 보이는 줄이 곧 가운데 줄이다.
    val centered by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 }
    }
    LaunchedEffect(centered) { onSelected(centered.coerceIn(0, count - 1)) }
    LazyColumn(
        Modifier.width(72.dp).height(ROW * 3).testTag(tag),
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = ROW),
    ) {
        items(count) { i ->
            Box(Modifier.fillMaxWidth().height(ROW), contentAlignment = Alignment.Center) {
                Text(
                    "%02d".format(i),
                    style = if (i == centered) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
                    color = if (i == centered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
