package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaitingPollerTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private val waiting = SessionState.Waiting(target, WaitMode.MINUTES_BEFORE, null, 0, 60, 180, 120, 0, deadlineAt = 1_800_000)
    private val ok = ApiResult.Ok(
        ArrivalsDto(
            "홍대입구", "t",
            groups = listOf(ArrivalGroupDto("2호선", "외선", "합정", listOf(TrainDto("성수", 240)))),
        ),
    )

    @Test fun pollsEvery30sAndDispatchesTrains() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val job = launch { WaitingPoller({ ok }, { sent += it }, { waiting }, { currentTime }).run() }
        advanceTimeBy(60_001)
        job.cancel()
        assertEquals(3, sent.size)
        assertEquals(listOf(TrainDto("성수", 240)), (sent[0] as SessionEvent.ArrivalsUpdated).trains)
        assertEquals(listOf(0L, 30_000L, 60_000L), sent.map { (it as SessionEvent.ArrivalsUpdated).receivedAt })
    }

    @Test fun failureDispatchesArrivalsFailed() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val job = launch { WaitingPoller({ ApiResult.Err(ApiError.Network) }, { sent += it }, { waiting }, { currentTime }).run() }
        runCurrent()
        job.cancel()
        assertEquals(listOf<SessionEvent>(SessionEvent.ArrivalsFailed), sent)
    }

    @Test fun otherLinesGiveNoTrains() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val elsewhere = WaitTarget("홍대입구", "홍대입구", "경의중앙선", null, null)
        val job = launch { WaitingPoller({ ok }, { sent += it }, { waiting.copy(target = elsewhere) }, { currentTime }).run() }
        runCurrent()
        job.cancel()
        assertEquals(emptyList<TrainDto>(), (sent.single() as SessionEvent.ArrivalsUpdated).trains)
    }

    @Test fun fetchesTheWaitedTarget() = runTest {
        val asked = mutableListOf<WaitTarget>()
        val job = launch { WaitingPoller({ asked += it; ok }, {}, { waiting }, { currentTime }).run() }
        runCurrent()
        job.cancel()
        assertEquals(listOf(target), asked)
    }

    @Test fun stopsWhenStateLeavesWaiting() = runTest {
        var state: SessionState = waiting
        val job = launch { WaitingPoller({ ok }, { state = SessionState.Idle }, { state }, { currentTime }).run() }
        advanceTimeBy(30_001)
        assertTrue(job.isCompleted)
    }

    @Test fun timeoutWhenDeadlinePassed() = runTest {
        val sent = mutableListOf<SessionEvent>()
        WaitingPoller({ ok }, { sent += it }, { waiting.copy(deadlineAt = 45_000) }, { currentTime }).run()
        assertEquals(SessionEvent.Timeout, sent.last())
        assertEquals(2, sent.count { it is SessionEvent.ArrivalsUpdated })
    }
}
