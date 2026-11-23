package io.github.klanary.jigeumchulbal.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 알림 버튼마다 고유한 요청 코드(스펙 8.4의 알람 코드 1000~3000번대와 겹치지 않는다). */
val SessionAction.requestCode: Int
    get() = when (this) {
        SessionAction.BOARDED -> 4001
        SessionAction.CANCEL -> 4002
    }

val SessionAction.intentAction: String
    get() = "io.github.klanary.jigeumchulbal.SESSION_$name"

fun sessionActionOf(intentAction: String?): SessionAction? =
    SessionAction.entries.firstOrNull { it.intentAction == intentAction }

/** 진행 중 알림의 [탑승했어요]·[종료] 버튼을 세션 컨트롤러에 넘긴다(스펙 8.5). */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (sessionActionOf(intent.action) ?: return) {
            SessionAction.BOARDED -> SessionEvent.Boarded
            SessionAction.CANCEL -> SessionEvent.Cancel
        }
        runWithSession(context) { it.dispatch(event) }
    }
}
