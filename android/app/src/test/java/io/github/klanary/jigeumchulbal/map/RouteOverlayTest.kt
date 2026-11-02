package io.github.klanary.jigeumchulbal.map

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.ui.common.WALK_COLOR
import io.github.klanary.jigeumchulbal.ui.common.lineColor
import io.github.klanary.jigeumchulbal.ui.routedetail.stepTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteOverlayTest {
    private fun step(mode: StepMode, path: List<List<Double>>, line: String? = null) = StepDto(
        mode = mode, durationSec = 60, distanceM = 100, path = path, lineName = line,
        stops = if (mode == StepMode.WALK) null else listOf("가", "나"),
    )

    private val route = specRoute().copy(
        legs = listOf(
            LegDto(
                listOf(
                    step(StepMode.WALK, listOf(listOf(37.0, 127.0), listOf(37.1, 127.0))),
                    step(StepMode.SUBWAY, listOf(listOf(37.1, 127.0), listOf(37.2, 127.0)), "2호선"),
                    step(StepMode.WALK, listOf(listOf(37.2, 127.0), listOf(37.2, 127.1))),
                    step(StepMode.SUBWAY, listOf(listOf(37.2, 127.1), listOf(37.3, 127.1)), "3호선"),
                ),
            ),
        ),
    )

    @Test fun walkIsDashedGreyAndSubwayUsesLineColor() {
        val lines = buildOverlay(route).polylines
        assertEquals(listOf(true, false, true, false), lines.map { it.dashed })
        assertEquals(listOf(WALK_COLOR, lineColor("2호선"), WALK_COLOR, lineColor("3호선")), lines.map { it.color })
    }

    @Test fun markersAtStartTransferEnd() {
        assertEquals(
            listOf(
                MarkerSpec(GeoPoint(37.0, 127.0), MarkerKind.START),
                MarkerSpec(GeoPoint(37.2, 127.1), MarkerKind.TRANSFER),
                MarkerSpec(GeoPoint(37.3, 127.1), MarkerKind.END),
            ),
            buildOverlay(route).markers,
        )
    }

    @Test fun stepTitles() {
        val sub = StepDto(StepMode.SUBWAY, 600, 5200, lineName = "2호선", stops = listOf("홍대입구", "합정", "당산"))
        assertEquals("2호선 홍대입구 → 당산 · 2정거장", stepTitle(sub))
        assertEquals("도보 7분", stepTitle(StepDto(StepMode.WALK, 420, 480)))
        assertEquals(
            "7016번 버스 · 3정거장",
            stepTitle(StepDto(StepMode.BUS, 300, 2000, routeName = "7016", stops = listOf("a", "b", "c", "d"))),
        )
    }
}
