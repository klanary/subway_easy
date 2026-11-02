package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoriteDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun route() = FavoriteRouteEntity(
        originLabel = "집", originLat = 37.55, originLng = 126.92,
        viaLabel = null, viaLat = null, viaLng = null,
        destLabel = "학교", destLat = 37.50, destLng = 127.03, createdAt = 1,
    )

    @Test fun stationFavoriteToggles() = runTest {
        val dao = db.favoriteStationDao()
        dao.insert(FavoriteStationEntity(stationId = "홍대입구", createdAt = 1))
        assertTrue(dao.observeIsFavorite("홍대입구").first())
        dao.delete("홍대입구")
        assertFalse(dao.observeIsFavorite("홍대입구").first())
    }

    @Test fun deletingRouteDeletesItsUsages() = runTest {
        val dao = db.favoriteRouteDao()
        val id = dao.insert(route())
        dao.insertUsage(RouteUsageEntity(routeId = id, usedAt = 1))
        dao.delete(id)
        assertTrue(dao.usagesSince(0).isEmpty())
    }
}
