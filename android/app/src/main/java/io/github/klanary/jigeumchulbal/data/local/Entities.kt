package io.github.klanary.jigeumchulbal.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// 기기에만 저장하는 데이터(스펙 5.4, 재설계 스펙 7). 서버에는 보내지 않는다.

/** 자주 가는 경로. 출발·경유·도착은 역 id와 화면 이름으로 저장한다(재설계 스펙 D10). */
@Entity(tableName = "favorite_routes")
data class FavoriteRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromId: String,
    val fromName: String,
    val viaId: String?,
    val viaName: String?,
    val toId: String,
    val toName: String,
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

/** 최근 검색한 역(재설계 스펙 4.3). 같은 역은 한 줄만 남는다. */
@Entity(tableName = "recent_searches", indices = [Index(value = ["stationId"], unique = true)])
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: String,
    val name: String,
    val usedAt: Long,
)
