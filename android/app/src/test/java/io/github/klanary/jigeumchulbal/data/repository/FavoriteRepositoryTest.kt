package io.github.klanary.jigeumchulbal.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.klanary.jigeumchulbal.data.local.AppDatabase
import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoriteRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DefaultFavoriteRepository
    private val mapo = StationRef("마포", "마포")
    private val gongdeok = StationRef("공덕", "공덕")
    private val aeogae = StationRef("애오개", "애오개")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DefaultFavoriteRepository(db.favoriteRouteDao()) { 1L }
    }

    @After fun tearDown() = db.close()

    @Test fun sameStationsAreSavedOnce() = runTest {
        repo.add(mapo, null, gongdeok)
        repo.add(mapo, null, gongdeok)
        assertEquals(1, repo.routes.first().size)
    }

    @Test fun viaMakesADifferentRoute() = runTest {
        repo.add(mapo, null, gongdeok)
        repo.add(mapo, aeogae, gongdeok)
        assertEquals(2, repo.routes.first().size)
        assertNull(repo.find(gongdeok, null, mapo)) // 방향이 다르면 다른 경로
    }

    @Test fun usageIsRecordedOnlyForFavorites() = runTest {
        repo.add(mapo, null, gongdeok)
        repo.recordUsageIfFavorite(mapo, null, gongdeok, at = 100)
        repo.recordUsageIfFavorite(mapo, aeogae, gongdeok, at = 200)
        assertEquals(listOf(100L), repo.usagesSince(0).first().map { it.usedAt })
    }
}
