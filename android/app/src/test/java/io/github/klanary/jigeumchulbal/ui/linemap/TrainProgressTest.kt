package io.github.klanary.jigeumchulbal.ui.linemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 역 시트 선로 그림에서 열차가 이전역→이번 역 사이 어디쯤인지(수동 검토 2026-11-22). */
class TrainProgressTest {
    @Test fun unknownTimeHasNoTrain() = assertNull(trainProgress(null, null, "서울역", "시청"))

    @Test fun atThisStationIsAtTheEnd() = assertEquals(1f, trainProgress(120, "시청", "서울역", "시청")!!, 0f)

    @Test fun fartherThanPrevSitsAtPrevEnd() = assertEquals(0f, trainProgress(600, "금정", "서울역", "시청")!!, 0f)

    @Test fun closerMeansFurtherAlong() {
        val far = trainProgress(120, "서울", "서울역", "시청")!!
        val near = trainProgress(30, null, "서울역", "시청")!!
        assert(far < near) { "$far < $near" }
        assertEquals(1f, trainProgress(0, null, "서울역", "시청")!!, 0f)
    }

    @Test fun farLabelOnlyWhenBeyondPrev() {
        assertEquals("금정", trainFarAway("금정", "서울역", "시청"))
        assertNull(trainFarAway("서울", "서울역", "시청"))
        assertNull(trainFarAway("시청", "서울역", "시청"))
        assertNull(trainFarAway(null, "서울역", "시청"))
    }
}
