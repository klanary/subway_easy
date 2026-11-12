package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        fromId = "마포", fromName = "마포", viaId = null, viaName = null,
        toId = "공덕", toName = "공덕", createdAt = 1,
    )

    @Test fun routeStoresStationIds() = runTest {
        val dao = db.favoriteRouteDao()
        dao.insert(route())
        assertEquals(listOf("마포" to "공덕"), dao.observeAll().first().map { it.fromId to it.toId })
    }

    @Test fun deletingRouteDeletesItsUsages() = runTest {
        val dao = db.favoriteRouteDao()
        val id = dao.insert(route())
        dao.insertUsage(RouteUsageEntity(routeId = id, usedAt = 1))
        dao.delete(id)
        assertTrue(dao.usagesSince(0).isEmpty())
    }
}
