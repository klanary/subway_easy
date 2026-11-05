package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.TrainNearDto
import io.github.klanary.jigeumchulbal.data.remote.TrainState
import io.github.klanary.jigeumchulbal.session.BOARD
import io.github.klanary.jigeumchulbal.session.FAR
import io.github.klanary.jigeumchulbal.session.fix
import io.github.klanary.jigeumchulbal.session.sampleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위치로 탑승 알아보기(⑭ 자동 감지, 위치 결합 스펙 7.1). */
class BoardingDetectorTest {
    @Test fun nearThenFastTowardNextIsBoarding() {
        val d = BoardingDetector(sampleTarget)
        assertFalse(d.onFix(fix(BOARD, 0)))
        assertTrue(d.onFix(fix(FAR, 40))) // 약 54km/h
    }

    @Test fun walkingSpeedIsNotBoarding() {
        val d = BoardingDetector(sampleTarget)
        d.onFix(fix(BOARD, 0))
        assertFalse(d.onFix(fix(FAR, 360))) // 약 6km/h
    }

    @Test fun inaccurateFixesIgnored() {
        val d = BoardingDetector(sampleTarget)
        d.onFix(fix(BOARD, 0, acc = 1_500f))
        assertFalse(d.onFix(fix(FAR, 40)))
    }

    @Test fun movingAwayFromNextStationIsNotBoarding() {
        val d = BoardingDetector(sampleTarget)
        d.onFix(fix(BOARD, 0))
        assertFalse(d.onFix(fix(GeoPoint(37.57394, 126.98252), 40))) // 탈 역 601m, 다음 역 1,662m
    }

    @Test fun speedIsMeasuredFromLatestNearFix() {
        val d = BoardingDetector(sampleTarget)
        d.onFix(fix(BOARD, 0))
        d.onFix(fix(BOARD, 300))
        assertTrue(d.onFix(fix(FAR, 340)))
    }

    @Test fun withoutNearFixNothingIsDetected() = assertFalse(BoardingDetector(sampleTarget).onFix(fix(FAR, 40)))
}

/** 근처 열차 가운데 방금 탄 열차 고르기(위치 결합 스펙 7.1). */
class PickBoardedTrainTest {
    private fun t(no: String, stops: Int, state: TrainState, eta: Int? = null) =
        TrainNearDto(no, "역", "역", state, etaSec = eta, stopsFromStation = stops)

    @Test fun nearestPassedTrainWins() = assertEquals(
        "B",
        pickBoardedTrain(
            listOf(t("A", 2, TrainState.ARRIVED), t("B", 1, TrainState.LEFT_PREVIOUS), t("C", -1, TrainState.DEPARTED, eta = 120)),
        )?.trainNo,
    )

    @Test fun departedAtStationCounts() =
        assertEquals("A", pickBoardedTrain(listOf(t("A", 0, TrainState.DEPARTED), t("B", 2, TrainState.ARRIVED)))?.trainNo)

    @Test fun noCandidate() {
        assertNull(
            pickBoardedTrain(
                listOf(t("A", 0, TrainState.ARRIVED, eta = 0), t("B", 3, TrainState.DEPARTED), t("C", -2, TrainState.DEPARTED, eta = 200)),
            ),
        )
        assertNull(pickBoardedTrain(emptyList()))
    }
}
