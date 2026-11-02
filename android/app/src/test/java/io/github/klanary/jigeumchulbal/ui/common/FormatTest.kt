package io.github.klanary.jigeumchulbal.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun duration() {
        assertEquals("33분", formatDuration(1980))
        assertEquals("1시간 5분", formatDuration(3900))
        assertEquals("1시간", formatDuration(3600))
        assertEquals("1분", formatDuration(59))
    }

    @Test fun remaining() {
        assertEquals("정보 없음", formatRemaining(null))
        assertEquals("곧 도착", formatRemaining(45))
        assertEquals("3분", formatRemaining(180))
        assertEquals("1분", formatRemaining(119))
    }

    @Test fun fareAndDistanceAndAgo() {
        assertEquals("1,550원", formatFare(1550))
        assertEquals("요금 정보 없음", formatFare(null))
        assertEquals("320m", formatDistance(320))
        assertEquals("1.2km", formatDistance(1234))
        assertEquals("5초 전 갱신", formatUpdatedAgo(5))
        assertEquals("1분 전 갱신", formatUpdatedAgo(75))
    }
}
