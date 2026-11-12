package io.github.klanary.jigeumchulbal.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.ui.RouteDetailDest
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.StateContainer
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowViewModel
import kotlinx.coroutines.delay

/** 경로 결과 목록(스펙 3.1 ④). 카드를 누르면 경로 상세로 간다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(viewModel: RouteFlowViewModel, navController: NavController) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // "다음 열차 N분"이 줄어들도록 10초마다 지금 시각을 갱신한다.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    val title = listOfNotNull(state.origin?.name, state.dest?.name).joinToString(" → ")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        StateContainer(
            state = state.results ?: UiState.Loading,
            onRetry = viewModel::search,
            emptyText = Copy.NO_ROUTES,
            modifier = Modifier.padding(padding),
        ) { found ->
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(found.routes, key = { it.id }) { route ->
                    RouteCard(route.toCardUi(state.receivedAt ?: now, now)) {
                        viewModel.select(route.id)
                        navController.navigate(RouteDetailDest(route.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(ui: RouteCardUi, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.totalText, style = MaterialTheme.typography.headlineSmall)
            Text("${ui.transfersText} · ${ui.fareText}", style = MaterialTheme.typography.bodyMedium)
            SegmentBar(ui.segments)
            ui.nextTrainText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 단계별 소요 시간 비율로 나눈 구간 막대(도보 회색, 지하철 호선 색, 버스 초록). */
@Composable
fun SegmentBar(segments: List<Segment>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        segments.forEach { segment ->
            // weight는 0보다 커야 한다: 소요 시간 0인 단계도 얇게 보이게 한다.
            Box(Modifier.weight(segment.weight.coerceAtLeast(0.01f)).fillMaxHeight().background(Color(segment.color)))
        }
    }
}
