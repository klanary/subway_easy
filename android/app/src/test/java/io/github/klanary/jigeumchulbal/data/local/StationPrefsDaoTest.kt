package io.github.klanary.jigeumchulbal.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 즐겨찾는 역·최근 경로(수동 검토 2026-11-22)와 버전 3 → 4 마이그레이션. */
@RunWith(RobolectricTestRunner::class)
class StationPrefsDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun favoriteStationsAddAndRemove() = runTest {
        val dao = db.favoriteStationDao()
        dao.add(FavoriteStationEntity("시청", "시청", 1))
        dao.add(FavoriteStationEntity("방이", "방이", 2))
        assertEquals(listOf("방이", "시청"), dao.observeAll().first().map { it.stationId })
        dao.remove("시청")
        assertEquals(listOf("방이"), dao.observeAll().first().map { it.stationId })
    }

    @Test fun recentRoutesDedupeAndKeepTwenty() = runTest {
        val dao = db.recentRouteDao()
        fun route(from: String, to: String, at: Long, via: String = "") =
            RecentRouteEntity(fromId = from, fromName = from, viaId = via, viaName = via, toId = to, toName = to, usedAt = at)
        dao.record(route("방이", "소사", 1))
        dao.record(route("시청", "강남", 2))
        dao.record(route("방이", "소사", 3))
        assertEquals(listOf("방이", "시청"), dao.observeRecent().first().map { it.fromId })
        repeat(25) { dao.record(route("역$it", "끝", 10L + it)) }
        assertEquals(20, dao.observeRecent().first().size)
    }

    @Test fun migrationFrom3KeepsRoutinesAndAddsTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        // 버전 3 모양의 DB를 만들고 루틴 하나를 넣는다(Room이 버전 3에서 만든 표 그대로).
        val v3 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        V3_TABLES.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO routines (name, fromId, fromName, toId, toName, routeJson, days, minuteOfDay, walkMin, alightAlerts, enabled) " +
                                "VALUES ('출근', '방이', '방이', '소사', '소사', '{}', 31, 480, 5, 1, 1)",
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        v3.writableDatabase.close()
        v3.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_3_4).allowMainThreadQueries().build()
        assertEquals(listOf("출근"), migrated.routineDao().observeAll().first().map { it.name })
        migrated.favoriteStationDao().add(FavoriteStationEntity("시청", "시청", 1))
        assertEquals(1, migrated.favoriteStationDao().observeAll().first().size)
        migrated.close()
        context.deleteDatabase(name)
    }

    private companion object {
        val V3_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `favorite_routes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromId` TEXT NOT NULL, `fromName` TEXT NOT NULL, `viaId` TEXT, `viaName` TEXT, `toId` TEXT NOT NULL, `toName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `route_usages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routeId` INTEGER NOT NULL, `usedAt` INTEGER NOT NULL, FOREIGN KEY(`routeId`) REFERENCES `favorite_routes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_route_usages_routeId` ON `route_usages` (`routeId`)",
            "CREATE TABLE IF NOT EXISTS `recent_searches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stationId` TEXT NOT NULL, `name` TEXT NOT NULL, `usedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_searches_stationId` ON `recent_searches` (`stationId`)",
            "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `fromId` TEXT NOT NULL, `fromName` TEXT NOT NULL, `viaId` TEXT, `viaName` TEXT, `toId` TEXT NOT NULL, `toName` TEXT NOT NULL, `routeJson` TEXT NOT NULL, `days` INTEGER NOT NULL, `minuteOfDay` INTEGER NOT NULL, `walkMin` INTEGER NOT NULL, `alightAlerts` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)",
        )
    }
}
