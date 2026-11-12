package io.github.klanary.jigeumchulbal.routine

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import io.github.klanary.jigeumchulbal.fakes.FakeLauncher
import io.github.klanary.jigeumchulbal.fakes.FakeNotifier
import io.github.klanary.jigeumchulbal.fakes.testSessionStore
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.session.AlertContent
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.WaitMode
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRoutineRepository(vararg initial: RoutineEntity) : RoutineRepository {
    override val routines = MutableStateFlow(initial.toList())
    val rescheduled = mutableListOf<Long>()

    override suspend fun get(id: Long) = routines.value.find { it.id == id }
    override suspend fun save(routine: RoutineEntity): Long {
        val id = if (routine.id == 0L) (routines.value.maxOfOrNull { it.id } ?: 0) + 1 else routine.id
        routines.value = routines.value.filterNot { it.id == id } + routine.copy(id = id)
        return id
    }
    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        routines.value = routines.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }
    override suspend fun delete(id: Long) {
        routines.value = routines.value.filterNot { it.id == id }
    }
    override suspend fun reschedule(id: Long) {
        rescheduled += id
    }
    override suspend fun rescheduleAll() = Unit
}

class RoutineRunnerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var t = 0L
    private val notifier = FakeNotifier()
    private val controller = SessionController(testSessionStore(), FakeAlarmScheduler(), notifier, FakeLauncher()) { t }
    private val routine = RoutineEntity(
        id = 7, name = "등교", fromId = "홍대입구", fromName = "홍대입구", viaId = null, viaName = null,
        toId = "시청", toName = "시청", routeJson = json.encodeToString(specRoute()),
        days = dayBit(DayOfWeek.FRIDAY), minuteOfDay = 490, walkMin = 5, alightAlerts = true,
    )
    private val repo = FakeRoutineRepository(routine)
    private fun runner() = RoutineRunner(controller, repo, notifier, flowOf(AppSettings()), json)

    @Test fun routineStartsDepartureTimingSession() = runTest {
        runner().run(7)
        val state = controller.state.value as SessionState.Waiting
        assertEquals(WaitMode.DEPARTURE_TIMING, state.mode)
        assertEquals(300, state.travelSec) // 걷는 시간 5분
        assertTrue(state.alightAlerts)
        assertEquals("홍대입구", state.target.stationId)
    }

    @Test fun routineSkippedWhenSessionRunning() = runTest {
        controller.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = false)
        val before = controller.state.value
        runner().run(7)
        assertEquals(before, controller.state.value)
        assertEquals(listOf<AlertContent>(AlertContent.RoutineSkipped), notifier.alerts)
    }

    @Test fun runReschedulesNextOccurrence() = runTest {
        runner().run(7)
        assertEquals(listOf(7L), repo.rescheduled)
    }

    @Test fun deletedOrDisabledRoutineDoesNothing() = runTest {
        repo.setEnabled(7, false)
        runner().run(7)
        assertEquals(SessionState.Idle, controller.state.value)
        runner().run(99)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test fun unreadableRouteTellsTheUser() = runTest { // 리뷰 사소한 문제 7
        repo.save(routine.copy(routeJson = "{망가진"))
        runner().run(7)
        assertEquals(SessionState.Idle, controller.state.value)
        assertEquals(listOf<AlertContent>(AlertContent.RoutineFailed), notifier.alerts)
    }
}
