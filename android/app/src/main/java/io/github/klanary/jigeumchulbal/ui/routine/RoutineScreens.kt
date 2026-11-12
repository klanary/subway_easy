package io.github.klanary.jigeumchulbal.ui.routine

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.App
import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.routine.RoutineRepository
import io.github.klanary.jigeumchulbal.routine.dayBit
import io.github.klanary.jigeumchulbal.routine.daysLabel
import io.github.klanary.jigeumchulbal.routine.minuteLabel
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.settings.PermissionRow
import io.github.klanary.jigeumchulbal.ui.settings.permissionSettingsIntent
import java.time.DayOfWeek
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")

fun notificationsEnabled(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

/**
 * 알림 권한이 있으면 바로 true로, 없으면 요청한 뒤 결과로 [onResult]를 부른다(Android 13 미만은 앱 알림 설정만 본다).
 */
@Composable
fun rememberNotificationPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return remember(launcher) {
        {
            if (notificationsEnabled(context)) {
                onResult(true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onResult(false)
            }
        }
    }
}

/** 루틴 등록·편집 화면(재설계 스펙 4.6). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutineEditScreen(routineId: Long?, navController: NavController) {
    val context = LocalContext.current
    val viewModel = appViewModel(key = "routine-${routineId ?: "new"}") {
        val draft = it.pendingRoutine.also { _ -> it.pendingRoutine = null }
        RoutineEditViewModel(routineId, draft, it.routineRepository, it.routeRepository, it.json)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askPermission = rememberNotificationPermission { granted -> viewModel.save(granted) }
    LaunchedEffect(state.saved, state.message) {
        if (state.saved && state.message == null) navController.popBackStack()
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeMessage() },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeMessage()
                    if (state.saved) navController.popBackStack()
                }) { Text(Copy.OK) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Copy.ROUTINE_TITLE) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(listOfNotNull(state.from?.name, state.via?.name, state.to?.name).joinToString(" → "), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(state.name, viewModel::setName, label = { Text(Copy.ROUTINE_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEachIndexed { i, day ->
                    FilterChip(state.days and dayBit(day) != 0, { viewModel.toggleDay(day) }, { Text(DAY_LABELS[i]) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Copy.ROUTINE_TIME, Modifier.weight(1f))
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> viewModel.setTime(h * 60 + m) }, state.minuteOfDay / 60, state.minuteOfDay % 60, true).show()
                }) { Text(minuteLabel(state.minuteOfDay)) }
            }
            Column {
                Text("${Copy.ROUTINE_WALK} ${state.walkMin}분")
                Slider(
                    value = state.walkMin.toFloat(),
                    onValueChange = { viewModel.setWalk(it.toInt()) },
                    valueRange = 0f..RoutineEntity.MAX_WALK_MIN.toFloat(),
                    steps = RoutineEntity.MAX_WALK_MIN - 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Copy.VIBRATION_ALERTS, Modifier.weight(1f))
                Switch(checked = state.alightAlerts, onCheckedChange = viewModel::setAlightAlerts)
            }
            if (state.preview.isNotEmpty()) {
                Text("${Copy.ROUTINE_PREVIEW}: ${state.preview.joinToString(", ")}", style = MaterialTheme.typography.bodyLarge)
            }
            Button(onClick = askPermission, enabled = state.from != null && state.to != null, modifier = Modifier.fillMaxWidth()) {
                Text(Copy.ROUTINE_SAVE)
            }
        }
    }
}

class RoutineListViewModel(private val routines: RoutineRepository) : ViewModel() {
    val list: StateFlow<List<RoutineEntity>> =
        routines.routines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { routines.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { routines.delete(id) }
    }
}

/**
 * 노선도 아래 패널의 [루틴] 탭(재설계 스펙 4.6): 알람 앱처럼 시각, 요일, "마포 → 공덕", 켜기·끄기. 길게 누르면 삭제.
 * 정확한 알람 권한이 없으면 위에 "알림이 늦을 수 있어요"와 [설정 열기].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutineList(onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val viewModel = appViewModel { RoutineListViewModel(it.routineRepository) }
    val routines by viewModel.list.collectAsStateWithLifecycle()
    val exact = (context.applicationContext as App).container.alarmScheduler.canScheduleExact()
    var deleting by remember { mutableStateOf<RoutineEntity?>(null) }
    var enabling by remember { mutableStateOf<Long?>(null) }
    val askPermission = rememberNotificationPermission { granted ->
        enabling?.let { id -> if (granted) viewModel.setEnabled(id, true) }
        enabling = null
    }

    Column {
        if (!exact) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Copy.MAY_BE_LATE, Modifier.weight(1f))
                    TextButton(onClick = {
                        context.startActivity(permissionSettingsIntent(context, PermissionRow(Copy.PERMISSION_EXACT_ALARM, false)))
                    }) { Text(Copy.OPEN_SETTINGS) }
                }
            }
        }
        if (routines.isEmpty()) ListItem(headlineContent = { Text(Copy.NO_ROUTINES) })
        routines.forEach { routine ->
            ListItem(
                headlineContent = { Text(minuteLabel(routine.minuteOfDay), style = MaterialTheme.typography.titleLarge) },
                supportingContent = { Text("${daysLabel(routine.days)} · ${routine.title}") },
                trailingContent = {
                    Switch(checked = routine.enabled, onCheckedChange = { on ->
                        if (on) {
                            enabling = routine.id
                            askPermission()
                        } else {
                            viewModel.setEnabled(routine.id, false)
                        }
                    })
                },
                modifier = Modifier.combinedClickable(onClick = { onEdit(routine.id) }, onLongClick = { deleting = routine }),
            )
        }
    }
    deleting?.let { routine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(Copy.DELETE_ROUTINE_ASK) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(routine.id)
                    deleting = null
                }) { Text(Copy.DELETE) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(Copy.CANCEL) } },
        )
    }
}

/** 접힌 패널에 보일 오늘의 다음 루틴 한 줄("08:10 마포 → 공덕"). */
fun nextRoutineLine(routines: List<RoutineEntity>, nowMinute: Int, today: DayOfWeek): String? =
    routines.filter { it.enabled && it.days and dayBit(today) != 0 && it.minuteOfDay > nowMinute }
        .minByOrNull { it.minuteOfDay }
        ?.let { "${minuteLabel(it.minuteOfDay)} ${it.title}" }
