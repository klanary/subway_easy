package io.github.klanary.jigeumchulbal.ui.settings

import androidx.compose.foundation.clickable
import io.github.klanary.jigeumchulbal.ui.theme.ButtonShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.BuildConfig
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.appViewModel

/** 설정(스펙 4.2): 알림 시점, 권한 상태, 정보(출처 — 스펙 10.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it.settingsStore) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current


    var permissions by remember { mutableStateOf(currentPermissionRows(context)) }
    LifecycleResumeEffect(Unit) {
        permissions = currentPermissionRows(context) // 설정 앱에서 돌아오면 다시 읽는다
        onPauseOrDispose {}
    }



    SettingsContent(
        state = state,
        permissions = permissions,
        onBack = { navController.popBackStack() },
        onOpenPermission = { row -> context.startActivity(permissionSettingsIntent(context, row)) },
        onDepartMargin = viewModel::setDepartMargin,
        onAlightLead = viewModel::setAlightLead,
        onStationLead = viewModel::setStationLead,
        onMaxWait = viewModel::setMaxWait,
    )
}

/** 설정을 그린다(상태만 받는다). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    permissions: List<PermissionRow>,
    onBack: () -> Unit = {},
    onOpenPermission: (PermissionRow) -> Unit = {},
    onDepartMargin: (Int) -> Unit = {},
    onAlightLead: (Int) -> Unit = {},
    onStationLead: (Int) -> Unit = {},
    onMaxWait: (Int) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Copy.SETTINGS) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionTitle(Copy.ALERT_TIMING) }
            item {
                OptionRow(Copy.DEPART_MARGIN, DEPART_MARGIN_OPTIONS, state.settings.departMarginSec, ::secondsText, onDepartMargin)
            }
            item {
                OptionRow(Copy.ALIGHT_LEAD, ALIGHT_LEAD_OPTIONS, state.settings.alightLeadSec, { "${secondsText(it)} 전" }, onAlightLead)
            }
            item {
                OptionRow(Copy.STATION_LEAD, STATION_LEAD_OPTIONS, state.settings.stationLeadSec, { "${secondsText(it)} 전" }, onStationLead)
            }
            item {
                OptionRow(Copy.MAX_WAIT, MAX_WAIT_OPTIONS, state.settings.maxWaitMin, { "${it}분" }, onMaxWait)
            }

            item { SectionTitle(Copy.PERMISSIONS) }
            items(permissions) { row ->
                ListItem(
                    // 허용된 권한도 줄을 누르면 설정이 열린다(끄러 갈 길, 2차 다듬기 Task 4).
                    modifier = Modifier.clickable { onOpenPermission(row) },
                    headlineContent = { Text(row.title) },
                    supportingContent = { Text(if (row.granted) Copy.GRANTED else Copy.NOT_GRANTED) },
                    // 허용된 권한은 ✓만, 허용 안 된 권한만 [설정 열기](Task 10 합의).
                    trailingContent = {
                        if (row.granted) {
                            Icon(Icons.Filled.Check, contentDescription = Copy.GRANTED, tint = MaterialTheme.colorScheme.primary)
                        } else {
                            FilledTonalButton(onClick = { onOpenPermission(row) }, shape = ButtonShape) { Text(Copy.OPEN_SETTINGS) }
                        }
                    },
                )
            }

            item { SectionTitle(Copy.ABOUT) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${Copy.APP_NAME} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    // TODO(P1 Task 5): 역사마스터·실시간 도착정보의 이용허락 문구(api-verification §2)를 더한다.
                    Text(Copy.ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
                    Text(Copy.LINEMAP_SOURCE, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


@Composable
private fun SectionTitle(title: String) {
    Column {
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(title: String, options: List<Int>, selected: Int, text: (Int) -> String, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(text(option)) })
            }
        }
    }
}

/** 0 → "0초", 90 → "1분 30초", 120 → "2분" */
private fun secondsText(sec: Int): String {
    val min = sec / 60
    val rest = sec % 60
    return when {
        min == 0 -> "${rest}초"
        rest == 0 -> "${min}분"
        else -> "${min}분 ${rest}초"
    }
}
