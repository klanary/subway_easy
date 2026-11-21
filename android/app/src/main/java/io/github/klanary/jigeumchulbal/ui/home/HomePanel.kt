package io.github.klanary.jigeumchulbal.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.routine.minuteLabel
import io.github.klanary.jigeumchulbal.ui.common.ClockIcon
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.StateKind
import io.github.klanary.jigeumchulbal.ui.common.StateMessage
import io.github.klanary.jigeumchulbal.ui.common.StateSize
import io.github.klanary.jigeumchulbal.ui.theme.ButtonShape
import io.github.klanary.jigeumchulbal.ui.theme.Spacing

/**
 * 홈 아래 패널(2차 다듬기 스펙 4.2, 한 손 배치): 검색 줄(검색·경로 찾기·현재 위치)을 엄지 영역에 두고,
 * 그 아래에 오늘 루틴과 자주 가는 경로를 탭 없이 함께 보인다. 패널은 화면 높이의 절반을 넘지 않는다.
 */
@Composable
internal fun HomePanel(
    todayRoutines: List<RoutineEntity>,
    favorites: List<FavoriteRouteEntity>,
    onSearch: () -> Unit,
    onLocate: () -> Unit,
    onRoute: () -> Unit,
    onRoutine: (RoutineEntity) -> Unit,
    onAllRoutines: () -> Unit,
    onFavorite: (FavoriteRouteEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxPanel = (LocalConfiguration.current.screenHeightDp / 2).dp
    Surface(
        modifier.fillMaxWidth().heightIn(max = maxPanel),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Spacing.l).padding(top = Spacing.s, bottom = Spacing.s)) {
            // 손잡이
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
            )
            Row(Modifier.padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                SearchField(onSearch = onSearch, onRoute = onRoute, modifier = Modifier.weight(1f))
                Box(Modifier.width(Spacing.s))
                FilledTonalIconButton(onClick = onLocate, modifier = Modifier.size(52.dp), shape = ButtonShape) {
                    Icon(Icons.Filled.LocationOn, contentDescription = Copy.LOCATE)
                }
            }
            Column(Modifier.padding(top = Spacing.s).verticalScroll(rememberScrollState())) {
                if (todayRoutines.isEmpty() && favorites.isEmpty()) {
                    StateMessage(StateKind.Empty, Copy.HOME_EMPTY_HINT, StateSize.Inline, onRetry = null)
                }
                todayRoutines.forEach { routine ->
                    // "08:10 통학 · 마포 → 공덕"(이름이 비면 경로만)
                    val label = listOf(routine.name, routine.title).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    PanelRow(ClockIcon, "${minuteLabel(routine.minuteOfDay)} $label") { onRoutine(routine) }
                }
                favorites.forEach { route ->
                    PanelRow(Icons.Filled.Star, listOfNotNull(route.fromName, route.viaName, route.toName).joinToString(" → ")) {
                        onFavorite(route)
                    }
                }
                TextButton(onClick = onAllRoutines) { Text(Copy.ALL_ROUTINES) }
            }
        }
    }
}

/** 검색 칸처럼 보이는 버튼: 누르면 역 검색이 열린다. 오른쪽에 [경로 찾기]. */
@Composable
private fun SearchField(onSearch: () -> Unit, onRoute: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 52.dp).background(MaterialTheme.colorScheme.surfaceVariant, ButtonShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clickable(onClick = onSearch).padding(horizontal = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Copy.SEARCH_BAR, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRoute) { Text(Copy.FIND_ROUTE) }
    }
}

@Composable
private fun PanelRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
