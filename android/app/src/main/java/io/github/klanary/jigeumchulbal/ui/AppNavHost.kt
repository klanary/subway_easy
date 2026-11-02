package io.github.klanary.jigeumchulbal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.home.HomeScreen
import io.github.klanary.jigeumchulbal.ui.mappick.MapPickScreen
import io.github.klanary.jigeumchulbal.ui.routedetail.RouteDetailScreen
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteEditScreen
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowViewModel
import io.github.klanary.jigeumchulbal.ui.routes.RoutesScreen
import io.github.klanary.jigeumchulbal.ui.search.SearchScreen
import io.github.klanary.jigeumchulbal.ui.station.StationScreen

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HomeDest) { appGraph(navController) }
}

/** 앱의 모든 화면. 아직 만들지 않은 화면은 제목만 있는 자리 표시 화면이고, 각 작업이 바꾼다. */
fun NavGraphBuilder.appGraph(navController: NavController) {
    composable<HomeDest> { HomeScreen(navController) }
    navigation<RouteFlowGraph>(startDestination = RouteEditDest) {
        composable<RouteEditDest> { entry ->
            val flow = routeFlowEntry(navController, entry)
            val viewModel = routeFlowViewModel(flow)
            entry.OnPickResult(viewModel::apply)
            RouteEditScreen(viewModel, flow.toRoute<RouteFlowGraph>().destMyPlaceId, navController)
        }
        composable<RoutesDest> { entry ->
            RoutesScreen(routeFlowViewModel(routeFlowEntry(navController, entry)), navController)
        }
        composable<RouteDetailDest> { entry ->
            RouteDetailScreen(
                routeFlowViewModel(routeFlowEntry(navController, entry)),
                entry.toRoute<RouteDetailDest>().routeId,
                navController,
            )
        }
    }
    composable<SearchDest> { entry -> SearchScreen(entry.toRoute<SearchDest>().slot, navController) }
    composable<MapPickDest> { entry -> MapPickScreen(entry.toRoute<MapPickDest>().slot, navController) }
    composable<StationDest> { entry -> StationScreen(entry.toRoute<StationDest>().stationId, navController) }
    composable<SettingsDest> { PlaceholderScreen("설정") }
}

/** 경로 흐름(편집·결과·상세)이 공유하는 중첩 그래프의 back stack entry. */
@Composable
private fun routeFlowEntry(navController: NavController, entry: NavBackStackEntry): NavBackStackEntry =
    remember(entry) { navController.getBackStackEntry<RouteFlowGraph>() }

@Composable
private fun routeFlowViewModel(flow: NavBackStackEntry): RouteFlowViewModel =
    appViewModel(flow) { RouteFlowViewModel(it.routeRepository, it.myPlaceRepository, it.locationProvider) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(title: String) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("준비 중인 화면이에요")
        }
    }
}
