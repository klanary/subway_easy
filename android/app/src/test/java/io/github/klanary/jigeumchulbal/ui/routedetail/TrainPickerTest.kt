package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 구간별 열차 고르기(열차 고르기 설계 2026-11-18 §5.2). 5호선 마포→여의도, 9호선 여의도→노량진. */
class TrainPickerTest {
    private fun t(hhmm: String) = "2026-11-18T$hhmm:00+09:00"

    private fun c(no: String, dep: String, arr: String, express: Boolean = false) =
        CandidateDto(trainNo = no, departsAt = t(dep), arrivesAt = t(arr), isExpress = express)

    private val five = listOf(c("5301", "08:05", "08:09"), c("5303", "08:15", "08:20"), c("5305", "08:25", "08:29"))
    private val nine = listOf(c("9101", "08:10", "08:14"), c("9501", "08:20", "08:24", express = true), c("9103", "08:30", "08:34"))

    private fun step(line: String, train: CandidateDto, candidates: List<CandidateDto>?) = StepDto(
        mode = StepMode.SUBWAY, durationSec = 240, distanceM = 0, lineName = line,
        departsAt = train.departsAt, arrivesAt = train.arrivesAt, trainNo = train.trainNo, candidates = candidates,
    )

    private val route = RouteDto(
        id = "r", type = "SUBWAY", totalSec = 540, distanceM = 0, transfers = 1,
        legs = listOf(LegDto(steps = listOf(step("5호선", five[0], five), step("9호선", nine[0], nine)))),
        departAt = t("08:05"), arriveAt = t("08:14"),
    )

    private fun numbers(picks: List<PickedSegment>) = picks.map { it.train?.trainNo }

    @Test fun unpinnedRouteKeepsServerChoice() {
        val picks = pickTrains(route, emptyList())
        assertEquals(listOf("5301", "9101"), numbers(picks))
        assertTrue(picks.none { it.pinned || it.warning != null })
    }

    @Test fun nextOnFirstRefitsSecond() {
        val choices = move(route, emptyList(), segment = 0, delta = 1)
        val picks = pickTrains(route, choices)
        assertEquals(listOf("5303", "9501"), numbers(picks))
        assertTrue(picks[0].pinned)
        assertFalse(picks[1].pinned)
    }

    @Test fun sameSecondConnectionIsAllowed() {
        // 5303이 08:20에 닿고 9501이 08:20에 떠난다(걷는 시간 0).
        val picks = pickTrains(route, move(route, emptyList(), 0, 1))
        assertEquals("9501", picks[1].train?.trainNo)
    }

    @Test fun pinnedConflictShowsWarning() {
        // 원래 열차로 돌아오면 고정이 풀리므로(2026-11-22) 더 늦은 열차를 고정해 겹치게 만든다.
        var choices = move(route, emptyList(), 1, 1) // 9501(08:20 출발) 고정
        choices = move(route, choices, 0, 1) // 5303(08:20 도착) 고정
        choices = move(route, choices, 0, 1) // 5305(08:29 도착) 고정
        val picks = pickTrains(route, choices)
        assertEquals(listOf("5305", "9501"), numbers(picks))
        assertEquals("앞 열차가 08:29에 도착해요. 이 열차는 08:20에 떠나요", picks[1].warning)
    }

    @Test fun expressFilterSkipsLocals() {
        assertEquals("9501", pickTrains(route, setFilter(emptyList(), 1, ExpressFilter.EXPRESS))[1].train?.trainNo)
        val picks = pickTrains(route, setFilter(emptyList(), 1, ExpressFilter.LOCAL))
        assertEquals("9101", picks[1].train?.trainNo)
        assertTrue(picks[1].hasExpress && picks[1].hasLocal)
        assertFalse(picks[0].hasExpress)
    }

    @Test fun lastCandidateDisablesNextAndNeedsMore() {
        var choices = move(route, emptyList(), 0, 1)
        choices = move(route, choices, 0, 1) // 5305, 08:29 도착
        choices = setFilter(choices, 1, ExpressFilter.EXPRESS)
        val picks = pickTrains(route, choices)
        assertEquals("5305", picks[0].train?.trainNo)
        assertFalse(picks[0].canNext)
        assertTrue(picks[0].needsMore)
        assertNull(picks[1].train) // 08:29 뒤 급행이 후보 안에 없다
        assertTrue(picks[1].needsMore)
        assertEquals(choices, move(route, choices, 0, 1)) // 끝에서 [다음]은 그대로
    }

    @Test fun firstCandidateDisablesPrev() {
        val first = pickTrains(route, emptyList())[0]
        assertFalse(first.canPrev)
        assertTrue(first.canNext)
        assertFalse(first.needsMore)
    }

    @Test fun unpinReturnsToAutoFit() {
        val choices = unpin(move(route, emptyList(), 0, 1), 0)
        val picks = pickTrains(route, choices)
        assertEquals(listOf("5301", "9101"), numbers(picks))
        assertFalse(picks[0].pinned)
    }

    @Test fun withPicksUpdatesTimesAndTotal() {
        val picked = route.withPicks(pickTrains(route, move(route, emptyList(), 0, 1)))
        val steps = picked.legs.flatMap { it.steps }
        assertEquals(listOf("5303", "9501"), steps.map { it.trainNo })
        assertEquals(t("08:24"), steps[1].arrivesAt)
        assertTrue(steps[1].isExpress)
        assertEquals(t("08:15"), picked.departAt)
        assertEquals(t("08:24"), picked.arriveAt)
        assertEquals(9 * 60, picked.totalSec)
    }

    @Test fun emptyCandidatesFallBackToStepTrain() {
        val old = route.copy(legs = listOf(LegDto(steps = listOf(step("5호선", five[0], null), step("9호선", nine[0], emptyList())))))
        val picks = pickTrains(old, emptyList())
        assertEquals(listOf("5301", "9101"), numbers(picks))
        assertFalse(picks[0].canPrev || picks[0].canNext)
    }

    /** 앞뒤로 옮기다 원래(자동) 열차로 돌아오면 고정이 풀린다(수동 검토 2026-11-22). */
    @Test fun movingBackToAutoTrainUnpins() {
        val later = move(route, emptyList(), 0, 1)
        assertTrue(pickTrains(route, later)[0].pinned)
        val back = move(route, later, 0, -1)
        assertTrue(pickTrains(route, back).none { it.pinned })
    }
}
