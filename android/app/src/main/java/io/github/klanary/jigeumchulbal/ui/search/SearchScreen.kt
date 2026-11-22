package io.github.klanary.jigeumchulbal.ui.search

import io.github.klanary.jigeumchulbal.ui.SearchDest
import io.github.klanary.jigeumchulbal.ui.toGraph
import io.github.klanary.jigeumchulbal.ui.linemap.TripDraft
import io.github.klanary.jigeumchulbal.ui.common.StarOutlineIcon
import io.github.klanary.jigeumchulbal.data.local.RecentRouteEntity
import androidx.compose.material3.FabPosition
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.klanary.jigeumchulbal.ui.theme.ButtonShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.filled.Search
import io.github.klanary.jigeumchulbal.ui.common.appBarColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.model.toRef
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.ClockIcon
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.LineBadges
import io.github.klanary.jigeumchulbal.ui.common.StateKind
import io.github.klanary.jigeumchulbal.ui.common.StateLoading
import io.github.klanary.jigeumchulbal.ui.common.StateMessage
import io.github.klanary.jigeumchulbal.ui.common.StateSize
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.returnPick
import kotlinx.coroutines.launch

/** 역 검색(재설계 스펙 4.3). 고른 역을 이전 화면(경로 편집의 칸, 또는 노선도)에 돌려준다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    slot: Slot?,
    navController: NavController,
    viewModel: SearchViewModel = appViewModel {
        SearchViewModel(slot, it.stationRepository, it.recentSearchRepository, it.favoriteStationRepository, it.recentRouteRepository)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun pick(station: StationRef) {
        // 경로 편집이면 그 칸에, 노선도에서 열었으면(slot 없음) 노선도가 그 역으로 가서 역 시트를 연다.
        scope.launch { navController.returnPick(viewModel.select(station)) }
    }

    SearchContent(
        state = state,
        onBack = { navController.popBackStack() },
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::retry,
        onPick = ::pick,
        focus = focus,
        onToggleFavorite = viewModel::toggleFavorite,
        onPickRoute = { r ->
            // 최근 경로를 누르면 그 경로의 결과를 바로 연다(노선도에서 출발·도착을 고른 것과 같다).
            val trip = TripDraft(
                StationRef(r.fromId, r.fromName),
                r.viaId.takeIf { it.isNotEmpty() }?.let { StationRef(it, r.viaName) },
                StationRef(r.toId, r.toName),
            )
            navController.navigate(trip.toGraph()) { popUpTo<SearchDest> { inclusive = true } }
        },
    )
}

/** 역 검색을 그린다(상태만 받는다). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    state: SearchUiState,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onPick: (StationRef) -> Unit = {},
    focus: FocusRequester = remember { FocusRequester() },
    onToggleFavorite: ((StationRef) -> Unit)? = null,
    onPickRoute: (RecentRouteEntity) -> Unit = {},
) {
    // 왼쪽 아래 탭으로 "역 찾기" ↔ "최근 경로"를 바꾼다(수동 검토 2026-11-22)
    var showRoutes by rememberSaveable { mutableStateOf(false) }
    val favoriteIds = state.favorites.map { it.stationId }.toSet()

    @Composable
    fun Star(station: StationRef) {
        val toggle = onToggleFavorite ?: return
        val on = station.id in favoriteIds
        IconButton(onClick = { toggle(station) }) {
            Icon(
                if (on) Icons.Filled.Star else StarOutlineIcon,
                contentDescription = if (on) Copy.REMOVE_FAVORITE_STATION else Copy.ADD_FAVORITE_STATION,
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
                title = {
                    // 입력은 이 칸이 바로 들고 있는다: 상태(StateFlow)가 한 박자 늦게 돌아와 덮으면
                    // 키보드가 조합 중인 글자가 깨진다("방이"가 조합되지 않음, 수동 검토 2026-11-22).
                    var text by rememberSaveable { mutableStateOf(state.query) }
                    TextField(
                        value = text,
                        onValueChange = { text = it; onQueryChange(it) },
                        placeholder = { Text(Copy.SEARCH_HINT) },
                        singleLine = true,
                        // 홈 검색 칸과 같은 모양(둥근 회색 칸 + 돋보기, 2차 다듬기 Task 4 합의)
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        shape = ButtonShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showRoutes = !showRoutes },
                icon = { Icon(if (showRoutes) Icons.Filled.Search else ClockIcon, contentDescription = null) },
                text = { Text(if (showRoutes) Copy.FIND_STATION else Copy.RECENT_ROUTES) },
            )
        },
        floatingActionButtonPosition = FabPosition.Start,
    ) { padding ->
        if (showRoutes) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (state.recentRoutes.isEmpty()) {
                    item { StateMessage(StateKind.Empty, Copy.NO_RECENT_ROUTES, StateSize.Inline, null, Modifier.padding(16.dp)) }
                }
                items(state.recentRoutes, key = { "route-${it.id}" }) { r ->
                    ListItem(
                        leadingContent = { Icon(ClockIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(listOf(r.fromName, r.viaName, r.toName).filter { it.isNotEmpty() }.joinToString(" → ")) },
                        modifier = Modifier.clickable { onPickRoute(r) },
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (state.query.isBlank() && state.favorites.isNotEmpty()) {
                sectionHeader(Copy.FAVORITE_STATIONS)
                items(state.favorites, key = { "fav-${it.stationId}" }) { fav ->
                    val ref = StationRef(fav.stationId, fav.name)
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(fav.name) },
                        trailingContent = { Star(ref) },
                        modifier = Modifier.clickable { onPick(ref) },
                    )
                }
            }
            if (state.query.isBlank() && state.recents.isEmpty() && state.favorites.isEmpty()) {
                // 처음 화면: 무엇을 입력하면 되는지 알려 준다(Task 5 합의).
                item { StateMessage(StateKind.Empty, Copy.SEARCH_START, StateSize.Inline, null, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (state.recents.isNotEmpty()) {
                sectionHeader(Copy.RECENT_SEARCHES)
                items(state.recents, key = { "recent-${it.id}" }) { recent ->
                    ListItem(
                        leadingContent = { Icon(ClockIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(recent.name) },
                        trailingContent = { Star(StationRef(recent.stationId, recent.name)) },
                        modifier = Modifier.clickable { onPick(StationRef(recent.stationId, recent.name)) },
                    )
                }
            }
            if (state.query.isNotBlank()) {
                sectionHeader(Copy.STATIONS)
                resultSection(state.stations, onRetry) { stations ->
                    items(stations, key = { "station-${it.id}" }) { station ->
                        ListItem(
                            headlineContent = { Text(station.name) },
                            supportingContent = { LineBadges(station.lines) },
                            trailingContent = { Star(station.toRef()) },
                            modifier = Modifier.clickable { onPick(station.toRef()) },
                        )
                    }
                }
            }
        }
    }
}


private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

/** 목록 안의 한 구역이라 전체 화면용 StateContainer 대신 한 줄짜리 상태 표시를 쓴다. */
private fun <T> LazyListScope.resultSection(
    state: UiState<List<T>>,
    onRetry: () -> Unit,
    content: LazyListScope.(List<T>) -> Unit,
) {
    when (state) {
        UiState.Loading -> item { StateLoading(StateSize.Inline, Modifier.padding(horizontal = 16.dp)) }
        UiState.Empty -> item { MessageRow(StateKind.Empty, Copy.NO_RESULTS_HINT, onRetry = null) }
        is UiState.Error -> item { MessageRow(StateKind.Error, state.message, onRetry) }
        UiState.Offline -> item { MessageRow(StateKind.Offline, Copy.OFFLINE, onRetry) }
        is UiState.Content -> content(state.data)
    }
}

@Composable
private fun MessageRow(kind: StateKind, text: String, onRetry: (() -> Unit)?) {
    StateMessage(kind, text, StateSize.Inline, onRetry, Modifier.padding(horizontal = 16.dp))
}
