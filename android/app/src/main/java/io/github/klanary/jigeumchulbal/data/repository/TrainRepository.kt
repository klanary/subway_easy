package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.LineTrainsDto
import io.github.klanary.jigeumchulbal.data.remote.TrainEtaDto

/** 실시간 열차 위치로 탄 열차를 정하고(⑭) 따라가는(⑪) 데 쓰는 서버 API(위치 결합 스펙 6.3). */
interface TrainRepository {
    /** [station] 앞뒤 3정거장 안의 열차. [toward](다음 정거장 이름이나 역 id)를 주면 그쪽으로 가는 열차만. */
    suspend fun near(line: String, station: String, toward: String?): ApiResult<LineTrainsDto>

    /** 열차 [trainNo]가 [to] 역까지 남은 시간. */
    suspend fun eta(trainNo: String, line: String, to: String): ApiResult<TrainEtaDto>
}

class DefaultTrainRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : TrainRepository {
    override suspend fun near(line: String, station: String, toward: String?): ApiResult<LineTrainsDto> =
        call { api.lineTrains(line, station, toward) }

    override suspend fun eta(trainNo: String, line: String, to: String): ApiResult<TrainEtaDto> =
        call { api.trainEta(trainNo, line, to) }
}
