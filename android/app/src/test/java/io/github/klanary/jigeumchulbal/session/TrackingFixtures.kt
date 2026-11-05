package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.remote.TrainNearDto
import io.github.klanary.jigeumchulbal.data.remote.TrainState
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.location.LocationFix
import io.github.klanary.jigeumchulbal.tracking.DetectionTarget

// 위치 결합·열차 추적 테스트(P6 Task 9~14)가 함께 쓰는 값. 사용자 통학처럼 5호선에서 6호선으로 갈아타는 경로다.

/** 5호선 광화문→공덕(600초), 도보 120초, 6호선 공덕→삼각지(300초). */
fun line5to6Route(): RouteDto = specRoute().copy(
    legs = listOf(
        LegDto(
            listOf(
                StepDto(
                    StepMode.SUBWAY, 600, 0, lineName = "5호선",
                    stops = listOf("광화문", "서대문", "충정로", "애오개", "공덕"),
                    boardStationId = "광화문", alightStationId = "공덕",
                ),
                StepDto(StepMode.WALK, 120, 0),
                StepDto(
                    StepMode.SUBWAY, 300, 0, lineName = "6호선",
                    stops = listOf("공덕", "효창공원앞", "삼각지"),
                    boardStationId = "공덕", alightStationId = "삼각지",
                ),
            ),
        ),
    ),
)

/** [line5to6Route]를 0초에 탔을 때의 하차 지점(하차·환승 알림 120초 전). */
fun trackedPoints(): List<AlightPoint> = listOf(
    AlightPoint(
        "공덕", false, PointKind.TRANSFER, alightAt = 600_000, alertAt = 480_000, stopsToGo = 4, nextLine = "6호선",
        line = "5호선", boardStationId = "광화문", alightStationId = "공덕", nextStop = "서대문",
    ),
    AlightPoint(
        "삼각지", false, PointKind.FINAL, alightAt = 1_020_000, alertAt = 900_000, stopsToGo = 2,
        line = "6호선", boardStationId = "공덕", alightStationId = "삼각지", nextStop = "효창공원앞",
    ),
)

/** 근처 열차 목록의 한 열차: 탈 역을 막 지나 다음 역으로 가는 중(전역출발, 1정거장 지남). */
fun nearTrain(dest: String?, station: String, no: String = "5123") =
    TrainNearDto(no, station, station, TrainState.LEFT_PREVIOUS, destination = dest, stopsFromStation = 1)

// 탑승 감지 테스트용 좌표(광화문·서대문 근처, 시험용 기하). 거리는 haversine으로 미리 잰 값이다.
val BOARD = GeoPoint(37.5710, 126.9768)

/** 탈 역에서 약 1,061m. */
val NEXT = GeoPoint(37.5658, 126.9667)

/** 탈 역에서 601m, 다음 역에서 461m(다음 역 쪽으로 간 위치). */
val FAR = GeoPoint(37.56806, 126.97108)

val sampleTarget = DetectionTarget("5호선", "광화문", "서대문", BOARD, NEXT)

fun fix(p: GeoPoint, atSec: Long, acc: Float = 20f) = LocationFix(p, acc, atSec * 1_000)

/** 역 검색 결과(탑승 감지 대상의 좌표를 얻는 데 쓴다). [BOARD]·[NEXT]와 같은 좌표다. */
val GWANGHWAMUN = StationDto("광화문", "광화문", listOf("5호선"), 37.5710, 126.9768)
val SEODAEMUN = StationDto("서대문", "서대문", listOf("5호선"), 37.5658, 126.9667)
