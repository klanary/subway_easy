package io.github.klanary.jigeumchulbal.ui.linemap

import io.github.klanary.jigeumchulbal.data.model.StationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDraftTest {
    private val mapo = StationRef("마포", "마포")
    private val gongdeok = StationRef("공덕", "공덕")
    private val hongdae = StationRef("홍대입구", "홍대입구")

    @Test fun sameStationCannotBeFromAndTo() = assertFalse(TripDraft(from = mapo).canSet(TripRole.TO, mapo))

    @Test fun viaMustDifferFromEnds() = assertFalse(TripDraft(mapo, to = gongdeok).canSet(TripRole.VIA, gongdeok))

    @Test fun viaOnlyAfterBothEnds() {
        assertFalse(TripDraft(from = mapo).canSet(TripRole.VIA, hongdae))
        assertTrue(TripDraft(from = mapo, to = gongdeok).canSet(TripRole.VIA, hongdae))
    }

    @Test fun readyNeedsDistinctEnds() {
        assertFalse(TripDraft(from = mapo).ready)
        assertTrue(TripDraft(from = mapo).set(TripRole.TO, gongdeok).ready)
    }

    @Test fun settingReplacesOnlyThatRole() {
        val draft = TripDraft(mapo, hongdae, gongdeok).set(TripRole.FROM, StationRef("애오개", "애오개"))
        assertEquals(TripDraft(StationRef("애오개", "애오개"), hongdae, gongdeok), draft)
    }

    @Test fun bannerText() {
        assertEquals("출발: 마포 · 도착: (역을 고르세요)", TripDraft(from = mapo).banner())
        assertEquals("출발: 마포 · 경유: 홍대입구 · 도착: 공덕", TripDraft(mapo, hongdae, gongdeok).banner())
        assertEquals(null, TripDraft().banner())
    }
}
