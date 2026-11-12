package io.github.klanary.jigeumchulbal.ui.station

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stations = FakeStationRepository()

    private fun train(sec: Int?) = TrainDto("성수", sec, "", false, false)

    @Test fun autoRefreshEvery30Seconds() = runTest {
        val vm = StationViewModel("홍대입구", stations) { 0L }
        val job = launch { vm.runAutoRefresh() }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(3, stations.arrivalCalls.size)
        job.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, stations.arrivalCalls.size)
    }

    @Test fun remainingCountsDownFromReceivedAt() {
        assertEquals(150, displayRemainingSec(train(180), receivedAt = 0, now = 30_000))
        assertEquals(0, displayRemainingSec(train(10), 0, 30_000))
        assertNull(displayRemainingSec(train(null), 0, 0))
    }

    @Test fun staleAndEmptyStates() = runTest {
        stations.arrivalsResult = ApiResult.Ok(ArrivalsDto("홍대입구", "t", stale = true, groups = listOf(GROUP)))
        val vm = StationViewModel("홍대입구", stations) { 0L }
        vm.refresh()
        advanceUntilIdle()
        assertTrue((vm.state.value.arrivals as UiState.Content).stale)
        stations.arrivalsResult = ApiResult.Ok(ArrivalsDto("홍대입구", "t", groups = emptyList()))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(UiState.Empty, vm.state.value.arrivals)
    }

    @Test fun groupTitleText() =
        assertEquals("2호선 내선 · 합정 방면", groupTitle(ArrivalGroupDto("2호선", "내선", "합정", emptyList())))


    private companion object {
        val GROUP = ArrivalGroupDto("2호선", "내선", "합정", listOf(TrainDto("성수", 180, "3분 후")))
    }
}
