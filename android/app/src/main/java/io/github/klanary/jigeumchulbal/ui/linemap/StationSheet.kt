package io.github.klanary.jigeumchulbal.ui.linemap

import io.github.klanary.jigeumchulbal.ui.common.lineColor
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.klanary.jigeumchulbal.ui.common.TimeWheelDialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import io.github.klanary.jigeumchulbal.ui.theme.ButtonShape
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.klanary.jigeumchulbal.ui.common.StaleNotice
import io.github.klanary.jigeumchulbal.ui.common.StateKind
import io.github.klanary.jigeumchulbal.ui.common.StateLoading
import io.github.klanary.jigeumchulbal.ui.common.StateMessage
import io.github.klanary.jigeumchulbal.ui.common.StateSize
import io.github.klanary.jigeumchulbal.ui.common.LineBadge
import io.github.klanary.jigeumchulbal.ui.common.LineBadges
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.common.pickedTime
import java.time.LocalTime

/**
 * 역 시트(재설계 스펙 4.2): 역 이름·호선, 방향별 다음 열차, 시각 버튼, [출발로] [도착으로] [경유로].
 * [draft]로 어느 버튼을 누를 수 있는지 정한다(같은 역은 두 역할을 못 한다, 경유는 출발·도착 뒤에만).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSheet(
    stationId: String,
    draft: TripDraft,
    onRole: (TripRole, StationRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val viewModel = appViewModel(key = "sheet-$stationId") { StationSheetViewModel(stationId, it.stationRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.runAutoRefresh() }
    }
    val context = LocalContext.current
    val station = StationRef(stationId, stationId)
    // 시각 고르기: 지금 시각에서 시·분을 굴려 맞춘다(수동 검토 2026-11-22)
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val now = LocalTime.now()
        TimeWheelDialog(now.hour, now.minute, onConfirm = { h, m ->
            picking = false
            viewModel.select(SheetTime.CUSTOM, pickedTime(h, m))
        }, onDismiss = { picking = false })
    }

    // 반만 열리면 버튼이 시스템 내비게이션 바에 가려진다(2026-11-12 실기기). 처음부터 다 연다.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        StationSheetContent(
            stationId = stationId,
            state = state,
            lines = lines,
            draft = draft,
            onRole = { role -> onRole(role, station) },
            onRetry = viewModel::retry,
            onTime = { viewModel.select(it) },
            onPickTime = { picking = true },
            onDismiss = onDismiss,
        )
    }
}

/**
 * 역 시트 안쪽을 그린다(상태만 받는다). 시트 틀·ViewModel·시각 고르기 대화상자는 [StationSheet]가 잇는다.
 * 화면 다듬기 Task 4 합의: 호선 색 배지 아래 방향을 묶고, 첫 열차의 남은 시간을 크게, 막차·급행·근거는 작은 표식.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StationSheetContent(
    stationId: String,
    state: UiState<StationSheetUi>,
    draft: TripDraft,
    lines: List<String> = (state as? UiState.Content)?.data?.lines.orEmpty(),
    onRole: (TripRole) -> Unit = {},
    onTime: (SheetTime) -> Unit = {},
    onPickTime: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val station = StationRef(stationId, stationId)
    val content = (state as? UiState.Content)?.data
    val time = content?.time ?: SheetTime.NOW
    // 시스템 내비게이션 바에 버튼이 가리지 않게 하고, 열차 줄이 많으면 스크롤한다(2026-11-12 실기기).
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 역 이름 아래에 호선 배지(줄바꿈). 한 줄에 몰면 호선이 많은 역·큰 글꼴에서 넘쳤다(최종 리뷰).
        Text(stationId, style = MaterialTheme.typography.headlineSmall)
        if (lines.isNotEmpty()) LineBadges(lines)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRole(TripRole.FROM) }, shape = ButtonShape, enabled = draft.canSet(TripRole.FROM, station)) {
                Text(Copy.AS_FROM)
            }
            // 출발로 · 경유로 · 도착으로(경유는 언제든 고를 수 있다, 수동 검토 2026-11-22)
            OutlinedButton(onClick = { onRole(TripRole.VIA) }, shape = ButtonShape, enabled = draft.canSet(TripRole.VIA, station)) {
                Text(Copy.AS_VIA)
            }
            Button(onClick = { onRole(TripRole.TO) }, shape = ButtonShape, enabled = draft.canSet(TripRole.TO, station)) {
                Text(Copy.AS_TO)
            }
        }
        // 큰 글꼴에서도 넘치지 않게 줄을 바꾼다(2차 다듬기 Task 4).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(time == SheetTime.NOW, { onTime(SheetTime.NOW) }, { Text(Copy.SHEET_NOW) })
            FilterChip(time == SheetTime.PLUS_1H, { onTime(SheetTime.PLUS_1H) }, { Text(Copy.SHEET_PLUS_1H) })
            FilterChip(time == SheetTime.PLUS_2H, { onTime(SheetTime.PLUS_2H) }, { Text(Copy.SHEET_PLUS_2H) })
            val picked = content?.customLabel?.takeIf { time == SheetTime.CUSTOM }
            FilterChip(time == SheetTime.CUSTOM, onPickTime, { Text(picked?.let { "$it ${Copy.SHEET_AT}" } ?: Copy.SHEET_PICK_TIME) })
        }
        when (state) {
            UiState.Loading -> StateLoading(StateSize.Inline)
            is UiState.Content -> {
                if (state.stale) StaleNotice(onRetry)
                SheetTrains(state.data)
            }
            is UiState.Error -> StateMessage(
                if (state.message == Copy.NO_TRAIN_INFO) StateKind.Empty else StateKind.Error,
                state.message,
                StateSize.Inline,
                onRetry,
            )
            UiState.Offline -> StateMessage(StateKind.Offline, Copy.OFFLINE, StateSize.Inline, onRetry)
            UiState.Empty -> StateMessage(StateKind.Empty, Copy.NO_TRAIN_INFO, StateSize.Inline, onRetry = null)
        }
        TextButton(onClick = onDismiss) { Text(Copy.CLOSE) }
    }
}

/** 작은 표식: [막차] [급행] [시간표]. */
@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun SheetTrains(ui: StationSheetUi) {
    // 호선별로 묶는다(서버가 주는 순서에서 처음 나온 호선 순서대로).
    val byLine = ui.groups.groupBy { it.line }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        byLine.forEach { (line, groups) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LineBadge(line)
                groups.forEach { DirectionRow(it, ui.name) }
            }
        }
        (ui.noTimetableLines.map { it to Copy.NO_TIMETABLE_LINE } + ui.unsupportedLines.map { it to Copy.UNSUPPORTED_LINE })
            .forEach { (line, message) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LineBadge(line)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
    }
}

