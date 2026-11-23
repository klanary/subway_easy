package io.github.klanary.jigeumchulbal.ui.session

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.klanary.jigeumchulbal.App
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.StartResult
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.settings.PermissionRow
import io.github.klanary.jigeumchulbal.ui.settings.permissionSettingsIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class StartDialog { NONE, INTRO, EXACT_ALARM, REPLACE }

/**
 * 세션 시작 흐름(스펙 4.4, 8.6). 처음이면 이유를 설명한 뒤 알림 권한을 요청하고, 정확한 알람이 안 되면
 * "알람 및 리마인더" 설정을 한 번 안내한 다음 시작한다. 진행 중인 세션이 있으면 바꿀지 묻는다.
 * 돌려주는 함수에는 설정값으로 시작 이벤트를 만드는 함수를 넘긴다. 시작되면 [onStarted]를 부른다.
 */
@Composable
fun rememberStartSession(onStarted: () -> Unit): ((AppSettings) -> SessionEvent) -> Unit {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as App).container }
    val scope = rememberCoroutineScope()
    val currentOnStarted by rememberUpdatedState(onStarted)
    var pending by remember { mutableStateOf<((AppSettings) -> SessionEvent)?>(null) }
    var dialog by remember { mutableStateOf(StartDialog.NONE) }

    suspend fun start(replaceExisting: Boolean) {
        val build = pending ?: return
        val settings = container.settingsStore.settings.first()
        val decision = decideStart(settings.permissionIntroShown, notificationsEnabled(context))
        if (decision == StartDecision.SHOW_INTRO) {
            dialog = StartDialog.INTRO
            return
        }
        val inAppOnly = decision == StartDecision.START_IN_APP_ONLY
        if (container.sessionController.start(build(settings), inAppOnly, replaceExisting) == StartResult.ALREADY_RUNNING) {
            dialog = StartDialog.REPLACE
            return
        }
        pending = null
        currentOnStarted()
    }

    fun launchStart(replaceExisting: Boolean = false) {
        scope.launch { start(replaceExisting) }
    }

    fun abandon() {
        dialog = StartDialog.NONE
        pending = null
    }

    val exactAlarmSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        launchStart() // 설정에서 돌아오면 허용 여부와 상관없이 시작한다
    }

    fun askExactAlarmThenStart() {
        if (container.alarmScheduler.canScheduleExact()) launchStart() else dialog = StartDialog.EXACT_ALARM
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askExactAlarmThenStart() // 거부해도 앱 안 모드로 시작한다
    }

    when (dialog) {
        StartDialog.NONE -> Unit
        StartDialog.INTRO -> AlertDialog(
            onDismissRequest = ::abandon,
            title = { Text(Copy.INTRO_TITLE) },
            text = { Text(Copy.INTRO_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = StartDialog.NONE
                    scope.launch {
                        container.settingsStore.update { it.copy(permissionIntroShown = true) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled(context)) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            askExactAlarmThenStart()
                        }
                    }
                }) { Text(Copy.CONTINUE) }
            },
        )
        StartDialog.EXACT_ALARM -> AlertDialog(
            onDismissRequest = {
                dialog = StartDialog.NONE
                launchStart()
            },
            text = { Text(Copy.EXACT_ALARM_ASK) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = StartDialog.NONE
                    exactAlarmSettings.launch(permissionSettingsIntent(context, PermissionRow(Copy.PERMISSION_EXACT_ALARM, false)))
                }) { Text(Copy.OPEN_SETTINGS) }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = StartDialog.NONE
                    launchStart()
                }) { Text(Copy.LATER) }
            },
        )
        StartDialog.REPLACE -> AlertDialog(
            onDismissRequest = ::abandon,
            text = { Text(Copy.REPLACE_SESSION_ASK) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = StartDialog.NONE
                    launchStart(replaceExisting = true)
                }) { Text(Copy.START_NEW) }
            },
            dismissButton = { TextButton(onClick = ::abandon) { Text(Copy.CANCEL) } },
        )
    }

    return { build ->
        pending = build
        launchStart()
    }
}

/** Android 13 이상은 알림 권한, 그 아래는 사용자가 앱 알림을 껐는지까지 함께 본다. */
private fun notificationsEnabled(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
