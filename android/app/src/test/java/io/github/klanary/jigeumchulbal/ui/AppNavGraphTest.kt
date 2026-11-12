package io.github.klanary.jigeumchulbal.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import io.github.klanary.jigeumchulbal.data.model.StationRef
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 기기 없이 내비게이션 그래프를 확인한다. 인자 타입을 지원하지 않으면 앱이 켜질 때 그래프를 만들다 죽는다. */
@RunWith(RobolectricTestRunner::class)
class AppNavGraphTest {
    /** back stack entry의 SavedStateHandle을 쓰려면 생명주기와 ViewModelStore가 있어야 한다. */
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private fun navController() = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        setLifecycleOwner(owner)
        setViewModelStore(ViewModelStore())
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = HomeDest) { appGraph(this@apply) }
    }

    @Test fun nullableEnumSlotRoundTrips() {
        val nav = navController()
        nav.navigate(SearchDest(Slot.DEST))
        assertEquals(SearchDest(Slot.DEST), nav.currentBackStackEntry!!.toRoute<SearchDest>())
        nav.navigate(SearchDest(null))
        assertEquals(SearchDest(null), nav.currentBackStackEntry!!.toRoute<SearchDest>())
    }

    @Test fun stationPickedInSearchReturnsToRouteEdit() {
        val nav = navController()
        nav.navigate(RouteFlowGraph())
        val routeEdit = nav.currentBackStackEntry!!
        nav.navigate(SearchDest(Slot.DEST))
        nav.returnPick(PickResult(Slot.DEST, StationRef("강남", "강남")))
        assertEquals(routeEdit, nav.currentBackStackEntry)
        assertEquals(true, routeEdit.savedStateHandle.contains("pick_result"))
    }

    @Test fun sessionScreenIsReachable() {
        val nav = navController()
        nav.navigate(SessionDest())
        assertEquals(SessionDest(), nav.currentBackStackEntry!!.toRoute<SessionDest>())
        // 알림의 [다른 열차였어요]로 열 때는 고르기 목록을 여는 인자가 함께 온다(위치 결합 스펙 7.1).
        nav.navigate(SessionDest(pickTrain = true))
        assertEquals(SessionDest(pickTrain = true), nav.currentBackStackEntry!!.toRoute<SessionDest>())
    }

    @Test fun routeFlowGraphStartsAtRouteEdit() {
        val nav = navController()
        nav.navigate(RouteFlowGraph(favoriteRouteId = 3))
        assertEquals(RouteEditDest::class.qualifiedName, nav.currentDestination!!.route)
    }
}
