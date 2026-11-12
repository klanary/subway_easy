package io.github.klanary.jigeumchulbal.ui.session

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import io.github.klanary.jigeumchulbal.fakes.FakeLauncher
import io.github.klanary.jigeumchulbal.fakes.FakeNotifier
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.fakes.testSessionStore
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.session.AlertContent
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.SessionUiEvent
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.WaitTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private var t = 0L
    private val controller = SessionController(testSessionStore(), FakeAlarmScheduler(), FakeNotifier(), FakeLauncher()) { t }
    private val stations = FakeStationRepository()
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private val startWaiting = SessionEvent.StartWaiting(target, WaitMode.MINUTES_BEFORE, null, 0, AppSettings())

    private fun viewModel(exactAlarms: () -> Boolean = { true }) = SessionViewModel(controller, { t }, stations, exactAlarms)

    @Test fun boardedAtTheStationEndsTheSession() = runTest {
        controller.start(startWaiting, inAppOnly = false)
        val vm = viewModel()
        assertTrue(vm.state.value is SessionState.Waiting)
        vm.boarded()
        advanceUntilIdle()
        assertEquals(SessionState.Idle, vm.state.value)
    }

    @Test fun cancelEndsARide() = runTest {
        controller.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = false)
        val vm = viewModel()
        assertTrue(vm.state.value is SessionState.Riding)
        vm.cancel()
        advanceUntilIdle()
        assertEquals(SessionState.Idle, vm.state.value)
    }

    @Test fun clockTicksEverySecondWhileWatched() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.now.collect {} }
        t = 5_000
        advanceTimeBy(1_001)
        assertEquals(5_000L, vm.now.value)
    }

    @Test fun exactAlarmStateComesFromTheScheduler() {
        assertFalse(viewModel(exactAlarms = { false }).exactAlarms())
        assertTrue(viewModel().exactAlarms())
    }

    @Test fun inAppLoopFiresDueAlightAlarmsAsDialogs() = runTest {
        controller.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = true)
        val events = mutableListOf<SessionUiEvent>()
        backgroundScope.launch { controller.uiEvents.collect { events += it } }
        backgroundScope.launch { viewModel().runInAppLoop() }
        runCurrent()
        assertTrue(events.isEmpty())
        t = 480_000 // 당산 하차(600초 뒤) 2분 전
        advanceTimeBy(1_001)
        val point = (controller.state.value as SessionState.Riding).points[0]
        assertEquals(listOf<SessionUiEvent>(SessionUiEvent.InAppAlert(AlertContent.AlightSoon(point))), events)
    }

    @Test fun inAppLoopPollsWhileWaiting() = runTest {
        controller.start(startWaiting, inAppOnly = true)
        backgroundScope.launch { viewModel().runInAppLoop() }
        runCurrent()
        assertEquals(listOf(Triple("홍대입구", "2호선", "합정")), stations.arrivalCalls.toList())
        advanceTimeBy(30_001)
        assertEquals(2, stations.arrivalCalls.size)
    }
}
