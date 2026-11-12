package io.github.klanary.jigeumchulbal.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 세션 서비스를 켜고 끈다. 세션은 앱 화면에서 시작하거나 앱을 열 때 복원하므로 보통은 백그라운드에서 포그라운드 서비스를
 * 시작하는 제한(Android 12)에 걸리지 않는다. 막히면 앱이 죽지 않게 false를 돌려준다(재설계 스펙 13: 알림만 보내고
 * 앱을 열면 세션을 이어 간다).
 */
class AndroidServiceLauncher(private val context: Context) : SessionServiceLauncher {
    private val intent = Intent(context, SessionService::class.java)

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (e: IllegalStateException) {
        // Android 12 이상의 ForegroundServiceStartNotAllowedException도 IllegalStateException이다.
        // 정확하지 않은 알람으로 깨어난 루틴 등. 컨트롤러가 "눌러서 시작" 알림을 띄운다.
        Log.w(TAG, "세션 서비스를 시작하지 못했어요.", e)
        false
    }

    override fun stop() {
        context.stopService(intent)
    }

    private companion object {
        const val TAG = "SessionService"
    }
}
