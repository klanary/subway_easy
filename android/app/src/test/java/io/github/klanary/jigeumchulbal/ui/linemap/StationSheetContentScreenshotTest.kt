package io.github.klanary.jigeumchulbal.ui.linemap

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.screenshot.snapBothThemes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 역 시트의 상태별 모습(화면 다듬기 Task 4). 시트 틀(ModalBottomSheet)은 팝업 창이라 안쪽만 찍는다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class StationSheetContentScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = StationSheetUi(
        name = "시청",
        lines = listOf("1호선", "2호선"),
        groups = listOf(
            DirectionTrains("1호선", "상행", "종각", listOf(TrainLine("청량리", "곧 도착", isExpress = true), TrainLine("광운대", "1분"))),
            DirectionTrains("2호선", "외선", "충정로", listOf(TrainLine("성수", "1분"), TrainLine("성수", "2분"))),
            DirectionTrains("2호선", "내선", "을지로입구", listOf(TrainLine("성수", "곧 도착"), TrainLine("성수", "5분", isLast = true))),
            DirectionTrains("1호선", "하행", "서울", listOf(TrainLine("인천", "1분", isLast = true), TrainLine("인천", "3분"))),
        ),
        unsupportedLines = emptyList(),
    )

    private fun snap(name: String, state: UiState<StationSheetUi>, draft: TripDraft = TripDraft(), station: String = "시청") =
        rule.snapBothThemes("sheet_$name") { StationSheetContent(station, state, draft) }

    @Test fun loading() = snap("loading", UiState.Loading)
    @Test fun realtime() = snap("realtime", UiState.Content(now))
    @Test fun stale() = snap("stale", UiState.Content(now, stale = true))
    @Test fun error() = snap("error", UiState.Error(Copy.SERVER_PROBLEM))
    @Test fun offline() = snap("offline", UiState.Offline)
    @Test fun noTrains() = snap("no_trains", UiState.Error(Copy.NO_TRAIN_INFO))

    @Test fun mixedBasis() = snap(
        "mixed",
        UiState.Content(
            now.copy(
                name = "김포공항",
                lines = listOf("5호선", "9호선", "공항철도", "김포골드라인"),
                groups = listOf(
                    DirectionTrains("5호선", "상행", null, listOf(TrainLine("방화", "3분"), TrainLine("방화", "11분")), basis = Copy.BY_TIMETABLE),
                    DirectionTrains("공항철도", "하행", "계양", listOf(TrainLine("인천공항2터미널", "6분"), TrainLine("인천공항2터미널", "18분")), basis = Copy.BY_MEASURED),
                ),
                unsupportedLines = listOf("김포골드라인"),
            ),
        ),
        station = "김포공항",
    )

    @Test fun plusHour() = snap(
        "plus_1h",
        UiState.Content(
            now.copy(
                time = SheetTime.PLUS_1H,
                groups = listOf(
                    DirectionTrains(
                        "1호선", "상행", null,
                        listOf("19:32" to "청량리", "19:38" to "광운대", "19:44" to "청량리", "19:51" to "소요산").map { TrainLine(it.second, it.first, clock = true) },
                        basis = Copy.BY_TIMETABLE,
                    ),
                    DirectionTrains(
                        "2호선", "외선", null,
                        listOf("19:30", "19:35", "19:40", "19:45").map { TrainLine("성수", it, clock = true) },
                        basis = Copy.BY_TIMETABLE,
                    ),
                ),
                noTimetableLines = listOf("경의중앙선"),
            ),
        ),
    )

    @Test fun customTime() = snap("custom", UiState.Content(now.copy(time = SheetTime.CUSTOM, customLabel = "19:30")))
    @Test fun originAlreadyHere() = snap("origin_here", UiState.Content(now), draft = TripDraft(from = StationRef("시청", "시청")))
    @Test fun viaAvailable() = snap(
        "via",
        UiState.Content(now),
        draft = TripDraft(from = StationRef("마포", "마포"), to = StationRef("강남", "강남")),
    )
}
