package io.github.klanary.jigeumchulbal.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.klanary.jigeumchulbal.session.openSessionPendingIntent

/** 루틴 알람의 요청 코드: 세션 알람(4000번대)과 겹치지 않게 5000 + 루틴 id. */
fun routineRequestCode(routineId: Long): Int = ROUTINE_REQUEST_BASE + routineId.toInt()

private const val ROUTINE_REQUEST_BASE = 5000
const val EXTRA_ROUTINE_ID = "routine_id"
const val ACTION_ROUTINE_FIRE = "io.github.klanary.jigeumchulbal.ROUTINE_FIRE"

/**
 * `setAlarmClock`으로 건다(정확하고, 도즈 중에도 울리며, 울리면 백그라운드 서비스 시작이 허용된다).
 * 정확한 알람 권한이 없으면 `setAndAllowWhileIdle`로 대신한다(조금 늦을 수 있다).
 */
class AndroidRoutineAlarmScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
) : RoutineAlarmScheduler {
    override fun schedule(routineId: Long, atMillis: Long) {
        val operation = pendingIntent(routineId)
        if (alarmManager.canScheduleExactAlarmsCompat()) {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, openSessionPendingIntent(context)), operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    override fun cancel(routineId: Long) {
        alarmManager.cancel(pendingIntent(routineId))
    }

    private fun pendingIntent(routineId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        routineRequestCode(routineId),
        Intent(context, RoutineReceiver::class.java).setAction(ACTION_ROUTINE_FIRE).putExtra(EXTRA_ROUTINE_ID, routineId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || canScheduleExactAlarms()
}
