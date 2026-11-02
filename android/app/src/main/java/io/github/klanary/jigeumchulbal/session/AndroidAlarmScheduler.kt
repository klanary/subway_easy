package io.github.klanary.jigeumchulbal.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 알람 예약(스펙 8.4). 정확한 알람 권한이 있으면 setAlarmClock(절전 모드에서도 제시간에 울림),
 * 없으면 setAndAllowWhileIdle(부정확 — 화면에 "알림이 늦을 수 있어요"를 보여준다).
 */
class AndroidAlarmScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
) : AlarmScheduler {
    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun schedule(id: AlarmId, atMillis: Long) {
        val operation = alarmIntent(id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (canScheduleExact()) {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, openSessionPendingIntent(context)), operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    override fun cancel(id: AlarmId) {
        // 만든 적 없는 알람은 취소할 것도 없다(FLAG_NO_CREATE면 null).
        val operation = alarmIntent(id, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    override fun cancelAll() {
        (listOf(AlarmId.PreBoarding, AlarmId.SessionEnd) + (0 until MAX_ALIGHT_POINTS).map { AlarmId.Alight(it) })
            .forEach(::cancel)
    }

    private fun alarmIntent(id: AlarmId, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        id.requestCode(),
        Intent(ACTION_ALARM).setClass(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, id.encode()),
        PendingIntent.FLAG_IMMUTABLE or flag,
    )
}
