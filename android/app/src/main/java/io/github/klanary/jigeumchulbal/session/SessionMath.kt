package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.session.CommuteSession.Companion.MINUTE_MS

// 통학 세션의 계산 함수(스펙 8.2, 8.3). 안드로이드에 의존하지 않고 시각을 인자로 받아 단위 테스트한다.

private const val SECOND_MS = 1_000L

/**
 * 출발 타이밍(⑦): 역까지 걸어가는 시간([travelSec])과 여유([marginSec])를 더해도 탈 수 있는 첫 열차.
 * 그 열차에 맞추려면 "지금 출발해야 하는 시각"에 알린다. 탈 수 있는 열차가 없으면 null.
 */
fun departurePlan(trains: List<TrainDto>, receivedAt: Long, travelSec: Int, marginSec: Int): TrainPlan? {
    val train = trains.withRemaining().firstOrNull { (_, sec) -> sec >= travelSec + marginSec } ?: return null
    val (dto, sec) = train
    return TrainPlan(
        alertAt = receivedAt + (sec - travelSec - marginSec) * SECOND_MS,
        trainArrivalAt = receivedAt + sec * SECOND_MS,
        destination = dto.destination,
    )
}

/** 도착 N분 전(역 상세에서 시작): 남은 시간을 아는 첫 열차가 [leadSec] 전이 되는 시각. 이미 지났으면 지금. */
fun minutesBeforePlan(trains: List<TrainDto>, receivedAt: Long, leadSec: Int, now: Long): TrainPlan? {
    val (dto, sec) = trains.withRemaining().firstOrNull() ?: return null
    return TrainPlan(
        alertAt = maxOf(now, receivedAt + (sec - leadSec) * SECOND_MS),
        trainArrivalAt = receivedAt + sec * SECOND_MS,
        destination = dto.destination,
    )
}

private fun List<TrainDto>.withRemaining(): List<Pair<TrainDto, Int>> =
    mapNotNull { train -> train.remainingSec?.let { train to it } }.sortedBy { it.second }

/**
 * 하차·환승 지점(⑤). 탑승 시각부터 단계 소요 시간을 차례로 더해(사이의 도보 포함) 대중교통 단계마다 내릴 시각을 구한다.
 * 알림은 내리기 [leadSec] 전이지만, 소요 시간이 그보다 짧은 구간(한 정거장 등)은 탑승 즉시 알린다.
 */
fun alightPoints(route: RouteDto, boardedAt: Long, from: StartFrom, leadSec: Int): List<AlightPoint> {
    data class Indexed(val step: StepDto, val leg: Int)

    val steps = route.legs.flatMapIndexed { leg, l -> l.steps.map { Indexed(it, leg) } }
    val start = steps.indexOfFirst { it.step.isStartOf(from) }
    if (start < 0) return emptyList()

    val transit = steps.drop(start).filter { it.step.isTransit() }
    val lastTransit = transit.lastOrNull()
    // 경유지가 있으면(구간 2개) 첫 구간의 마지막 대중교통 하차가 경유지 하차다.
    val viaStep = if (route.legs.size == 2 && steps.any { it.leg == 1 && it.step.isTransit() }) {
        transit.lastOrNull { it.leg == 0 }
    } else {
        null
    }

    var elapsedMs = 0L
    val points = mutableListOf<AlightPoint>()
    steps.drop(start).forEach { indexed ->
        val step = indexed.step
        elapsedMs += step.durationSec * SECOND_MS
        if (!step.isTransit()) return@forEach
        val alightAt = boardedAt + elapsedMs
        val kind = when {
            indexed === lastTransit -> PointKind.FINAL
            indexed === viaStep -> PointKind.VIA
            else -> PointKind.TRANSFER
        }
        val next = transit.getOrNull(transit.indexOf(indexed) + 1)?.step
        points += AlightPoint(
            name = step.stops?.lastOrNull() ?: step.lineName ?: step.routeName.orEmpty(),
            isBus = step.mode == StepMode.BUS,
            kind = kind,
            alightAt = alightAt,
            alertAt = maxOf(boardedAt, alightAt - leadSec * SECOND_MS),
            stopsToGo = ((step.stops?.size ?: 1) - 1).coerceAtLeast(0),
            nextLine = if (kind == PointKind.TRANSFER) next?.let { it.lineName ?: it.routeName } else null,
        )
    }
    return points
}

private fun StepDto.isTransit() = mode == StepMode.SUBWAY || mode == StepMode.BUS

/** 하차 알림을 걸 대중교통 단계가 있는지. 도보만인 경로는 알릴 지점이 없다. */
fun RouteDto.hasTransit(): Boolean = legs.any { leg -> leg.steps.any { it.isTransit() } }

/** 진행 중 알림과 세션 화면이 보일 지점: 다음 지점, 다 지났으면 마지막 지점. */
fun SessionState.Riding.currentPoint(): AlightPoint? = points.getOrNull(nextIndex) ?: points.lastOrNull()

/** 남은 시간을 분으로 올린다(1초만 남아도 1분). */
fun ceilMinutes(ms: Long): Long = (ms + MINUTE_MS - 1) / MINUTE_MS

private fun StepDto.isStartOf(from: StartFrom) = when (from) {
    StartFrom.FIRST_SUBWAY -> mode == StepMode.SUBWAY
    StartFrom.FIRST_TRANSIT -> isTransit()
}

/** 알림 문구의 지점 이름: 버스는 "OO 정류장", 지하철은 "역"을 한 번만 붙인다("서울역역" 방지). */
fun stopLabel(point: AlightPoint): String = when {
    point.isBus -> "${point.name} 정류장"
    point.name.endsWith("역") -> point.name
    else -> "${point.name}역"
}

/** 실시간 도착 그룹 중 기다리는 호선(과 방향)의 첫 그룹. 방면은 서버가 이미 걸러서 돌려준다. */
fun pickGroup(groups: List<ArrivalGroupDto>, target: WaitTarget): ArrivalGroupDto? =
    groups.firstOrNull { it.line == target.line && (target.direction == null || it.direction == target.direction) }

/** 경로 좌표를 뺀 사본. 세션 저장 크기를 줄인다(알림 계산에는 좌표가 필요 없다). */
fun RouteDto.withoutPaths(): RouteDto =
    copy(legs = legs.map { leg -> leg.copy(steps = leg.steps.map { it.copy(path = emptyList()) }) })
