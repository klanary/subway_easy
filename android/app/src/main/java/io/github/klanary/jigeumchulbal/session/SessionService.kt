package io.github.klanary.jigeumchulbal.session

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.klanary.jigeumchulbal.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 통학 세션 서비스(스펙 8.2). 대기 단계 동안 진행 중 알림을 띄운 채 실시간 도착을 조회한다(⑦).
 * 대기가 끝나면 스스로 멈추고, 세션이 끝나면 컨트롤러가 멈춘다. 유형은 dataSync다(재설계 스펙 7).
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null
    private val container get() = (application as App).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = container.sessionController
        // startForegroundService 뒤 몇 초 안에 startForeground를 부르지 않으면 앱이 죽는다. 세션이 그새 끝났어도 먼저 부른다.
        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            container.sessionNotifier.foregroundNotification(controller.state.value, System.currentTimeMillis()),
            sessionServiceType(Build.VERSION.SDK_INT),
        )
        if (work?.isActive != true) {
            work = scope.launch {
                runSessionWork(controller, container.stationRepository, System::currentTimeMillis)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // 서비스가 멈추면 시스템이 서비스 알림(id 1)을 지운다. 같은 id에 쓴 진행 중 알림까지 지워지지 않도록
        // 알림을 서비스에서 떼어 낸 뒤 지금 세션 상태로 다시 맞춘다(세션이 끝났으면 지운다).
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        container.sessionNotifier.showOngoing(container.sessionController.state.value, System.currentTimeMillis())
        super.onDestroy()
    }
}
