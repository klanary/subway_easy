package io.github.klanary.jigeumchulbal.ui.linemap

import android.app.TimePickerDialog
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
import androidx.compose.material3.LinearProgressIndicator
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.runAutoRefresh() }
    }
    val context = LocalContext.current
    val station = StationRef(stationId, stationId)
    val time = (state as? UiState.Content)?.data?.time ?: SheetTime.NOW

    // 반만 열리면 버튼이 시스템 내비게이션 바에 가려진다(2026-11-12 실기기). 처음부터 다 연다.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // 시스템 내비게이션 바에 버튼이 가리지 않게 하고, 열차 줄이 많으면 스크롤한다(2026-11-12 실기기).
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stationId, style = MaterialTheme.typography.headlineSmall)
            (state as? UiState.Content)?.data?.lines?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRole(TripRole.FROM, station) }, enabled = draft.canSet(TripRole.FROM, station)) {
                    Text(Copy.AS_FROM)
                }
                Button(onClick = { onRole(TripRole.TO, station) }, enabled = draft.canSet(TripRole.TO, station)) {
                    Text(Copy.AS_TO)
                }
                if (draft.from != null && draft.to != null) {
                    OutlinedButton(onClick = { onRole(TripRole.VIA, station) }, enabled = draft.canSet(TripRole.VIA, station)) {
                        Text(Copy.AS_VIA)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(time == SheetTime.NOW, { viewModel.select(SheetTime.NOW) }, { Text(Copy.SHEET_NOW) })
                FilterChip(time == SheetTime.PLUS_1H, { viewModel.select(SheetTime.PLUS_1H) }, { Text(Copy.SHEET_PLUS_1H) })
                FilterChip(time == SheetTime.PLUS_2H, { viewModel.select(SheetTime.PLUS_2H) }, { Text(Copy.SHEET_PLUS_2H) })
                FilterChip(time == SheetTime.CUSTOM, {
                    val now = LocalTime.now()
                    TimePickerDialog(context, { _, h, m ->
                        val at = LocalDate.now().atTime(h, m).atZone(ZoneId.systemDefault()).toInstant()
                        viewModel.select(SheetTime.CUSTOM, at)
                    }, now.hour, now.minute, true).show()
                }, { Text(Copy.SHEET_PICK_TIME) })
            }
            when (val s = state) {
                UiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is UiState.Content -> SheetTrains(s.data)
                is UiState.Error -> Text(s.message)
                UiState.Offline -> Text(Copy.OFFLINE)
                UiState.Empty -> Text(Copy.NO_TRAIN_INFO)
            }
            TextButton(onClick = onDismiss) { Text(Copy.CLOSE) }
        }
    }
}

@Composable
private fun SheetTrains(ui: StationSheetUi) {
    if (ui.fromTimetable) Text(Copy.BY_TIMETABLE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    ui.groups.forEach { group ->
        Column {
            val head = listOfNotNull(group.line, group.direction, group.towards?.let { "$it 방면" }).joinToString(" ")
            Text(head, style = MaterialTheme.typography.titleSmall)
            Text(group.trains.joinToString(if (ui.time == SheetTime.NOW) " · " else ", "), style = MaterialTheme.typography.bodyLarge)
        }
    }
    ui.noTimetableLines.forEach { line ->
        Text("$line · ${Copy.NO_TIMETABLE_LINE}", style = MaterialTheme.typography.bodyMedium)
    }
    ui.unsupportedLines.forEach { line ->
        Text("$line · ${Copy.UNSUPPORTED_LINE}", style = MaterialTheme.typography.bodyMedium)
    }
}
