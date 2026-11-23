package io.github.klanary.jigeumchulbal.ui.session

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import io.github.klanary.jigeumchulbal.fakes.FakeLauncher
import io.github.klanary.jigeumchulbal.fakes.FakeNotifier
import io.github.klanary.jigeumchulbal.fakes.testSessionStore
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.WaitTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val controller = SessionController(testSessionStore(), FakeAlarmScheduler(), FakeNotifier(), FakeLauncher()) { 0L }
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)

    @Test fun boardedAtTheStationEndsTheSession() = runTest {
        controller.start(SessionEvent.StartWaiting(target, WaitMode.MINUTES_BEFORE, null, 0, AppSettings()), inAppOnly = false)
        val vm = SessionViewModel(controller, { 0L })
        assertTrue(vm.state.value is SessionState.Waiting)
        vm.boarded()
        advanceUntilIdle()
        assertEquals(SessionState.Idle, vm.state.value)
    }

    @Test fun cancelEndsARide() = runTest {
        controller.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = false)
        val vm = SessionViewModel(controller, { 0L })
        assertTrue(vm.state.value is SessionState.Riding)
        vm.cancel()
        advanceUntilIdle()
        assertEquals(SessionState.Idle, vm.state.value)
    }

    @Test fun clockTicksEverySecondWhileWatched() = runTest {
        var t = 0L
        val vm = SessionViewModel(controller, { t })
        backgroundScope.launch { vm.now.collect {} }
        t = 5_000
        advanceTimeBy(1_001)
        assertEquals(5_000L, vm.now.value)
    }

    @Test fun exactAlarmStateComesFromTheScheduler() {
        assertFalse(SessionViewModel(controller, { 0L }, exactAlarms = { false }).exactAlarms())
        assertTrue(SessionViewModel(controller, { 0L }).exactAlarms())
    }
}
