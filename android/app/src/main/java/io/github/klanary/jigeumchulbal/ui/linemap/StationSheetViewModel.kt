package io.github.klanary.jigeumchulbal.ui.linemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.TimetableDto
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SheetTime { NOW, PLUS_1H, PLUS_2H, CUSTOM }

/** 한 방향의 열차 줄. [trains]는 화면에 그대로 보이는 글("방화행 3분", "그다음 11분" / "19:32 방화행"). */
data class DirectionTrains(val line: String, val direction: String?, val towards: String?, val trains: List<String>)

data class StationSheetUi(
    val name: String,
    val lines: List<String>,
    val groups: List<DirectionTrains>,
    /** 실시간도 시간표도 없는 비서울 노선(재설계 스펙 D4). */
    val unsupportedLines: List<String>,
    val fromTimetable: Boolean,
    val time: SheetTime = SheetTime.NOW,
)

/** 비서울 5개 노선(서버 NON_SEOUL_LINES와 같다). 시간표를 묻지 않는다. */
private val NON_SEOUL_LINES = setOf("인천1호선", "인천2호선", "에버라인선", "의정부선", "김포골드라인")

private fun directionsOf(line: String) = if (line == "2호선") listOf("내선", "외선") else listOf("상행", "하행")

/**
 * 역 시트(재설계 스펙 4.2): 지금은 방향별 다음 열차 2대(실시간, 없으면 시간표), +1시간·+2시간·시각 선택은
 * 그 시각 이후 시간표 열차를 방향별 4대.
 */
class StationSheetViewModel(
    private val stationId: String,
    private val stations: StationRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<StationSheetUi>>(UiState.Loading)
    val state: StateFlow<UiState<StationSheetUi>> = _state.asStateFlow()

    private var lines: List<String> = emptyList()
    private var time = SheetTime.NOW
    private var custom: Instant? = null
    private var job: Job? = null

    init {
        job = viewModelScope.launch {
            lines = (stations.search(stationId) as? ApiResult.Ok)?.value?.firstOrNull { it.id == stationId }?.lines.orEmpty()
            load()
        }
    }

    /** [지금]이면 30초마다 새로 고친다. 화면이 보이는 동안만 부른다. */
    suspend fun runAutoRefresh() {
        while (true) {
            delay(REFRESH_MS)
            if (time == SheetTime.NOW) load()
        }
    }

    fun select(time: SheetTime, custom: Instant? = null) {
        this.time = time
        this.custom = custom
        job?.cancel()
        _state.value = UiState.Loading
        job = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.value = if (time == SheetTime.NOW) loadNow() else loadTimetable(startAt())
    }

    private fun startAt(): Instant {
        val now = Instant.ofEpochMilli(clock())
        return when (time) {
            SheetTime.NOW -> now
            SheetTime.PLUS_1H -> now.plusSeconds(3600)
            SheetTime.PLUS_2H -> now.plusSeconds(7200)
            SheetTime.CUSTOM -> custom ?: now
        }
    }

    private suspend fun loadNow(): UiState<StationSheetUi> {
        val dto = when (val result = stations.arrivals(stationId)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> return result.toUiState()
        }
        val unsupported = dto.groups.filter { it.source == "none" }.map { it.line } + lines.filter { it in NON_SEOUL_LINES }
        val groups = dto.groups.filter { it.source != "none" && it.trains.isNotEmpty() }.map { it.toDirectionTrains() }
        return sheet(groups, unsupported.distinct(), fromTimetable = dto.groups.any { it.source == "timetable" })
    }

    private suspend fun loadTimetable(from: Instant): UiState<StationSheetUi> = coroutineScope {
        val seoul = lines.filter { it !in NON_SEOUL_LINES }
        val results = seoul.flatMap { line -> directionsOf(line).map { line to it } }.map { (line, direction) ->
            async { stations.timetable(stationId, line, direction, from, TIMETABLE_COUNT) }
        }.awaitAll()
        val groups = results.mapNotNull { (it as? ApiResult.Ok)?.value?.toDirectionTrains() }
        val failure = results.filterIsInstance<ApiResult.Err>().firstOrNull()
        if (groups.isEmpty() && failure != null) return@coroutineScope failure.toUiState()
        sheet(groups, lines.filter { it in NON_SEOUL_LINES }, fromTimetable = true)
    }

    private fun sheet(groups: List<DirectionTrains>, unsupported: List<String>, fromTimetable: Boolean): UiState<StationSheetUi> {
        if (groups.isEmpty() && unsupported.isEmpty()) return UiState.Error(Copy.NO_TRAIN_INFO)
        return UiState.Content(StationSheetUi(stationId, lines, groups, unsupported, fromTimetable, time))
    }


    private fun ArrivalGroupDto.toDirectionTrains() = DirectionTrains(
        line = line,
        direction = direction,
        towards = towards,
        trains = trains.take(2).mapIndexed { i, train ->
            val left = formatRemaining(train.remainingSec)
            if (i == 0) listOfNotNull(train.destination?.let { "${it}행" }, left).joinToString(" ") else "그다음 $left"
        },
    )

    private fun TimetableDto.toDirectionTrains(): DirectionTrains? {
        if (trains.isEmpty()) return null
        return DirectionTrains(
            line = line,
            direction = direction,
            towards = null,
            trains = trains.map { listOfNotNull(it.departsAt.substring(11, 16), it.destination?.let { d -> "${d}행" }).joinToString(" ") },
        )
    }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val TIMETABLE_COUNT = 4
    }
}
