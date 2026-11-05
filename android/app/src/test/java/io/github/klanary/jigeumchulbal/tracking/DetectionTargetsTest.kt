package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.session.GWANGHWAMUN
import io.github.klanary.jigeumchulbal.session.SEODAEMUN
import io.github.klanary.jigeumchulbal.session.sampleTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 탑승 감지 대상의 좌표는 역 이름 검색으로만 얻는다(폰 위치는 보내지 않는다, 위치 결합 스펙 3.2). */
class DetectionTargetsTest {
    private val byQuery = mapOf("광화문" to listOf(GWANGHWAMUN), "서대문" to listOf(SEODAEMUN))

    @Test fun resolvesBoardAndNextStation() = runTest {
        assertEquals(sampleTarget, detectionTarget("5호선", "광화문", "서대문") { ApiResult.Ok(byQuery[it].orEmpty()) })
    }

    @Test fun nextStationMustBeOnSameLine() = runTest {
        assertNull(
            detectionTarget("5호선", "광화문", "서대문") { q ->
                ApiResult.Ok(if (q == "광화문") listOf(GWANGHWAMUN) else listOf(SEODAEMUN.copy(lines = listOf("2호선"))))
            },
        )
    }

    @Test fun searchErrorGivesNull() = runTest {
        assertNull(detectionTarget("5호선", "광화문", "서대문") { ApiResult.Err(ApiError.Network) })
    }
}
