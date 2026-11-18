package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
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

    @Test fun planKeepsTrainNo() = assertEquals(
        "5123",
        departurePlan(listOf(TrainDto("하남검단산", 600, trainNo = "5123")), receivedAt = 0, travelSec = 420, marginSec = 60)!!.trainNo,
    )

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

    @Test fun alightUsesTrainArrivalTimesIncludingTransferWait() {
        // 운행표 경로(2026-11-18): 08:00→08:10 탄 뒤 환승역에서 10분 기다려 08:20→08:30.
        // 단계 소요만 더하면 둘째 하차가 08:20으로 10분 일찍 울린다(최종 리뷰 #4).
        fun ride(dep: String, arr: String) = step(StepMode.SUBWAY, 600, line = "5호선").copy(
            departsAt = "2026-11-18T$dep:00+09:00", arrivesAt = "2026-11-18T$arr:00+09:00",
        )
        val r = route(listOf(ride("08:00", "08:10"), ride("08:20", "08:30")))
        val points = alightPoints(r, boardedAt = 0, from = StartFrom.FIRST_SUBWAY, leadSec = 120)
        assertEquals(listOf(600_000L, 1_800_000L), points.map { it.alightAt })
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

    @Test fun withoutPathsDropsCandidates() {
        // 세션·루틴 저장본에는 후보 열차 목록을 남기지 않는다(DataStore 크기).
        val candidate = CandidateDto(trainNo = "5301", departsAt = "2026-11-18T08:05:00+09:00")
        val withCandidates = twoLines.copy(
            legs = twoLines.legs.map { leg -> leg.copy(steps = leg.steps.map { it.copy(candidates = listOf(candidate)) }) },
        )
        assertTrue(withCandidates.withoutPaths().legs.flatMap { it.steps }.all { it.candidates == null })
    }

    // ── 고른 열차로 출발 알림(열차 고르기 설계 §5.4) ──
    private val chosen = ChosenTrain(trainNo = "5301", departsAt = 900_000L)

    @Test fun chosenPlanUsesRealtimeWhenNumberMatches() {
        val trains = listOf(TrainDto("마천", 300, trainNo = "5299"), TrainDto("마천", 840, trainNo = "5301"))
        val plan = chosenPlan(trains, receivedAt = 100_000, chosen = chosen, travelSec = 0, marginSec = 60)
        assertEquals(940_000L, plan.trainArrivalAt)
        assertEquals(880_000L, plan.alertAt)
        assertEquals("5301", plan.trainNo)
        assertEquals("마천", plan.destination)
    }

    @Test fun chosenPlanMatchesTrainNumberIgnoringLeadingZeros() {
        val plan = chosenPlan(listOf(TrainDto("마천", 840, trainNo = "05301")), 100_000, chosen, 0, 0)
        assertEquals(940_000L, plan.trainArrivalAt)
    }

    @Test fun chosenWithoutRealtimeUsesScheduledTime() {
        val plan = chosenPlan(listOf(TrainDto("마천", 300, trainNo = "5299")), 100_000, chosen, 120, 60)
        assertEquals(900_000L, plan.trainArrivalAt)
        assertEquals(720_000L, plan.alertAt)
    }
}
