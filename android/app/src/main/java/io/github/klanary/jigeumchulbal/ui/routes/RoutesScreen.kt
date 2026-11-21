package io.github.klanary.jigeumchulbal.ui.routes

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.klanary.jigeumchulbal.ui.common.TimeWheelDialog
import io.github.klanary.jigeumchulbal.ui.common.appBarColors
import androidx.compose.material3.OutlinedCard
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
import android.app.TimePickerDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.ui.RouteDetailDest
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.LineBadges
import androidx.compose.ui.text.font.FontWeight
import io.github.klanary.jigeumchulbal.ui.common.StateContainer
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.formatClock
import io.github.klanary.jigeumchulbal.ui.common.pickedTime
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowState
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
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val base = java.time.LocalTime.now()
        TimeWheelDialog(base.hour, base.minute, onConfirm = { h, m ->
            picking = false
            viewModel.setDepartAt(pickedTime(h, m))
        }, onDismiss = { picking = false })
    }
    RoutesContent(
        state = state,
        now = now,
        onBack = { navController.popBackStack() },
        onDepartNow = { viewModel.setDepartAt(null) },
        onPickTime = { picking = true },
        onRetry = viewModel::search,
        onRoute = { id ->
            viewModel.select(id)
            navController.navigate(RouteDetailDest(id))
        },
    )
}

/** 경로 결과를 그린다(상태만 받는다). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutesContent(
    state: RouteFlowState,
    now: Long,
    onBack: () -> Unit = {},
    onDepartNow: () -> Unit = {},
    onPickTime: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRoute: (String) -> Unit = {},
) {
    // "마포 → (공덕) → 홍대입구"(재설계 스펙 4.4)
    val title = listOfNotNull(state.origin?.name, state.via?.name?.let { "($it)" }, state.dest?.name).joinToString(" → ")

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
        // 출발 시각: 지금 / 시각 선택. 시각을 고르면 시간표 열차와 도착 예상이 붙는다.
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.departAt == null, onDepartNow, { Text(Copy.SHEET_NOW) })
            FilterChip(state.departAt != null, onPickTime, { Text(state.departAt?.let { formatClock(it.toEpochMilli()) + " 출발" } ?: Copy.SHEET_PICK_TIME) })
        }
        StateContainer(
            state = state.results ?: UiState.Loading,
            onRetry = onRetry,
            emptyText = if (state.departAt != null) Copy.NO_ROUTES_AT_TIME else Copy.NO_ROUTES_HINT,
        ) { found ->
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(found.routes, key = { it.id }) { route ->
                    RouteCard(route.toCardUi(state.receivedAt ?: now, now)) { onRoute(route.id) }
                }
            }
        }
        }
    }
}


@Composable
private fun RouteCard(ui: RouteCardUi, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.totalText, style = MaterialTheme.typography.headlineMedium)
                Text(ui.transfersText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 탈 호선을 순서대로(Task 6 합의)
            if (ui.lines.isNotEmpty()) LineBadges(ui.lines, arrow = true)
            SegmentBar(ui.segments)
            ui.boardingText?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            ui.transferText?.let {
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
