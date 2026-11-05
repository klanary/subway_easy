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

    /** 역 앞뒤 3정거장 안의 열차(위치 결합 스펙 6.3). [toward]를 주면 그쪽으로 가는 열차만. */
    @GET("v1/lines/{line}/trains")
    suspend fun lineTrains(
        @Path("line") line: String,
        @Query("station") station: String,
        @Query("toward") toward: String?,
    ): LineTrainsDto

    /** 탄 열차가 내릴 역([to])까지 남은 시간. */
    @GET("v1/trains/{trainNo}")
    suspend fun trainEta(
        @Path("trainNo") trainNo: String,
        @Query("line") line: String,
        @Query("to") to: String,
    ): TrainEtaDto

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
