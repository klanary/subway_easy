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

    @GET("v1/places/search")
    suspend fun searchPlaces(
        @Query("q") q: String,
        @Query("lat") lat: Double?,
        @Query("lng") lng: Double?,
    ): PlaceSearchDto

    @GET("v1/places/reverse")
    suspend fun reverse(@Query("lat") lat: Double, @Query("lng") lng: Double): ReverseDto

    @GET("v1/routes")
    suspend fun routes(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("via") via: String?,
        @Query("fromName") fromName: String?,
        @Query("toName") toName: String?,
        @Query("viaName") viaName: String?,
    ): RoutesDto
}
