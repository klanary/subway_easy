package io.github.klanary.jigeumchulbal.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** 중계 서버 API(스펙 6.2). null인 쿼리 파라미터는 보내지 않는다. */
interface ApiService {
    @GET("health")
    suspend fun health(): Map<String, String>

    @GET("v1/stations/search")
    suspend fun searchStations(@Query("q") q: String, @Query("limit") limit: Int = 20): StationSearchDto

    @GET("v1/stations/{id}/arrivals")
    suspend fun arrivals(
        @Path("id") id: String,
        @Query("line") line: String?,
        @Query("towards") towards: String?,
    ): ArrivalsDto

    /** 반경 2km 안의 가장 가까운 역(재설계 스펙 6). */
    @GET("v1/stations/nearest")
    suspend fun nearestStation(@Query("lat") lat: Double, @Query("lng") lng: Double): NearestDto

    /** [from](ISO 시각) 이후 시간표 열차 [count]대. [from]이 없으면 지금부터. */
    @GET("v1/stations/{id}/timetable")
    suspend fun timetable(
        @Path("id") id: String,
        @Query("line") line: String,
        @Query("direction") direction: String,
        @Query("from") from: String?,
        @Query("count") count: Int,
    ): TimetableDto

    /** 역 id로 묻는다. [departAt](ISO 시각)을 주면 시간표 열차와 도착 예상이 붙는다. */
    @GET("v1/routes")
    suspend fun routes(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("via") via: String?,
        @Query("departAt") departAt: String?,
    ): RoutesDto
}
