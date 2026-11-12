package io.github.klanary.jigeumchulbal.ui.linemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineMapGeometryTest {
    @Test fun zoomIsClamped() {
        assertEquals(4f, MapTransform(3f, 0f, 0f).zoomAround(100f, 100f, 2f).scale)
        assertEquals(0.5f, MapTransform(0.6f, 0f, 0f).zoomAround(100f, 100f, 0.5f).scale)
    }

    @Test fun zoomKeepsFocusPointStill() {
        val before = MapTransform(1f, -200f, -300f)
        val after = before.zoomAround(150f, 250f, 2f)
        val (bx, by) = before.toMap(150f, 250f)
        val (ax, ay) = after.toMap(150f, 250f)
        assertEquals(bx, ax, 0.001f)
        assertEquals(by, ay, 0.001f)
    }

    @Test fun toMapUndoesScaleAndOffset() {
        assertEquals(300f to 400f, MapTransform(2f, 100f, 200f).toMap(700f, 1000f))
    }

    @Test fun panMovesOffset() {
        assertEquals(MapTransform(1f, 15f, -5f), MapTransform(1f, 10f, 0f).pan(5f, -5f))
    }

    @Test fun centerOnPutsPointInMiddle() {
        val t = MapTransform(2f, 0f, 0f).centerOn(100f, 50f, viewW = 400f, viewH = 800f)
        assertEquals(100f to 50f, t.toMap(200f, 400f))
    }

    @Test fun hitPicksNearestWithinRadius() {
        val stations = listOf(MapStation("공덕", 10f, 10f), MapStation("마포", 40f, 10f))
        assertEquals("공덕", hitStation(stations, 18f, 12f, 12f)?.id)
    }

    @Test fun hitMissesOutsideRadius() {
        assertNull(hitStation(listOf(MapStation("공덕", 10f, 10f)), 60f, 60f, 12f))
    }

    @Test fun fitScaleShowsWholeMap() {
        assertEquals(0.1f, fitScale(mapW = 4000f, mapH = 6000f, viewW = 400f, viewH = 800f), 0.0001f)
    }
}
