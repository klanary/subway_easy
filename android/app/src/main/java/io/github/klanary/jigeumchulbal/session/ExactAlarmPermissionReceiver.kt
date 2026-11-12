package io.github.klanary.jigeumchulbal.session

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.klanary.jigeumchulbal.App

/** "알람 및 리마인더"가 새로 허용되면(Android 12 이상) 진행 중 세션의 알람을 정확한 방식으로 다시 건다(스펙 8.7). */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val routines = (context.applicationContext as App).container.routineRepository
        runWithSession(context) {
            it.rescheduleAll()
            routines.rescheduleAll() // 정확하지 않게 걸려 있던 루틴도 정확한 알람으로 다시 건다
        }
    }
}
