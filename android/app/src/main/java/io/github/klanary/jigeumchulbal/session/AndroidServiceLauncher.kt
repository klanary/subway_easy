package io.github.klanary.jigeumchulbal.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 대기 서비스를 켜고 끈다. 세션은 앱 화면에서 시작하거나(Task 6·7) 앱을 열 때 복원하므로(Task 9)
 * 백그라운드에서 포그라운드 서비스를 시작하는 제한(Android 12)에 걸리지 않는다.
 */
class AndroidServiceLauncher(private val context: Context) : SessionServiceLauncher {
    private val intent = Intent(context, WaitingService::class.java)

    override fun start() = ContextCompat.startForegroundService(context, intent)

    override fun stop() {
        context.stopService(intent)
    }
}
