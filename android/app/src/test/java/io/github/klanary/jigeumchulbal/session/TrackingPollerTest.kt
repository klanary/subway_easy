package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.TrainEtaDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 탄 열차를 30초마다 조회하기(⑪, 위치 결합 스펙 7.2). 가상 시간으로 확인한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingPollerTest {
    private val riding = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕", "5123"))

    private fun eta(found: Boolean): ApiResult<TrainEtaDto> =
        ApiResult.Ok(TrainEtaDto("5123", "5호선", found, etaSec = if (found) 200 else null, updatedAt = "t"))

    @Test fun pollsEvery30sForTrackedTrain() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val asked = mutableListOf<Tracking>()
        val job = launch {
            TrackingPoller({ asked += it; eta(true) }, { sent += it }, { riding }, { currentTime }).run()
        }
        advanceTimeBy(60_001)
        job.cancel()
        assertEquals(listOf(0L, 30_000L, 60_000L).map { SessionEvent.TrainEtaUpdated(200, it) }, sent)
        assertEquals("5123", asked.first().trainNo)
    }

    @Test fun notFoundAndErrorsAreFailures() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val results = ArrayDeque(listOf(eta(false), ApiResult.Err(ApiError.Network)))
        val job = launch { TrackingPoller({ results.removeFirst() }, { sent += it }, { riding }, { currentTime }).run() }
        advanceTimeBy(30_001)
        job.cancel()
        assertEquals(listOf<SessionEvent>(SessionEvent.TrainLookupFailed, SessionEvent.TrainLookupFailed), sent)
    }

    @Test fun returnsWithoutCallWhenTrainUnknown() = runTest {
        val unknown = riding.copy(tracking = riding.tracking!!.copy(trainNo = null))
        TrackingPoller({ error("호출하면 안 된다") }, {}, { unknown }, { currentTime }).run()
    }
}
