package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService

interface ServerRepository {
    /** 잠든 서버(Render 무료)를 깨운다. 앱을 열 때 먼저 부른다(스펙 10.1). */
    suspend fun wake(): Boolean
}

class DefaultServerRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : ServerRepository {
    override suspend fun wake(): Boolean = call { api.health() } is ApiResult.Ok
}
