package io.github.klanary.jigeumchulbal.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.repository.RecentSearchRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class SearchUiState(
    val query: String = "",
    val recents: List<RecentSearchEntity> = emptyList(),
    val stations: UiState<List<StationDto>> = UiState.Empty,
)

/**
 * 역 검색(재설계 스펙 4.3): 이름·초성. 입력이 멈추고 300ms 뒤에 검색하고,
 * 새 검색어가 오면 이전 검색을 취소한다. 검색어가 비면 최근 검색한 역을 보인다.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val slot: Slot?,
    private val stations: StationRepository,
    private val recents: RecentSearchRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retries = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val results: Flow<UiState<List<StationDto>>> =
        merge(query.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(), retries)
            // flatMapLatest: 새 검색어가 오면 이전 검색을 취소해 늦게 온 결과가 덮어쓰지 않는다.
            .flatMapLatest { q ->
                if (q.isEmpty()) {
                    flowOf<UiState<List<StationDto>>>(UiState.Empty)
                } else {
                    flow<UiState<List<StationDto>>> {
                        emit(UiState.Loading)
                        emit(stations.search(q).toUiState(isEmpty = { it.isEmpty() }))
                    }
                }
            }

    val state: StateFlow<SearchUiState> = combine(
        query,
        recents.recent,
        results.onStart { emit(UiState.Empty) },
    ) { q, recent, found ->
        val key = q.trim()
        SearchUiState(query = q, recents = recent.filter { key.isEmpty() || key in it.name }, stations = found)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    fun onQueryChange(q: String) {
        query.value = q
    }

    /** 오류가 난 검색을 같은 검색어로 다시 한다. */
    fun retry() {
        retries.tryEmit(query.value.trim())
    }

    /** 고른 역을 최근 검색에 남기고 이전 화면에 돌려줄 결과를 만든다. */
    suspend fun select(station: StationRef): PickResult {
        recents.record(station)
        return PickResult(slot, station)
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
