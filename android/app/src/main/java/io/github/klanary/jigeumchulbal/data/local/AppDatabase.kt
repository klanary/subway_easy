package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/** 버전 1에 다섯 테이블을 모두 둔다. P4가 favorite_routes·route_usages를 마이그레이션 없이 쓴다. */
@Database(
    entities = [
        MyPlaceEntity::class,
        FavoriteStationEntity::class,
        FavoriteRouteEntity::class,
        RouteUsageEntity::class,
        RecentSearchEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun myPlaceDao(): MyPlaceDao

    abstract fun favoriteStationDao(): FavoriteStationDao

    abstract fun favoriteRouteDao(): FavoriteRouteDao

    abstract fun recentSearchDao(): RecentSearchDao
}
