package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMathTest {
    private fun t(sec: Int?, dest: String = "성수") = TrainDto(dest, sec)

    private fun step(mode: StepMode, sec: Int, line: String? = null, stops: Int = 2, route: String? = null) =
        StepDto(
            mode, sec, 0, lineName = line, routeName = route,
            stops = if (mode == StepMode.WALK) null else List(stops) { "역$it" },
        )

    private fun route(vararg legs: List<StepDto>) = specRoute().copy(legs = legs.map { LegDto(it) })

    private val twoLines = route(
        listOf(
            step(StepMode.WALK, 300), step(StepMode.SUBWAY, 600, "2호선", 3),
            step(StepMode.WALK, 120), step(StepMode.SUBWAY, 900, "3호선", 5), step(StepMode.WALK, 200),
        ),
    )

    @Test fun departurePicksFirstCatchableTrain() =
        assertEquals(
            TrainPlan(alertAt = 120_000, trainArrivalAt = 600_000, destination = "성수"),
            departurePlan(listOf(t(300), t(600), t(900)), receivedAt = 0, travelSec = 420, marginSec = 60),
        )

    @Test fun noCatchableTrain() {
        assertNull(departurePlan(listOf(t(300), t(null)), 0, 420, 60))
        assertNull(departurePlan(emptyList(), 0, 420, 60))
    }

    @Test fun minutesBeforeAlertsAtLeadOrImmediately() {
        assertEquals(TrainPlan(420_000, 600_000, "성수"), minutesBeforePlan(listOf(t(600)), 0, leadSec = 180, now = 0))
        assertEquals(5_000L, minutesBeforePlan(listOf(t(120)), 0, 180, now = 5_000)!!.alertAt)
    }

    @Test fun alightPointsFromFirstTransit() {
        val p = alightPoints(twoLines, boardedAt = 0, from = StartFrom.FIRST_TRANSIT, leadSec = 120)
        assertEquals(listOf(600_000L, 1_620_000L), p.map { it.alightAt })
        assertEquals(listOf(480_000L, 1_500_000L), p.map { it.alertAt })
        assertEquals(listOf(PointKind.TRANSFER, PointKind.FINAL), p.map { it.kind })
        assertEquals(listOf(2, 4), p.map { it.stopsToGo })
        assertEquals("3호선", p[0].nextLine)
    }

    @Test fun firstSubwaySkipsEarlierBus() {
        val r = route(
            listOf(step(StepMode.BUS, 300, route = "7016"), step(StepMode.WALK, 60), step(StepMode.SUBWAY, 600, "2호선")),
        )
        assertEquals(listOf(600_000L), alightPoints(r, 0, StartFrom.FIRST_SUBWAY, 120).map { it.alightAt })
    }

    @Test fun shortRideAlertsImmediately() {
        val r = route(listOf(step(StepMode.SUBWAY, 90, "2호선")))
        assertEquals(
            10_000L,
            alightPoints(r, boardedAt = 10_000, from = StartFrom.FIRST_TRANSIT, leadSec = 120).single().alertAt,
        )
    }

    @Test fun viaLegEndIsViaPoint() {
        val r = route(
            listOf(step(StepMode.SUBWAY, 600, "2호선")),
            listOf(step(StepMode.WALK, 60), step(StepMode.SUBWAY, 300, "6호선")),
        )
        assertEquals(listOf(PointKind.VIA, PointKind.FINAL), alightPoints(r, 0, StartFrom.FIRST_TRANSIT, 120).map { it.kind })
    }

    @Test fun stopLabels() {
        fun pt(name: String, bus: Boolean) = AlightPoint(name, bus, PointKind.FINAL, 0, 0, 1)
        assertEquals("홍대입구역", stopLabel(pt("홍대입구", false)))
        assertEquals("서울역", stopLabel(pt("서울역", false)))
        assertEquals("신촌오거리 정류장", stopLabel(pt("신촌오거리", true)))
    }

    @Test fun pickGroupByLineAndDirection() {
        val inner = ArrivalGroupDto("2호선", "내선", "신촌", emptyList())
        val outer = ArrivalGroupDto("2호선", "외선", "합정", emptyList())
        assertEquals(outer, pickGroup(listOf(inner, outer), WaitTarget("홍대입구", "홍대입구", "2호선", "합정", "외선")))
        assertEquals(inner, pickGroup(listOf(inner, outer), WaitTarget("홍대입구", "홍대입구", "2호선", "신촌", null)))
    }

    @Test fun hasTransitOnlyWhenThereIsARide() {
        assertTrue(twoLines.hasTransit())
        assertFalse(route(listOf(step(StepMode.WALK, 300))).hasTransit())
    }

    @Test fun withoutPathsDropsCoordinatesOnly() {
        val stripped = twoLines.withoutPaths()
        assertEquals(emptyList<List<Double>>(), stripped.legs.flatMap { it.steps }.flatMap { it.path })
        assertEquals(twoLines.legs.flatMap { it.steps }.map { it.durationSec }, stripped.legs.flatMap { it.steps }.map { it.durationSec })
    }
}
