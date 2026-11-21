package io.github.klanary.jigeumchulbal.ui.linemap

import android.content.Context
import android.graphics.Picture
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.test.core.app.ApplicationProvider
import com.caverock.androidsvg.SVG
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.screenshot.snapBothThemes
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
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
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
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

    private val routines = listOf(
        RoutineEntity(1, "통학", "마포", "마포", null, null, "공덕", "공덕", "", 0b1111111, 8 * 60 + 10),
        RoutineEntity(2, "", "홍대입구", "홍대입구", null, null, "시청", "시청", "", 0b1111111, 18 * 60),
    )

    private fun snap(
        name: String,
        state: LineMapUiState = ready,
        picture: Picture? = this.picture,
        session: String? = null,
        favorites: List<FavoriteRouteEntity> = emptyList(),
        routines: List<RoutineEntity> = emptyList(),
        failed: Boolean = false,
        fontScale: Float = 1f,
    ) = rule.snapBothThemes("linemap_$name", fontScale) {
        LineMapContent(
            state = state,
            picture = picture,
            mapFailed = failed,
            snapshot = null,
            initialTransform = null,
            favoriteRoutes = favorites,
            todayRoutines = routines,
            showSessionBanner = session != null,
            sessionBanner = { SessionBannerCard(session.orEmpty(), onClick = {}) },
        )
    }

    @Test fun loadingStations() = snap("loading", state = LineMapUiState())
    @Test fun loadingPicture() = snap("loading_picture", picture = null)
    @Test fun ready() = snap("ready")
    @Test fun mapFailed() = snap("failed", state = LineMapUiState(), failed = true)
    @Test fun locationDenied() = snap("denied", state = ready.copy(message = Copy.LOCATION_DENIED, messageAction = MessageAction.OPEN_SETTINGS))
    @Test fun message() = snap("message", state = ready.copy(message = Copy.LOCATION_UNAVAILABLE))
    @Test fun tripOriginPicked() = snap("trip_origin", state = ready.copy(draft = TripDraft(from = StationRef("마포", "마포"))))

    // 홈 아래 패널(2차 다듬기 Task 3)
    @Test fun panelRoutinesAndFavorites() = snap("home_panel_routines", routines = routines, favorites = favorites)
    @Test fun panelEmpty() = snap("home_panel_empty")
    @Test fun panelLargeFont() = snap("home_panel_font130", routines = routines, favorites = favorites, fontScale = 1.3f)
    @Test fun panelWithSession() = snap("home_panel_session", routines = routines, session = "마포 07:42 열차까지 6분")
}
