package io.github.klanary.jigeumchulbal.routine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.klanary.jigeumchulbal.data.local.AppDatabase
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeRoutineScheduler : RoutineAlarmScheduler {
    val scheduled = mutableMapOf<Long, Long>()

    override fun schedule(routineId: Long, atMillis: Long) {
        scheduled[routineId] = atMillis
    }

    override fun cancel(routineId: Long) {
        scheduled.remove(routineId)
    }
}

@RunWith(RobolectricTestRunner::class)
class RoutineRepositoryTest {
    private lateinit var db: AppDatabase
    private val scheduler = FakeRoutineScheduler()
    private val kst = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 13, 7, 0, 0, 0, kst) // 금요일
    private lateinit var repo: RoutineRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DefaultRoutineRepository(db.routineDao(), scheduler) { now }
    }

    @After fun tearDown() = db.close()

    private fun routine(enabled: Boolean = true) = RoutineEntity(
        name = "등교", fromId = "마포", fromName = "마포", viaId = null, viaName = null, toId = "공덕", toName = "공덕",
        routeJson = "{}", days = dayBit(DayOfWeek.FRIDAY), minuteOfDay = 490, enabled = enabled,
    )

    @Test fun savingEnabledRoutineSchedulesIt() = runTest {
        val id = repo.save(routine())
        assertEquals(now.withHour(8).withMinute(10).toInstant().toEpochMilli(), scheduler.scheduled[id])
        assertEquals(listOf("등교"), repo.routines.first().map { it.name })
    }

    @Test fun disablingCancelsAndEnablingReschedules() = runTest {
        val id = repo.save(routine())
        repo.setEnabled(id, false)
        assertFalse(id in scheduler.scheduled)
        assertFalse(repo.routines.first().single().enabled)
        repo.setEnabled(id, true)
        assertTrue(id in scheduler.scheduled)
    }

    @Test fun deletingCancels() = runTest {
        val id = repo.save(routine())
        repo.delete(id)
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(repo.routines.first().isEmpty())
    }

    @Test fun disabledRoutineIsNotScheduled() = runTest {
        val id = repo.save(routine(enabled = false))
        assertFalse(id in scheduler.scheduled)
    }

    @Test fun rescheduleAllRestoresEveryEnabledRoutine() = runTest {
        val a = repo.save(routine())
        val b = repo.save(routine(enabled = false))
        scheduler.scheduled.clear()
        repo.rescheduleAll()
        assertEquals(setOf(a), scheduler.scheduled.keys)
        assertFalse(b in scheduler.scheduled)
    }
}
