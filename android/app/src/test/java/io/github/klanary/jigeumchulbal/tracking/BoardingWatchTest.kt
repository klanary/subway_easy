package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.data.remote.ApiError
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.LineTrainsDto
import io.github.klanary.jigeumchulbal.session.BOARD
import io.github.klanary.jigeumchulbal.session.DetectedTrain
import io.github.klanary.jigeumchulbal.session.DetectionSpot
import io.github.klanary.jigeumchulbal.session.FAR
import io.github.klanary.jigeumchulbal.session.GWANGHWAMUN
import io.github.klanary.jigeumchulbal.session.SEODAEMUN
import io.github.klanary.jigeumchulbal.session.fix
import io.github.klanary.jigeumchulbal.session.nearTrain
import io.github.klanary.jigeumchulbal.session.sampleTarget
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위치 흐름에서 탑승을 기다리고, 근처 열차에서 탄 열차를 고르기(위치 결합 스펙 7.1). */
class BoardingWatchTest {
    @Test fun detectsFromFixes() = runTest { assertTrue(awaitBoarding(sampleTarget, flowOf(fix(BOARD, 0), fix(FAR, 40)))) }

    @Test fun endedFlowIsNotBoarding() = runTest { assertFalse(awaitBoarding(sampleTarget, flowOf(fix(BOARD, 0)))) }

    @Test fun detectBoardingReportsTrainOnce() = runTest {
        val reported = mutableListOf<DetectedTrain?>()
        detectBoarding(
            spot = DetectionSpot("5호선", "광화문", "서대문"),
            search = { q -> ApiResult.Ok(listOfNotNull(mapOf("광화문" to GWANGHWAMUN, "서대문" to SEODAEMUN)[q])) },
            fixes = { flowOf(fix(BOARD, 0), fix(FAR, 40)) },
            near = { ApiResult.Ok(LineTrainsDto(it.line, "t", trains = listOf(nearTrain("하남검단산", "서대문")))) },
            onBoarded = { reported += it },
        )
        assertEquals(listOf<DetectedTrain?>(DetectedTrain("5123", "하남검단산")), reported)
    }

    @Test fun pickedTrainBecomesDetected() = runTest {
        assertEquals(
            DetectedTrain("5123", "하남검단산"),
            boardedTrain { ApiResult.Ok(LineTrainsDto("5호선", "t", trains = listOf(nearTrain("하남검단산", "서대문")))) },
        )
        assertNull(boardedTrain { ApiResult.Err(ApiError.Network) })
    }
}
