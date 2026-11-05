package io.github.klanary.jigeumchulbal.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 위치 결합 스펙 6.3의 새 칸과 새 API 응답(7.4). 새 칸이 없는 예전 서버 응답도 읽혀야 한다. */
class TrainDtoDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun arrivalTrainCarriesTrainNoAndBasis() {
        val t = json.decodeFromString<TrainDto>("""{"destination":"하남검단산","remainingSec":80,"trainNo":"5123","basis":"position"}""")
        assertEquals("5123" to "position", t.trainNo to t.basis)
    }

    @Test fun olderServerTrainHasNoTrainNo() = assertNull(json.decodeFromString<TrainDto>("""{"remainingSec":80}""").trainNo)

    @Test fun lineTrainsFromSpecExample() {
        val body = """{"line":"5호선","updatedAt":"2026-11-05T08:01:30+09:00","stale":false,"trains":[{"trainNo":"5123",
            "stationId":"공덕","stationName":"공덕","state":"DEPARTED","direction":"하행","destination":"하남검단산",
            "isExpress":false,"isLast":false,"etaSec":null,"stopsFromStation":1}]}"""
        assertEquals(
            TrainNearDto("5123", "공덕", "공덕", TrainState.DEPARTED, "하행", "하남검단산", stopsFromStation = 1),
            json.decodeFromString<LineTrainsDto>(body).trains.single(),
        )
    }

    @Test fun trainNotFound() {
        val dto = json.decodeFromString<TrainEtaDto>(
            """{"trainNo":"5123","line":"5호선","found":false,"updatedAt":"2026-11-05T08:01:30+09:00","stale":false}""",
        )
        assertFalse(dto.found)
        assertNull(dto.etaSec)
    }
}
