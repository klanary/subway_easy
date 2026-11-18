package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import java.time.Instant
import java.time.OffsetDateTime

// 구간별 열차 고르기(열차 고르기 설계 2026-11-18 §5.2). 서버가 보낸 후보로 앱이 바로 계산한다.
// 바꾼 구간만 고정하고, 그 뒤 구간은 앞 열차가 내린 뒤(같은 초 포함) 탈 수 있는 첫 열차로 맞춘다.

enum class ExpressFilter { ALL, EXPRESS, LOCAL }

/** 구간 하나의 사용자 선택. [pinned]가 null이면 자동 맞춤. */
data class SegmentChoice(val pinned: CandidateDto? = null, val filter: ExpressFilter = ExpressFilter.ALL)

/**
 * 계산한 구간: [train]이 null이면 맞는 열차가 후보 안에 없다([needsMore]).
 * [needsMore]는 "더 늦은 열차 불러오기"가 필요할 때(후보 끝이거나 맞는 열차 없음).
 */
data class PickedSegment(
    val step: StepDto,
    val train: CandidateDto?,
    val pinned: Boolean,
    val warning: String?,
    val canPrev: Boolean,
    val canNext: Boolean,
    val needsMore: Boolean,
    val hasExpress: Boolean,
    val hasLocal: Boolean,
)

/** 모든 다리의 지하철 단계(순서대로). 구간 번호는 이 목록의 위치다. */
fun subwaySteps(route: RouteDto): List<StepDto> = route.legs.flatMap { it.steps }.filter { it.mode == StepMode.SUBWAY }

fun pickTrains(route: RouteDto, choices: List<SegmentChoice>): List<PickedSegment> {
    val out = mutableListOf<PickedSegment>()
    var previous: CandidateDto? = null
    subwaySteps(route).forEachIndexed { k, step ->
        val choice = choices.getOrNull(k) ?: SegmentChoice()
        val all = step.candidateList()
        val filtered = all.filter { choice.filter.accepts(it) }
        // 첫 구간은 경로가 정한 출발 시각, 그 뒤는 앞 구간 도착이 기준이다.
        val after: Instant? = if (k == 0) step.departsAt?.let(::instant) else previous?.let { (it.arrivesAt ?: it.departsAt).let(::instant) }
        val pinned = choice.pinned
        val train = pinned ?: after?.let { base -> filtered.firstOrNull { !instant(it.departsAt).isBefore(base) } }
        val warning = if (pinned != null && k > 0 && after != null && instant(pinned.departsAt).isBefore(after)) {
            "앞 열차가 ${hhmm(previous?.arrivesAt ?: previous?.departsAt)}에 도착해요. 이 열차는 ${hhmm(pinned.departsAt)}에 떠나요"
        } else {
            null
        }
        val at = train?.let { current -> filtered.indexOfFirst { it.same(current) } } ?: -1
        val canNext = at >= 0 && at < filtered.lastIndex
        out += PickedSegment(
            step = step,
            train = train,
            pinned = pinned != null,
            warning = warning,
            canPrev = at > 0,
            canNext = canNext,
            needsMore = train == null && after != null || at >= 0 && !canNext,
            hasExpress = all.any { it.isExpress },
            hasLocal = all.any { !it.isExpress },
        )
        previous = train
    }
    return out
}

/** [segment] 구간을 지금 열차에서 필터 맞는 후보 기준 [delta]만큼 옮기고 고정한다. 끝이면 그대로. */
fun move(route: RouteDto, choices: List<SegmentChoice>, segment: Int, delta: Int): List<SegmentChoice> {
    val picked = pickTrains(route, choices).getOrNull(segment) ?: return choices
    val current = picked.train ?: return choices
    val choice = choices.getOrNull(segment) ?: SegmentChoice()
    val filtered = picked.step.candidateList().filter { choice.filter.accepts(it) }
    val at = filtered.indexOfFirst { it.same(current) }
    val target = filtered.getOrNull(at + delta)?.takeIf { at >= 0 } ?: return choices
    return choices.with(route, segment) { it.copy(pinned = target) }
}

/** 급행/일반 필터. 고정한 열차가 새 필터에 맞지 않으면 고정을 푼다. */
fun setFilter(choices: List<SegmentChoice>, segment: Int, filter: ExpressFilter): List<SegmentChoice> =
    choices.with(null, segment) { c -> c.copy(filter = filter, pinned = c.pinned?.takeIf { filter.accepts(it) }) }

fun unpin(choices: List<SegmentChoice>, segment: Int): List<SegmentChoice> =
    choices.with(null, segment) { it.copy(pinned = null) }

/**
 * 고른 열차로 바꾼 경로 사본: 지하철 단계의 시각·번호·급행·행선지·출처, 경로 출발·도착과 총 소요.
 * 알림 세션과 루틴은 이 사본을 받는다. 열차가 없는 구간은 원래 값을 둔다.
 */
fun RouteDto.withPicks(picks: List<PickedSegment>): RouteDto {
    var k = 0
    val legs = legs.map { leg ->
        leg.copy(
            steps = leg.steps.map { step ->
                if (step.mode != StepMode.SUBWAY) return@map step
                val train = picks.getOrNull(k++)?.train ?: return@map step
                step.copy(
                    departsAt = train.departsAt,
                    arrivesAt = train.arrivesAt ?: step.arrivesAt,
                    trainNo = train.trainNo,
                    isExpress = train.isExpress,
                    destination = train.destination,
                    source = train.source ?: step.source,
                    estimated = train.estimated,
                )
            },
        )
    }
    val trains = picks.map { it.train }
    if (trains.isEmpty() || trains.any { it == null }) return copy(legs = legs)
    val departs = trains.first()!!.departsAt
    val arrives = trains.last()!!.let { it.arrivesAt ?: it.departsAt }
    return copy(
        legs = legs,
        departAt = departs,
        arriveAt = arrives,
        totalSec = ((instant(arrives).toEpochMilli() - instant(departs).toEpochMilli()) / 1000).toInt(),
    )
}

/** 후보가 없으면(옛 서버) 경로가 정한 열차 하나를 후보로 본다. */
private fun StepDto.candidateList(): List<CandidateDto> = candidates?.takeIf { it.isNotEmpty() }
    ?: departsAt?.let {
        listOf(CandidateDto(trainNo, it, arrivesAt, isExpress, destination, source, estimated))
    }.orEmpty()

private fun ExpressFilter.accepts(c: CandidateDto) = when (this) {
    ExpressFilter.ALL -> true
    ExpressFilter.EXPRESS -> c.isExpress
    ExpressFilter.LOCAL -> !c.isExpress
}

private fun CandidateDto.same(other: CandidateDto) = trainNo == other.trainNo && departsAt == other.departsAt

private fun List<SegmentChoice>.with(route: RouteDto?, segment: Int, change: (SegmentChoice) -> SegmentChoice): List<SegmentChoice> {
    val size = maxOf(this.size, segment + 1, route?.let { subwaySteps(it).size } ?: 0)
    return List(size) { i -> (getOrNull(i) ?: SegmentChoice()).let { if (i == segment) change(it) else it } }
}

private fun instant(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()

private fun hhmm(iso: String?): String = iso?.substring(11, 16) ?: ""
