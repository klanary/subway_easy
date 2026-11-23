package io.github.klanary.jigeumchulbal.ui

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 기기 없이 내비게이션 그래프를 확인한다. 인자 타입을 지원하지 않으면 앱이 켜질 때 그래프를 만들다 죽는다. */
@RunWith(RobolectricTestRunner::class)
class AppNavGraphTest {
    private fun navController() = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = HomeDest) { appGraph() }
    }

    @Test fun nullableEnumSlotRoundTrips() {
        val nav = navController()
        nav.navigate(SearchDest(Slot.DEST))
        assertEquals(SearchDest(Slot.DEST), nav.currentBackStackEntry!!.toRoute<SearchDest>())
        nav.navigate(MapPickDest(null))
        assertEquals(MapPickDest(null), nav.currentBackStackEntry!!.toRoute<MapPickDest>())
    }

    @Test fun routeFlowGraphStartsAtRouteEdit() {
        val nav = navController()
        nav.navigate(RouteFlowGraph(destMyPlaceId = 3))
        assertEquals(RouteEditDest::class.qualifiedName, nav.currentDestination!!.route)
    }
}
