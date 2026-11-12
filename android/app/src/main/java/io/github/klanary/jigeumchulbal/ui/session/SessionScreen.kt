package io.github.klanary.jigeumchulbal.ui.session

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.session.AlertContent
import io.github.klanary.jigeumchulbal.session.AlightPoint
import io.github.klanary.jigeumchulbal.session.PointKind
import io.github.klanary.jigeumchulbal.session.SessionAction
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.SessionUiEvent
import io.github.klanary.jigeumchulbal.session.alertText
import io.github.klanary.jigeumchulbal.session.ongoingContent
import io.github.klanary.jigeumchulbal.session.stopLabel
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.formatClock

/**
 * 통학 세션(스펙 4.2): 대기 단계는 "지금 출발하면 N분 뒤…"와 [탑승했어요] [종료],
 * 탑승 단계는 하차·환승 지점 목록과 [종료]. 세션이 끝나면 이전 화면으로 돌아간다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(navController: NavController, viewModel: SessionViewModel = sessionViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inAppOnly by viewModel.inAppOnly.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    var exactAlarms by remember { mutableStateOf(viewModel.exactAlarms()) }
    LifecycleResumeEffect(Unit) {
        exactAlarms = viewModel.exactAlarms() // "알람 및 리마인더" 설정에서 돌아오면 다시 읽는다
        onPauseOrDispose {}
    }
    val context = LocalContext.current
    var inAppAlert by remember { mutableStateOf<AlertContent?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is SessionUiEvent.Finished -> navController.popBackStack()
                    is SessionUiEvent.InAppAlert -> {
                        inAppAlert = event.content
                        vibrateOnce(context)
                    }
                }
            }
        }
    }
    // 앱 안 모드(알림 권한 없음): 이 화면이 보이는 동안 알람·조회를 대신 돌린다(스펙 8.6).
    LaunchedEffect(viewModel, lifecycleOwner, inAppOnly) {
        if (inAppOnly) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.runInAppLoop() }
    }
    inAppAlert?.let { content ->
        val (title, text) = alertText(content)
        AlertDialog(
            onDismissRequest = { inAppAlert = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { inAppAlert = null }) { Text(Copy.OK) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Copy.SESSION_TITLE) },
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
            val notice = when {
                state == SessionState.Idle -> null
                inAppOnly -> Copy.IN_APP_ONLY
                !exactAlarms -> Copy.MAY_BE_LATE
                else -> null
            }
            notice?.let { NoticeBand(it) }
            Text(sessionHeadline(state, now) ?: Copy.NO_SESSION, style = MaterialTheme.typography.headlineSmall)
            when (val s = state) {
                SessionState.Idle -> Unit
                is SessionState.Waiting -> WaitingSection(s, now, viewModel::boarded, viewModel::cancel)
                is SessionState.Riding -> RidingSection(s, viewModel::cancel)
            }
        }
    }
}

@Composable
private fun NoticeBand(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 기다리는 역·방면과 진행 중 알림과 같은 한 줄("다음 열차 4분 · 7분 뒤 출발"). */
@Composable
private fun WaitingSection(state: SessionState.Waiting, now: Long, onBoarded: () -> Unit, onCancel: () -> Unit) {
    val content = ongoingContent(state, now, exactAlarms = true) ?: return
    Text(content.title, style = MaterialTheme.typography.titleMedium)
    Text(content.text, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBoarded) { Text(SessionAction.BOARDED.label) }
        OutlinedButton(onClick = onCancel) { Text(SessionAction.CANCEL.label) }
    }
}

/** 하차·환승 지점마다 이름과 하차 예정 시각. 이미 지난 지점은 흐리게 보인다. */
@Composable
private fun RidingSection(state: SessionState.Riding, onCancel: () -> Unit) {
    Column {
        state.points.forEachIndexed { i, point ->
            ListItem(
                headlineContent = { Text(stopLabel(point)) },
                supportingContent = { Text(pointKindLabel(point)) },
                trailingContent = { Text("${formatClock(point.alightAt)} ${Copy.ALIGHT_EXPECTED}") },
                modifier = Modifier.alpha(if (i < state.nextIndex) 0.4f else 1f),
            )
        }
    }
    OutlinedButton(onClick = onCancel) { Text(SessionAction.CANCEL.label) }
}

/** 앱 안 모드 알림의 진동 한 번. 보통 모드에서는 알림 채널이 진동을 맡는다. */
private fun vibrateOnce(context: Context) {
    context.getSystemService(Vibrator::class.java)
        ?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
}

private fun pointKindLabel(point: AlightPoint): String = when (point.kind) {
    PointKind.TRANSFER -> point.nextLine?.let { "$it ${Copy.TRANSFER}" } ?: Copy.TRANSFER
    PointKind.VIA -> Copy.VIA_POINT
    PointKind.FINAL -> Copy.FINAL_POINT
}
