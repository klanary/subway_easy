package io.github.klanary.jigeumchulbal.ui.linemap

import android.content.Context
import android.graphics.Picture
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.test.core.app.ApplicationProvider
import com.caverock.androidsvg.SVG
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.screenshot.snapBothThemes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 노선도 홈의 상태별 모습(화면 다듬기 Task 3). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class LineMapContentScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val map: LineMapStations by lazy {
        parseLineMapStations(context.assets.open(LINEMAP_STATIONS_ASSET).bufferedReader().use { it.readText() })
    }
    private val picture: Picture by lazy { SVG.getFromAsset(context.assets, LINEMAP_SVG_ASSET).renderToPicture() }
    private val ready get() = LineMapUiState(map = map)

    private val favorites = listOf(
        FavoriteRouteEntity(1, "마포", "마포", null, null, "강남", "강남", 0),
        FavoriteRouteEntity(2, "홍대입구", "홍대입구", "시청", "시청", "잠실", "잠실", 0),
    )

    private fun snap(
        name: String,
        state: LineMapUiState = ready,
        picture: Picture? = this.picture,
        nextRoutine: String? = null,
        session: String? = null,
        favorites: List<FavoriteRouteEntity> = emptyList(),
        expanded: Boolean = false,
        tab: Int = 0,
        failed: Boolean = false,
        routineList: @androidx.compose.runtime.Composable () -> Unit = { ListItem(headlineContent = { Text(Copy.NO_ROUTINES) }) },
    ) = snapBothThemes("linemap_$name") {
        LineMapContent(
            state = state,
            picture = picture,
            mapFailed = failed,
            snapshot = null,
            initialTransform = null,
            favoriteRoutes = favorites,
            nextRoutine = nextRoutine,
            showSessionBanner = session != null,
            sessionBanner = { SessionBannerCard(session.orEmpty(), onClick = {}) },
            routineList = routineList,
            panelExpanded = expanded,
            panelTab = tab,
        )
    }

    @Test fun loadingStations() = snap("loading", state = LineMapUiState())
    @Test fun loadingPicture() = snap("loading_picture", picture = null)
    @Test fun ready() = snap("ready")
    @Test fun mapFailed() = snap("failed", state = LineMapUiState(), failed = true)
    @Test fun locationDenied() = snap("denied", state = ready.copy(message = Copy.LOCATION_DENIED, messageAction = MessageAction.OPEN_SETTINGS))
    @Test fun message() = snap("message", state = ready.copy(message = Copy.LOCATION_UNAVAILABLE))
    @Test fun tripOriginPicked() = snap("trip_origin", state = ready.copy(draft = TripDraft(from = StationRef("마포", "마포"))))
    @Test fun session() = snap("session", session = "마포 07:42 열차까지 6분")
    @Test fun collapsedWithNextRoutine() = snap("next_routine", nextRoutine = "07:30 통학")
    @Test fun routinesEmpty() = snap("routines_empty", expanded = true, tab = 0)
    @Test fun favoritesEmpty() = snap("favorites_empty", expanded = true, tab = 1)
    @Test fun favoritesList() = snap("favorites", favorites = favorites, expanded = true, tab = 1)
}
