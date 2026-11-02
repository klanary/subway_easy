package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

// 기기에만 저장하는 데이터(스펙 5.4, 7.2). 서버에는 보내지 않는다.

enum class MyPlaceType { HOME, SCHOOL, WORK, CUSTOM }

@Entity(tableName = "my_places")
data class MyPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: MyPlaceType,
    val label: String,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val stationId: String?,
)

@Entity(tableName = "favorite_stations")
data class FavoriteStationEntity(
    @PrimaryKey val stationId: String,
    val line: String? = null,
    val towards: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "favorite_routes")
data class FavoriteRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originLabel: String,
    val originLat: Double,
    val originLng: Double,
    val viaLabel: String?,
    val viaLat: Double?,
    val viaLng: Double?,
    val destLabel: String,
    val destLat: Double,
    val destLng: Double,
    val createdAt: Long,
)

/** 자주 가는 경로의 사용 기록. 경로를 지우면 기록도 함께 지워진다. */
@Entity(
    tableName = "route_usages",
    foreignKeys = [
        ForeignKey(
            entity = FavoriteRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routeId")],
)
data class RouteUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val usedAt: Long,
)

enum class RecentKind { STATION, PLACE, PIN }

/** 최근 검색. 같은 항목([dedupeKey])은 한 줄만 남는다. */
@Entity(tableName = "recent_searches", indices = [Index(value = ["dedupeKey"], unique = true)])
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: RecentKind,
    val label: String,
    val lat: Double,
    val lng: Double,
    val stationId: String?,
    val usedAt: Long,
    val dedupeKey: String,
)

/** 같은 역, 같은 이름·좌표의 장소, 같은 좌표의 핀을 같은 항목으로 본다(좌표는 소수점 5자리, 약 1m). */
fun recentDedupeKey(kind: RecentKind, label: String, lat: Double, lng: Double, stationId: String?): String =
    when (kind) {
        RecentKind.STATION -> "S:$stationId"
        RecentKind.PLACE -> String.format(Locale.US, "P:%s@%.5f,%.5f", label, lat, lng)
        RecentKind.PIN -> String.format(Locale.US, "M:%.5f,%.5f", lat, lng)
    }
