package io.github.klanary.jigeumchulbal.ui.station

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.waitStartFromGroup
import io.github.klanary.jigeumchulbal.ui.SessionDest
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.StateContainer
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining
import io.github.klanary.jigeumchulbal.ui.common.formatUpdatedAgo
import io.github.klanary.jigeumchulbal.ui.session.rememberStartSession
import kotlinx.coroutines.delay

/** 역 상세. 화면이 보이는 동안만 30초마다 갱신하고 "n초 전 갱신"을 보여준다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationScreen(
    stationId: String,
    navController: NavController,
    viewModel: StationViewModel = appViewModel {
        StationViewModel(stationId, it.stationRepository, it.favoriteRepository)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.runAutoRefresh() }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val startSession = rememberStartSession(onStarted = { navController.navigate(SessionDest()) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stationId) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = Copy.FAVORITE,
                            tint = if (state.isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.receivedAt?.let { receivedAt ->
                Text(
                    formatUpdatedAgo(((now - receivedAt) / 1000).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            StateContainer(state.arrivals, onRetry = viewModel::refresh, emptyText = Copy.NO_ARRIVALS) { arrivals ->
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(arrivals.groups, key = { "${it.line}-${it.direction}-${it.towards}" }) { group ->
                        GroupCard(group, receivedAt = state.receivedAt ?: now, now = now) {
                            val start = waitStartFromGroup(stationId, group)
                            startSession { settings ->
                                SessionEvent.StartWaiting(start.target, start.mode, null, start.travelSec, settings)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 호선·방향 묶음 하나. [승차 전 알림]은 이 방향의 첫 열차가 설정한 시간 안으로 들어오면 알린다(⑦). */
@Composable
private fun GroupCard(group: ArrivalGroupDto, receivedAt: Long, now: Long, onPreBoarding: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupTitle(group), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onPreBoarding) { Text(Copy.PRE_BOARDING_ALERT) }
            }
            group.trains.take(2).forEach { train ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val destination = train.destination?.let { "${it}행 · " }.orEmpty()
                    Text(destination + formatRemaining(displayRemainingSec(train, receivedAt, now)))
                    if (train.isExpress) AssistChip(onClick = {}, label = { Text(Copy.EXPRESS) })
                    if (train.isLast) AssistChip(onClick = {}, label = { Text(Copy.LAST_TRAIN) })
                }
            }
        }
    }
}
