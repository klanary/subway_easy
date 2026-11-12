package io.github.klanary.jigeumchulbal.ui.linemap

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.remote.TimetableDto
import io.github.klanary.jigeumchulbal.data.remote.TimetableTrainDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.fakes.FakeStationRepository
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import java.time.Instant
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
class StationSheetViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = Instant.parse("2026-11-13T10:00:00Z") // 19:00 KST

    private fun group(direction: String, source: String = "realtime", vararg secs: Int) = ArrivalGroupDto(
        line = "5호선", direction = direction, towards = null,
        trains = secs.map { TrainDto("방화", it) }, source = source,
    )

    private val stations = FakeStationRepository().apply {
        searchByQuery = mapOf("영등포구청" to listOf(StationDto("영등포구청", "영등포구청", listOf("2호선", "5호선"), 37.5, 126.9)))
        arrivalsResult = ApiResult.Ok(
            ArrivalsDto("영등포구청", "2026-11-13T19:00:00+09:00", groups = listOf(group("상행", "realtime", 180, 660), group("하행", "realtime", 60))),
        )
        timetableResult = { call ->
            ApiResult.Ok(
                TimetableDto(
                    call.stationId, call.line, call.direction,
                    listOf(TimetableTrainDto("2026-11-13T20:01:00+09:00", "방화"), TimetableTrainDto("2026-11-13T20:05:00+09:00", "방화")),
                ),
            )
        }
    }

    private fun vm() = StationSheetViewModel("영등포구청", stations, clock = { now.toEpochMilli() })

    private fun content(vm: StationSheetViewModel) = (vm.state.value as UiState.Content).data

    @Test fun nowShowsTwoTrainsPerDirection() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val ui = content(vm)
        assertEquals(listOf("2호선", "5호선"), ui.lines)
        assertEquals(listOf("방화행 3분 · 그다음 11분", "방화행 1분"), ui.groups.map { it.trains.joinToString(" · ") })
        assertFalse(ui.fromTimetable)
    }

    @Test fun plusOneHourUsesTimetable() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.select(SheetTime.PLUS_1H)
        advanceUntilIdle()
        val ui = content(vm)
        assertTrue(ui.fromTimetable)
        val calls = stations.timetableCalls
        assertEquals(setOf("2호선" to "내선", "2호선" to "외선", "5호선" to "상행", "5호선" to "하행"), calls.map { it.line to it.direction }.toSet())
        assertTrue(calls.all { it.from == now.plusSeconds(3600) && it.count == 4 })
        assertEquals("20:01 방화행", ui.groups.first().trains.first())
    }

    @Test fun unsupportedLineMessage() = runTest {
        stations.searchByQuery = mapOf("계양" to listOf(StationDto("계양", "계양", listOf("공항철도", "인천1호선"), 37.5, 126.7)))
        stations.arrivalsResult = ApiResult.Ok(
            ArrivalsDto("계양", "2026-11-13T19:00:00+09:00", groups = listOf(ArrivalGroupDto("인천1호선", null, null, emptyList(), "none"))),
        )
        val vm = StationSheetViewModel("계양", stations, clock = { now.toEpochMilli() })
        advanceUntilIdle()
        assertEquals(listOf("인천1호선"), content(vm).unsupportedLines)
    }

    @Test fun noTrainsMessage() = runTest {
        stations.arrivalsResult = ApiResult.Ok(ArrivalsDto("영등포구청", "2026-11-13T19:00:00+09:00", groups = emptyList()))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(UiState.Error(Copy.NO_TRAIN_INFO), vm.state.value)
    }

    @Test fun linesRecoverWhenFirstSearchFailedOrWasCancelled() = runTest { // 리뷰 Important 4(a)
        val saved = stations.searchByQuery
        stations.searchByQuery = emptyMap()
        stations.result = ApiResult.Err(io.github.klanary.jigeumchulbal.data.remote.ApiError.Network)
        val vm = vm()
        vm.select(SheetTime.PLUS_1H) // 첫 호선 조회가 끝나기 전에 누름
        advanceUntilIdle()
        stations.searchByQuery = saved
        stations.result = null
        vm.select(SheetTime.PLUS_1H)
        advanceUntilIdle()
        assertEquals(listOf("2호선", "5호선"), content(vm).lines)
        assertTrue(stations.timetableCalls.isNotEmpty())
    }

    @Test fun autoRefreshLoadsImmediately() = runTest { // 리뷰 Important 4(b): 다시 연 시트가 옛 시각을 보이지 않게
        val vm = vm()
        advanceUntilIdle()
        val before = stations.arrivalCalls.size
        backgroundScope.launch { vm.runAutoRefresh() }
        runCurrent()
        assertEquals(before + 1, stations.arrivalCalls.size)
    }

    @Test fun lineWithoutTimetableIsNamed() = runTest { // 2026-11-12: 서울시 시간표는 2~9호선만 있다
        stations.timetableResult = { call ->
            val trains = if (call.line == "2호선") listOf(TimetableTrainDto("2026-11-13T20:01:00+09:00", "성수")) else emptyList()
            ApiResult.Ok(TimetableDto(call.stationId, call.line, call.direction, trains))
        }
        stations.searchByQuery = mapOf("시청" to listOf(StationDto("시청", "시청", listOf("1호선", "2호선"), 37.5, 126.9)))
        val vm = StationSheetViewModel("시청", stations, clock = { now.toEpochMilli() })
        advanceUntilIdle()
        vm.select(SheetTime.PLUS_1H)
        advanceUntilIdle()
        assertEquals(listOf("1호선"), content(vm).noTimetableLines)
    }

    @Test fun lateNowRefreshDoesNotOverwriteLaterTime() = runTest { // 리뷰 사소한 문제 3
        val vm = vm()
        advanceUntilIdle()
        stations.arrivalsDelayMs = 5_000
        backgroundScope.launch { vm.runAutoRefresh() }
        runCurrent() // "지금" 새로 고침이 응답을 기다리는 중
        vm.select(SheetTime.PLUS_1H)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(content(vm).fromTimetable)
        assertEquals(SheetTime.PLUS_1H, content(vm).time)
    }
}
