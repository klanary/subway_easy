package io.github.klanary.jigeumchulbal.ui.linemap

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineMapDataTest {
    @Test fun parsesStationTable() {
        val data = parseLineMapStations("""{"width":100,"height":200,"stations":[{"id":"공덕","x":1.5,"y":2}]}""")
        assertEquals(100f to 200f, data.width to data.height)
        assertEquals(listOf(MapStation("공덕", 1.5f, 2f)), data.stations)
    }

    /** 앱에 넣은 역 위치표(server/scripts/build_linemap.py가 만든 것)가 노선도 안의 600개 넘는 역을 갖는다. */
    @Test fun bundledMapCoversStations() {
        val data = parseLineMapStations(File("src/main/assets/linemap/stations.json").readText())
        assertTrue(data.stations.size > 600)
        assertTrue(data.stations.all { it.x in 0f..data.width && it.y in 0f..data.height })
        assertTrue(data.stations.any { it.id == "시청" })
    }
}
