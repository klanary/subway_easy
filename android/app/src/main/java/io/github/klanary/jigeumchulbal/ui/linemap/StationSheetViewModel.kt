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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/**
 * 열차 한 대. [time]은 남은 시간("3분", "곧 도착") 또는 출발 시각("19:32").
 * [clock]이면 시간표 열차(시각이 앞에 온다), 아니면 실시간 열차(남은 시간).
 */
data class TrainLine(
    val destination: String?,
    val time: String,
    val clock: Boolean = false,
    val isLast: Boolean = false,
    val isExpress: Boolean = false,
) {
    /** 글로 쓴 한 줄: "방화행 3분" / "20:01 방화행". */
    fun text(): String = if (clock) listOfNotNull(time, destination?.let { "${it}행" }).joinToString(" ")
    else listOfNotNull(destination?.let { "${it}행" }, time).joinToString(" ")
}

/** 한 방향의 열차 줄. [basis]는 실시간이 아닐 때의 근거("시간표", "운행 기록"), 실시간이면 null. */
data class DirectionTrains(
    val line: String,
    val direction: String?,
    val towards: String?,
    val trains: List<TrainLine>,
    val basis: String? = null,
) {
    /** 글로 쓴 줄: "방화행 3분 · 그다음 11분" / "20:01 방화행, 20:05 방화행". */
    fun text(): String = if (trains.firstOrNull()?.clock == true) trains.joinToString(", ") { it.text() }
    else trains.mapIndexed { i, t -> if (i == 0) t.text() else "그다음 ${t.time}" }.joinToString(" · ")
}

data class StationSheetUi(
    val name: String,
    val lines: List<String>,
    val groups: List<DirectionTrains>,
    /** 실시간도 시간표도 없는 비서울 노선(재설계 스펙 D4). */
    val unsupportedLines: List<String>,
    val time: SheetTime = SheetTime.NOW,
    /** 시간표 조회에서 열차가 하나도 없던 서울 노선. 서울시 시간표는 1~9호선만 있다(2026-11-17 확인). */
    val noTimetableLines: List<String> = emptyList(),
    /** [SheetTime.CUSTOM]일 때 고른 시각("19:30"). */
    val customLabel: String? = null,
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

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 이 역의 호선. 열차 조회가 실패해도 시트 머리에 남는다. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private var lineNames: List<String>
        get() = _lines.value
        set(value) {
            _lines.value = value
        }
    private var time = SheetTime.NOW
    private var custom: Instant? = null
    private var job: Job? = null
    private var loads = 0

    init {
        job = viewModelScope.launch { load() }
    }

    /**
     * 시트가 보이는 동안 부른다. 다시 연 시트가 예전 결과를 그대로 보이지 않게 먼저 한 번 새로 고치고,
     * [지금]이면 30초마다 새로 고친다.
     */
    suspend fun runAutoRefresh() {
        while (true) {
            if (time == SheetTime.NOW) load()
            delay(REFRESH_MS)
        }
    }

    /** 오류·오프라인·오래된 정보의 [다시 시도]: 지금 고른 시각 그대로 다시 불러온다. */
    fun retry() = select(time, custom)

    fun select(time: SheetTime, custom: Instant? = null) {
        this.time = time
        this.custom = custom
        job?.cancel()
        _state.value = UiState.Loading
        job = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        // 호선 목록은 처음 조회가 실패했거나 취소됐어도 다음 불러오기에서 다시 묻는다.
        var lookupFailure: ApiResult.Err? = null
        if (lineNames.isEmpty()) {
            when (val found = stations.search(stationId)) {
                is ApiResult.Ok -> lineNames = found.value.firstOrNull { it.id == stationId }?.lines.orEmpty()
                is ApiResult.Err -> lookupFailure = found
            }
        }
        // 늦게 끝난 불러오기(예: [+1시간]을 누르기 전에 시작한 "지금" 새로 고침)가 새 결과를 덮지 않게 한다.
        val generation = ++loads
        val requested = time
        // 호선을 모르면 시간표를 물을 수 없다: "정보 없음" 대신 그 오류와 [다시 시도]를 보인다(최종 리뷰).
        val failure = lookupFailure
        val result = when {
            requested == SheetTime.NOW -> loadNow()
            lineNames.isEmpty() && failure != null -> failure.toUiState()
            else -> loadTimetable(startAt())
        }
        if (generation == loads && requested == time) _state.value = result
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
        val unsupported = dto.groups.filter { it.source == "none" }.map { it.line } + lineNames.filter { it in NON_SEOUL_LINES }
        val groups = dto.groups.filter { it.source != "none" && it.trains.isNotEmpty() }.map { it.toDirectionTrains() }
        return sheet(groups, unsupported.distinct(), stale = dto.stale)
    }

    private suspend fun loadTimetable(from: Instant): UiState<StationSheetUi> = coroutineScope {
        val seoul = lineNames.filter { it !in NON_SEOUL_LINES }
        val results = seoul.flatMap { line -> directionsOf(line).map { line to it } }.map { (line, direction) ->
            async { stations.timetable(stationId, line, direction, from, TIMETABLE_COUNT) }
        }.awaitAll()
        val tables = results.mapNotNull { (it as? ApiResult.Ok)?.value }
        val groups = tables.mapNotNull { it.toDirectionTrains() }
        val failure = results.filterIsInstance<ApiResult.Err>().firstOrNull()
        if (groups.isEmpty() && failure != null) return@coroutineScope failure.toUiState()
        val withTrains = groups.map { it.line }.toSet()
        sheet(groups, lineNames.filter { it in NON_SEOUL_LINES }, noTimetable = seoul.filter { it !in withTrains })
    }

    private fun sheet(
        groups: List<DirectionTrains>,
        unsupported: List<String>,
        noTimetable: List<String> = emptyList(),
        stale: Boolean = false,
    ): UiState<StationSheetUi> {
        if (groups.isEmpty() && unsupported.isEmpty() && noTimetable.isEmpty()) return UiState.Error(Copy.NO_TRAIN_INFO)
        val label = custom?.takeIf { time == SheetTime.CUSTOM }?.let { CLOCK.format(it.atZone(SEOUL)) }
        return UiState.Content(StationSheetUi(stationId, lineNames, groups, unsupported, time, noTimetable, label), stale)
    }


    private fun ArrivalGroupDto.toDirectionTrains() = DirectionTrains(
        line = line,
        direction = direction,
        towards = towards,
        trains = trains.take(2).map { TrainLine(it.destination, formatRemaining(it.remainingSec), isLast = it.isLast, isExpress = it.isExpress) },
        basis = basisOf(source),
    )

    private fun TimetableDto.toDirectionTrains(): DirectionTrains? {
        if (trains.isEmpty()) return null
        return DirectionTrains(
            line = line,
            direction = direction,
            towards = null,
            trains = trains.map { TrainLine(it.destination, it.departsAt.substring(11, 16), clock = true, isExpress = it.isExpress) },
            basis = basisOf(if (source == "measured") "measured" else "timetable"),
        )
    }

    /** 출처 → 방향 줄의 근거 표식. 실시간이면 null. */
    private fun basisOf(source: String): String? = when (source) {
        "timetable", "official" -> Copy.BY_TIMETABLE
        "measured" -> Copy.BY_MEASURED
        else -> null
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val REFRESH_MS = 30_000L
        const val TIMETABLE_COUNT = 4
    }
}
