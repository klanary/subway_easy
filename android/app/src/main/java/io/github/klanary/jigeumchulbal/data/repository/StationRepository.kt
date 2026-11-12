package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.NearestStationDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.remote.TimetableDto
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val KST: ZoneOffset = ZoneOffset.ofHours(9)

/** 서버에 보내는 시각. 한국 시간 오프셋을 붙인 ISO 형식("2026-11-13T08:00:00+09:00"). */
fun isoKst(at: Instant): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atOffset(KST).withNano(0))

interface StationRepository {
    suspend fun search(q: String, limit: Int = 20): ApiResult<List<StationDto>>

    suspend fun arrivals(stationId: String, line: String? = null, towards: String? = null): ApiResult<ArrivalsDto>

    /** [from] 이후 시간표 열차 [count]대(재설계 스펙 D11). [from]이 null이면 지금부터. */
    suspend fun timetable(
        stationId: String,
        line: String,
        direction: String,
        from: Instant? = null,
        count: Int = 4,
    ): ApiResult<TimetableDto>

    /** 반경 2km 안의 가장 가까운 역. 없으면 Ok(null). */
    suspend fun nearest(lat: Double, lng: Double): ApiResult<NearestStationDto?>
}

class DefaultStationRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : StationRepository {
    override suspend fun search(q: String, limit: Int): ApiResult<List<StationDto>> =
        call { api.searchStations(q, limit).items }

    override suspend fun arrivals(stationId: String, line: String?, towards: String?): ApiResult<ArrivalsDto> =
        call { api.arrivals(stationId, line, towards) }

    override suspend fun timetable(
        stationId: String,
        line: String,
        direction: String,
        from: Instant?,
        count: Int,
    ): ApiResult<TimetableDto> = call { api.timetable(stationId, line, direction, from?.let(::isoKst), count) }

    override suspend fun nearest(lat: Double, lng: Double): ApiResult<NearestStationDto?> =
        call { api.nearestStation(lat, lng).station }
}