@Composable
private fun DirectionRow(group: DirectionTrains, here: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(start = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val head = listOfNotNull(group.direction, group.towards?.let { "$it 방면" }).joinToString(" · ")
            Text(head, style = MaterialTheme.typography.labelLarge, color = muted)
            group.basis?.let { Tag(it, MaterialTheme.colorScheme.tertiary) }
        }
        // 이전역·이번 역·다음역과 다가오는 열차(수동 검토 2026-11-22)
        if (group.prevStation != null || group.nextStation != null) {
            TrainTrack(
                group.prevStation, here, group.nextStation, group.progress, group.farAway,
                Color(lineColor(group.line)), Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
        }
        val first = group.trains.firstOrNull() ?: return@Column
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (first.clock) {
                Text(first.time, style = MaterialTheme.typography.headlineMedium)
                first.destination?.let { Text("${it}행", style = MaterialTheme.typography.bodyLarge) }
            } else {
                first.destination?.let { Text("${it}행", style = MaterialTheme.typography.bodyLarge) }
                Text(first.time, style = MaterialTheme.typography.headlineMedium)
                // 남은 시간 옆 도착 시각(수동 검토 2026-11-22)
                first.arriveAt?.let { Text(it + " " + Copy.ARRIVES, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
            if (first.isLast) Tag(Copy.LAST_TRAIN, MaterialTheme.colorScheme.error)
            if (first.isExpress) Tag(Copy.EXPRESS, MaterialTheme.colorScheme.primary)
        }
        val rest = group.trains.drop(1)
        if (rest.isNotEmpty()) {
            val text = if (first.clock) rest.joinToString(" · ") { it.text() } else rest.joinToString(" · ") { t -> "그다음 " + t.time + (t.arriveAt?.let { "($it)" } ?: "") }
            val marks = rest.flatMap { listOfNotNull(Copy.LAST_TRAIN.takeIf { _ -> it.isLast }, Copy.EXPRESS.takeIf { _ -> it.isExpress }) }.distinct()
            Text(
                (listOf(text) + marks.map { "($it 포함)" }).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }
    }
}
