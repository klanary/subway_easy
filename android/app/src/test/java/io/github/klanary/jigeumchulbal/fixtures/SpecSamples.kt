package io.github.klanary.jigeumchulbal.fixtures

import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import kotlinx.serialization.json.Json

// 스펙 6.2의 응답 예시를 그대로 옮긴 문자열. 값은 형식 설명용이며 실제 데이터가 아니다.

const val SPEC_ARRIVALS_JSON = """
{
  "stationId": "홍대입구",
  "updatedAt": "2026-11-02T08:01:30+09:00",
  "stale": false,
  "groups": [
    {
      "line": "2호선",
      "direction": "내선",
      "towards": "합정",
      "trains": [
        {"destination": "성수", "remainingSec": 180, "message": "3분 후", "isLast": false, "isExpress": false},
        {"destination": "성수", "remainingSec": 420, "message": "7분 후", "isLast": false, "isExpress": false}
      ]
    }
  ]
}
"""

const val SPEC_ROUTES_JSON = """
{
  "updatedAt": "2026-11-02T08:00:00+09:00",
  "stale": false,
  "routes": [
    {
      "id": "r_3f9a",
      "type": "SUBWAY",
      "totalSec": 1980,
      "distanceM": 11800,
      "transfers": 1,
      "fare": 1550,
      "legs": [
        {
          "steps": [
            {"mode": "WALK", "durationSec": 420, "distanceM": 480, "guidance": "홍대입구역까지 도보", "path": [[37.5550, 126.9200], [37.5571, 126.9245]]},
            {"mode": "SUBWAY", "durationSec": 600, "distanceM": 5200, "lineName": "2호선",
             "stops": ["홍대입구", "합정", "당산"], "boardStationId": "홍대입구", "alightStationId": "당산",
             "guidance": "2호선 홍대입구 승차, 당산 하차", "path": [[37.5571, 126.9245], [37.5349, 126.9025]]}
          ]
        }
      ],
      "firstBoarding": {
        "stationId": "홍대입구",
        "lineName": "2호선",
        "towards": "합정",
        "secToBoarding": 420,
        "allWalkBeforeBoarding": true,
        "nextTrains": [
          {"destination": "성수", "remainingSec": 180, "message": "3분 후", "isLast": false, "isExpress": false}
        ]
      }
    }
  ]
}
"""

private val specJson = Json { ignoreUnknownKeys = true }

/** 스펙 6.2 예시의 경로 하나. */
fun specRoute(): RouteDto = specJson.decodeFromString<RoutesDto>(SPEC_ROUTES_JSON).routes.single()
