package io.github.klanary.jigeumchulbal.ui.routeedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.location.rememberLocationPermissionRequest
import io.github.klanary.jigeumchulbal.ui.RoutesDest
import io.github.klanary.jigeumchulbal.ui.SearchDest
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy

/** 경로 편집. 출발 기본값은 현재 위치이고, 칸을 누르면 통합 검색이 열린다(스펙 4.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditScreen(
    viewModel: RouteFlowViewModel,
    destMyPlaceId: Long?,
    navController: NavController,
    favoriteRouteId: Long? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requestLocation = rememberLocationPermissionRequest { granted -> if (granted) viewModel.useCurrentLocation() }

    LaunchedEffect(Unit) {
        if (favoriteRouteId != null) {
            // 홈의 자주 가는 경로 카드: 편집을 건너뛰고 바로 경로 결과(⑨). 결과에서 뒤로 오면 편집 화면이다.
            viewModel.init(destMyPlaceId, favoriteRouteId)
            navController.navigate(RoutesDest)
            return@LaunchedEffect
        }
        viewModel.init(destMyPlaceId)
        // 홈 검색창으로 들어와 도착이 비어 있으면 바로 검색을 연다(한 번만).
        if (destMyPlaceId == null && viewModel.consumeAutoOpenSearch()) {
            navController.navigate(SearchDest(Slot.DEST))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Copy.ROUTE_EDIT_TITLE) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                EndpointRow(
                    label = Copy.ORIGIN,
                    value = state.origin?.label,
                    hint = state.originHint ?: Copy.ORIGIN_HINT,
                    onClick = { navController.navigate(SearchDest(Slot.ORIGIN)) },
                )
                if (state.viaVisible) {
                    EndpointRow(
                        label = Copy.VIA,
                        value = state.via?.label,
                        hint = Copy.VIA_HINT,
                        onClick = { navController.navigate(SearchDest(Slot.VIA)) },
                        trailing = {
                            IconButton(onClick = viewModel::toggleVia) {
                                Icon(Icons.Filled.Close, contentDescription = Copy.REMOVE_VIA)
                            }
                        },
                    )
                }
                EndpointRow(
                    label = Copy.DEST,
                    value = state.dest?.label,
                    hint = Copy.DEST_HINT,
                    onClick = { navController.navigate(SearchDest(Slot.DEST)) },
                )
            }
            if (state.origin == null) {
                AssistChip(onClick = requestLocation, label = { Text(Copy.CURRENT_LOCATION) })
            }
            if (!state.viaVisible) {
                TextButton(onClick = viewModel::toggleVia) { Text(Copy.ADD_VIA) }
            }
            TextButton(onClick = viewModel::swap) { Text(Copy.SWAP) }
            Button(
                onClick = {
                    viewModel.search()
                    navController.navigate(RoutesDest)
                },
                enabled = state.canSearch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(Copy.SEARCH_ROUTES)
            }
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String?,
    hint: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = {
            if (value != null) {
                Text(value)
            } else {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
