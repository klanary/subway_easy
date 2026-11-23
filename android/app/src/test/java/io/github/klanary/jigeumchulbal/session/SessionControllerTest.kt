package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import io.github.klanary.jigeumchulbal.fakes.FakeLauncher
import io.github.klanary.jigeumchulbal.fakes.FakeNotifier
import io.github.klanary.jigeumchulbal.fakes.testSessionStore
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {
    private val scheduler = FakeAlarmScheduler()
    private val notifier = FakeNotifier()
    private val launcher = FakeLauncher()

    private fun controller(store: SessionStore) = SessionController(store, scheduler, notifier, launcher) { 0L }

    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private val startWaiting = SessionEvent.StartWaiting(target, WaitMode.MINUTES_BEFORE, null, 0, AppSettings())

    @Test fun startPersistsAndAppliesEffects() = runTest {
        val store = testSessionStore()
        val c = controller(store)
        assertEquals(StartResult.STARTED, c.start(startWaiting, inAppOnly = false))
        assertTrue(store.load() is SessionState.Waiting)
        assertEquals(1, launcher.starts)
        assertTrue(notifier.ongoingShown > 0)
    }

    @Test fun secondStartNeedsConfirmationThenReplaces() = runTest {
        val c = controller(testSessionStore())
        c.start(startWaiting, false)
        assertEquals(StartResult.ALREADY_RUNNING, c.start(SessionEvent.StartRiding(specRoute(), AppSettings()), false))
        assertEquals(
            StartResult.STARTED,
            c.start(SessionEvent.StartRiding(specRoute(), AppSettings()), false, replaceExisting = true),
        )
        assertEquals(1, scheduler.cancelAllCalls)
        assertTrue(c.state.value is SessionState.Riding)
    }

    @Test fun inAppOnlyNeitherPersistsNorSchedules() = runTest {
        val store = testSessionStore()
        val c = controller(store)
        c.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = true)
        assertEquals(SessionState.Idle, store.load())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(c.state.value is SessionState.Riding)
    }

    @Test fun corruptStoredJsonLoadsAsIdle() = runTest {
        assertEquals(SessionState.Idle, testSessionStore(rawJson = "{망가진").load())
    }

    @Test fun savedSessionIsLoadedBeforeTheFirstEvent() = runTest {
        val store = testSessionStore()
        controller(store).start(startWaiting, false)
        val restarted = controller(store) // 앱을 다시 켠 것처럼 새 컨트롤러
        restarted.dispatch(SessionEvent.Cancel)
        assertEquals(SessionState.Idle, store.load())
        assertEquals(1, launcher.stops)
    }
}
