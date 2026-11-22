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

    /** 경유는 언제든 고를 수 있다(출발로·도착으로 사이 버튼, 수동 검토 2026-11-22). */
    @Test fun viaAnytime() {
        assertTrue(TripDraft().canSet(TripRole.VIA, hongdae))
        assertTrue(TripDraft(from = mapo).canSet(TripRole.VIA, hongdae))
        assertEquals("홍대입구 경유 · 출발역을 눌러 주세요", TripDraft(via = hongdae).banner())
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
        assertEquals("마포에서 출발 · 도착역을 눌러 주세요", TripDraft(from = mapo).banner())
        assertEquals("공덕까지 · 출발역을 눌러 주세요", TripDraft(to = gongdeok).banner())
        assertEquals("마포 → 공덕", TripDraft(from = mapo, to = gongdeok).banner())
        assertEquals("마포 → 홍대입구 → 공덕", TripDraft(mapo, hongdae, gongdeok).banner())
        assertEquals(null, TripDraft().banner())
    }
}
