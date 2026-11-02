package io.github.klanary.jigeumchulbal.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 예약한 알람이 울리면 세션 컨트롤러에 알린다. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = decodeAlarmId(intent.getStringExtra(EXTRA_ALARM_ID)) ?: return
        runWithSession(context) { it.onAlarm(id) }
    }
}
