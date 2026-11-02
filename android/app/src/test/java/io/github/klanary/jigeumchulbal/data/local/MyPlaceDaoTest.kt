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
class MyPlaceDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: MyPlaceDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.myPlaceDao()
    }

    @After fun tearDown() = db.close()

    private fun place(type: MyPlaceType, label: String, lat: Double) =
        MyPlaceEntity(type = type, label = label, lat = lat, lng = 127.0, address = null, stationId = null)

    @Test fun savingHomeTwiceReplacesIt() = runTest {
        dao.saveTyped(place(MyPlaceType.HOME, "집", 37.1))
        dao.saveTyped(place(MyPlaceType.HOME, "집", 37.2))
        assertEquals(listOf(37.2), dao.observeAll().first().filter { it.type == MyPlaceType.HOME }.map { it.lat })
    }

    @Test fun customPlacesAccumulateAfterTypedOnes() = runTest {
        dao.saveTyped(place(MyPlaceType.CUSTOM, "도서관", 37.3))
        dao.saveTyped(place(MyPlaceType.CUSTOM, "학원", 37.4))
        dao.saveTyped(place(MyPlaceType.SCHOOL, "학교", 37.5))
        assertEquals(listOf("학교", "도서관", "학원"), dao.observeAll().first().map { it.label })
    }
}
