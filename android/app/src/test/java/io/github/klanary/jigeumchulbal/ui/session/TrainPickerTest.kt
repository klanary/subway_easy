package io.github.klanary.jigeumchulbal.ui.session

import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.Tracking
import io.github.klanary.jigeumchulbal.session.nearTrain
import io.github.klanary.jigeumchulbal.session.trackedPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 직접 고르기 목록 문구와 세션 화면의 추적 상태 줄(위치 결합 스펙 7.1, 7.2). */
class TrainPickerTest {
    @Test fun choiceLabel() {
        assertEquals("하남검단산행 · 지금 충정로역", trainChoiceLabel(nearTrain("하남검단산", "충정로")))
        assertEquals("열차 · 지금 서울역", trainChoiceLabel(nearTrain(null, "서울역")))
    }

    @Test fun trackingStatusTexts() {
        val r = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕"))
        val known = r.tracking!!.copy(trainNo = "5123", destination = "하남검단산")
        assertEquals("탄 열차를 고르면 도착 시각을 맞춰 드려요", trackingStatus(r))
        assertEquals("하남검단산행 열차 실시간 추적 중", trackingStatus(r.copy(tracking = known)))
        assertEquals("실시간 추적 끊김", trackingStatus(r.copy(tracking = known.copy(lost = true))))
        assertNull(trackingStatus(r.copy(tracking = null)))
    }
}
