package io.github.klanary.jigeumchulbal.ui.search

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
import io.github.klanary.jigeumchulbal.ui.common.Copy
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
        SearchViewModel(slot, it.stationRepository, it.recentSearchRepository)
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(Copy.SEARCH_HINT) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.recents.isNotEmpty()) {
                sectionHeader(Copy.RECENT_SEARCHES)
                items(state.recents, key = { "recent-${it.id}" }) { recent ->
                    ListItem(
                        headlineContent = { Text(recent.name) },
                        modifier = Modifier.clickable { pick(StationRef(recent.stationId, recent.name)) },
                    )
                }
            }
            if (state.query.isNotBlank()) {
                sectionHeader(Copy.STATIONS)
                resultSection(state.stations, viewModel::retry) { stations ->
                    items(stations, key = { "station-${it.id}" }) { station ->
                        ListItem(
                            headlineContent = { Text(station.name) },
                            supportingContent = { Text(station.lines.joinToString(" · ")) },
                            modifier = Modifier.clickable { pick(station.toRef()) },
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
        UiState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        UiState.Empty -> item { MessageRow(Copy.NO_RESULTS, onRetry = null) }
        is UiState.Error -> item { MessageRow(state.message, onRetry) }
        UiState.Offline -> item { MessageRow(Copy.OFFLINE, onRetry) }
        is UiState.Content -> content(state.data)
    }
}

@Composable
private fun MessageRow(text: String, onRetry: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = onRetry?.let { retry -> { TextButton(onClick = retry) { Text(Copy.RETRY) } } },
    )
}
