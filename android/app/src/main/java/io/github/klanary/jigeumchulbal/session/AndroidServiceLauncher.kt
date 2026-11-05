package io.github.klanary.jigeumchulbal.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 세션 서비스를 켜고 끈다. 세션은 앱 화면에서 시작하거나 앱을 열 때 복원하므로 보통은 백그라운드에서 포그라운드 서비스를
 * 시작하는 제한(Android 12)에 걸리지 않는다. 그래도 막히면 앱이 죽지 않게 로그만 남긴다. 알람은 이미 예약돼 있어
 * 시간표대로 울린다(위치 결합 스펙 7.3).
 */
class AndroidServiceLauncher(private val context: Context) : SessionServiceLauncher {
    private val intent = Intent(context, SessionService::class.java)

    override fun start() {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Android 12 이상의 ForegroundServiceStartNotAllowedException도 IllegalStateException이다.
            Log.w(TAG, "세션 서비스를 시작하지 못했어요. 알람은 그대로 울려요.", e)
        }
    }

    override fun stop() {
        context.stopService(intent)
    }

    private companion object {
        const val TAG = "SessionService"
    }
}
