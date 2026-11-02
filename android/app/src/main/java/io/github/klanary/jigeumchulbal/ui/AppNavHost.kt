package io.github.klanary.jigeumchulbal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HomeDest) { appGraph() }
}

/** 앱의 모든 화면. 아직 만들지 않은 화면은 제목만 있는 자리 표시 화면이고, 각 작업이 바꾼다. */
fun NavGraphBuilder.appGraph() {
    composable<HomeDest> { PlaceholderScreen("지금출발") }
    navigation<RouteFlowGraph>(startDestination = RouteEditDest) {
        composable<RouteEditDest> { PlaceholderScreen("경로 편집") }
        composable<RoutesDest> { PlaceholderScreen("경로 결과") }
        composable<RouteDetailDest> { PlaceholderScreen("경로 상세") }
    }
    composable<SearchDest> { PlaceholderScreen("통합 검색") }
    composable<MapPickDest> { PlaceholderScreen("지도에서 선택") }
    composable<StationDest> { PlaceholderScreen("역 상세") }
    composable<SettingsDest> { PlaceholderScreen("설정") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(title: String) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("준비 중인 화면이에요")
        }
    }
}
