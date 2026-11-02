package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode

/** 승차 전 알림(⑦)을 시작할 값: 기다릴 역·호선·방면, 알림 방식, 역까지 걸리는 시간. */
data class WaitStart(val target: WaitTarget, val mode: WaitMode, val travelSec: Int)

/**
 * 경로의 첫 지하철 탑승 정보로 대기 대상을 만든다. 첫 탑승 정보나 역 ID가 없으면 실시간 정보를 쓸 수 없어 null.
 * 첫 탑승 전이 모두 도보일 때만 출발 시각을 계산할 수 있고, 버스가 끼면 "도착 N분 전" 방식으로 대신한다(스펙 4.3).
 */
fun waitStartFromRoute(route: RouteDto): WaitStart? {
    val boarding = route.firstBoarding ?: return null
    val stationId = boarding.stationId ?: return null
    val stationName = route.legs.asSequence().flatMap { it.steps }
        .firstOrNull { it.mode == StepMode.SUBWAY }?.stops?.firstOrNull() ?: stationId
    val mode = if (boarding.allWalkBeforeBoarding) WaitMode.DEPARTURE_TIMING else WaitMode.MINUTES_BEFORE
    return WaitStart(WaitTarget(stationId, stationName, boarding.lineName, boarding.towards, null), mode, boarding.secToBoarding)
}

/** 역 상세의 호선·방향 묶음에서 시작한다. 이미 역에 있으므로 "도착 N분 전" 방식이다. */
fun waitStartFromGroup(stationId: String, group: ArrivalGroupDto): WaitStart =
    WaitStart(WaitTarget(stationId, stationId, group.line, group.towards, group.direction), WaitMode.MINUTES_BEFORE, 0)
