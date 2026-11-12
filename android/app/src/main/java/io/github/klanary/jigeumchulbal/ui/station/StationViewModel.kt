package io.github.klanary.jigeumchulbal.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StationUiState(
    val stationId: String,
    val arrivals: UiState<ArrivalsDto> = UiState.Loading,
    val receivedAt: Long? = null,
)

/** 역 상세(스펙 3.1 ①): 호선·방향별 다음 열차. */
class StationViewModel(
    private val stationId: String,
    private val stations: StationRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(StationUiState(stationId))
    val state: StateFlow<StationUiState> = _state.asStateFlow()

    /**
     * 새로 고친 뒤 30초를 기다리기를 반복한다. 화면이 `repeatOnLifecycle(STARTED)` 안에서 부르므로
     * 화면이 안 보이면 코루틴이 취소돼 갱신이 멈춘다(스펙 7.1).
     */
    suspend fun runAutoRefresh() {
        while (true) {
            load()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val result = stations.arrivals(stationId)
        val receivedAt = clock()
        _state.update {
            it.copy(
                arrivals = result.toUiState(isEmpty = { dto -> dto.groups.isEmpty() }, isStale = { dto -> dto.stale }),
                receivedAt = receivedAt,
            )
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
    }
}

/** "2호선 내선 · 합정 방면". 방면을 모르면 "2호선 내선". */
fun groupTitle(g: ArrivalGroupDto): String {
    val head = listOfNotNull(g.line, g.direction).joinToString(" ")
    return if (g.towards != null) "$head · ${g.towards} 방면" else head
}

/** 응답을 받은 뒤 지난 시간을 뺀 남은 초(0 이상). 남은 시간을 모르면 null. */
fun displayRemainingSec(train: TrainDto, receivedAt: Long, now: Long): Int? =
    train.remainingSec?.let { (it - ((now - receivedAt) / 1000).toInt()).coerceAtLeast(0) }
