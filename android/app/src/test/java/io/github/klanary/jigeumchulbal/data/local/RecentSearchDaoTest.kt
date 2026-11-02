package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentSearchDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun station(id: String, at: Long) = RecentSearchEntity(
        kind = RecentKind.STATION, label = id, lat = 37.5, lng = 127.0, stationId = id, usedAt = at,
        dedupeKey = recentDedupeKey(RecentKind.STATION, id, 37.5, 127.0, id),
    )

    @Test fun keepsOnlyTwentyNewest() = runTest {
        repeat(25) { db.recentSearchDao().record(station("역$it", it.toLong())) }
        assertEquals(20, db.recentSearchDao().count())
        assertEquals("역24", db.recentSearchDao().observeRecent().first().first().label)
    }

    @Test fun sameStationTwiceKeepsOneRowOnTop() = runTest {
        listOf("홍대입구" to 1L, "합정" to 2L, "홍대입구" to 3L).forEach { (id, at) ->
            db.recentSearchDao().record(station(id, at))
        }
        assertEquals(listOf("홍대입구", "합정"), db.recentSearchDao().observeRecent().first().map { it.label })
    }
}
