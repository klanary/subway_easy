package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.klanary.jigeumchulbal.routine.RoutineDao
import io.github.klanary.jigeumchulbal.routine.RoutineEntity

/**
 * 버전 2: 재설계로 내 장소·즐겨찾는 역을 빼고 자주 가는 경로를 역 id로 바꿨다. 버전 3: 루틴 표를 더했다.
 * 아직 배포 전이라 마이그레이션 없이 옛 데이터를 지운다(재설계 스펙 7, AppContainer의 fallbackToDestructiveMigration).
 */
@Database(
    entities = [
        FavoriteRouteEntity::class,
        RouteUsageEntity::class,
        RecentSearchEntity::class,
        RoutineEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteRouteDao(): FavoriteRouteDao

    abstract fun recentSearchDao(): RecentSearchDao

    abstract fun routineDao(): RoutineDao
}
