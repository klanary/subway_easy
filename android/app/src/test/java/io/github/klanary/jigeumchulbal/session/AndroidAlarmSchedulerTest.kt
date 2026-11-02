package io.github.klanary.jigeumchulbal.session

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** Robolectric ShadowAlarmManager로 정확한 알람 허용 여부를 바꾸고, 예약된 알람 목록을 읽는다. */
@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduler = AndroidAlarmScheduler(context, alarmManager)

    @After fun reset() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    @Test fun exactPermissionUsesAlarmClock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.schedule(AlarmId.PreBoarding, 123_000)
        val alarm = shadowOf(alarmManager).scheduledAlarms.single()
        assertNotNull(alarm.alarmClockInfo)
        assertEquals(123_000L, alarm.triggerAtMs)
    }

    @Test fun withoutPermissionUsesAllowWhileIdle() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.canScheduleExact())
        scheduler.schedule(AlarmId.PreBoarding, 123_000)
        val alarm = shadowOf(alarmManager).scheduledAlarms.single()
        assertNull(alarm.alarmClockInfo)
        assertTrue(alarm.isAllowWhileIdle())
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
    }

    @Test fun rescheduleReplacesTheSameAlarm() {
        scheduler.schedule(AlarmId.Alight(0), 100_000)
        scheduler.schedule(AlarmId.Alight(0), 200_000)
        assertEquals(listOf(200_000L), shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtMs })
    }

    @Test fun cancelAllRemovesEverything() {
        listOf(AlarmId.PreBoarding, AlarmId.Alight(0), AlarmId.Alight(3), AlarmId.SessionEnd)
            .forEachIndexed { i, id -> scheduler.schedule(id, 100_000L + i) }
        assertEquals(4, shadowOf(alarmManager).scheduledAlarms.size)
        scheduler.cancelAll()
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }
}
