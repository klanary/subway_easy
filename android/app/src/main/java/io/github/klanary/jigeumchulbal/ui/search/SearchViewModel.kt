package io.github.klanary.jigeumchulbal.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.remote.PlaceDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.repository.MyPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.PlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.RecentSearchRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SearchUiState(
    val query: String = "",
    val myPlaces: List<MyPlaceEntity> = emptyList(),
    val recents: List<RecentSearchEntity> = emptyList(),
    val stations: UiState<List<StationDto>> = UiState.Empty,
    val places: UiState<List<PlaceDto>> = UiState.Empty,
)

/** 역과 장소 검색 결과. 각자 상태를 가져 한쪽 실패가 다른 쪽을 가리지 않는다. */
private data class SearchResults(
    val stations: UiState<List<StationDto>>,
    val places: UiState<List<PlaceDto>>,
) {
    companion object {
        val NONE = SearchResults(UiState.Empty, UiState.Empty)
        val LOADING = SearchResults(UiState.Loading, UiState.Loading)
    }
}

/**
 * 통합 검색(스펙 3.1 ②). 검색어 하나로 역(이름·초성)과 장소를 함께 찾는다.
 * 입력이 멈추고 300ms 뒤에 검색하고, 새 검색어가 오면 이전 검색을 취소한다.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val slot: Slot?,
    private val stations: StationRepository,
    private val places: PlaceRepository,
    myPlaces: MyPlaceRepository,
    private val recents: RecentSearchRepository,
    location: LocationProvider,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retries = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 장소를 가까운 순으로 받기 위한 현재 위치. 처음 장소 검색 때 한 번만 구한다. */
    private val near = viewModelScope.async(start = CoroutineStart.LAZY) {
        if (location.hasPermission()) runCatching { location.current() }.getOrNull() else null
    }

    private val results: Flow<SearchResults> =
        merge(query.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(), retries)
            // flatMapLatest: 새 검색어가 오면 이전 검색을 취소해 늦게 온 결과가 덮어쓰지 않는다.
            .flatMapLatest { q -> if (q.isEmpty()) flowOf(SearchResults.NONE) else search(q) }

    val state: StateFlow<SearchUiState> = combine(
        query,
        myPlaces.places,
        recents.recent,
        results.onStart { emit(SearchResults.NONE) },
    ) { q, mine, recent, found ->
        val key = q.trim()
        SearchUiState(
            query = q,
            myPlaces = mine.filter { key.isEmpty() || key in it.label },
            recents = recent.filter { key.isEmpty() || key in it.label },
            stations = found.stations,
            places = found.places,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    fun onQueryChange(q: String) {
        query.value = q
    }

    /** 오류가 난 검색을 같은 검색어로 다시 한다. */
    fun retry() {
        retries.tryEmit(query.value.trim())
    }

    /** 고른 항목을 최근 검색에 남기고 이전 화면에 돌려줄 결과를 만든다. */
    suspend fun select(endpoint: Endpoint): PickResult {
        recents.record(endpoint)
        return PickResult(slot, endpoint)
    }

    private fun search(q: String): Flow<SearchResults> = channelFlow {
        val lock = Mutex()
        var current = SearchResults.LOADING
        send(current)
        launch {
            val found = stations.search(q).toUiState(isEmpty = { it.isEmpty() })
            lock.withLock {
                current = current.copy(stations = found)
                send(current)
            }
        }
        launch {
            val found = places.search(q, near.await()).toUiState(isEmpty = { it.isEmpty() })
            lock.withLock {
                current = current.copy(places = found)
                send(current)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
