package io.github.klanary.jigeumchulbal.data.remote

import io.github.klanary.jigeumchulbal.ui.common.Copy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** 화면이 구분해서 보여줄 오류 종류(스펙 4.5). */
sealed interface ApiError {
    /** 기기가 인터넷에 연결돼 있지 않다. */
    data object Offline : ApiError

    /** 연결은 있지만 서버에 닿지 못했다(시간 초과 등). */
    data object Network : ApiError

    /** 서버가 스펙 6.3 형식으로 돌려준 오류. message는 사용자에게 그대로 보여준다. */
    data class Server(val code: String, val reason: String?, val message: String) : ApiError

    data class Unexpected(val cause: Throwable) : ApiError
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class Err(val error: ApiError) : ApiResult<Nothing>
}

/**
 * 서버 호출을 [ApiResult]로 바꾼다. 코루틴 취소는 오류가 아니므로 그대로 다시 던진다
 * (화면을 떠날 때 오류가 뜨거나 자동 갱신이 멈추지 않는 문제를 막는다).
 */
suspend fun <T> safeApiCall(json: Json, network: NetworkMonitor, block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        ApiResult.Err(e.toServerError(json))
    } catch (e: IOException) {
        ApiResult.Err(if (network.isOnline()) ApiError.Network else ApiError.Offline)
    } catch (e: Exception) {
        ApiResult.Err(ApiError.Unexpected(e))
    }

private fun HttpException.toServerError(json: Json): ApiError.Server {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val error = body?.let { runCatching { json.decodeFromString<ErrorBodyDto>(it).error }.getOrNull() }
        ?: return ApiError.Server("HTTP_${code()}", null, Copy.SERVER_PROBLEM)
    return ApiError.Server(error.code, error.reason, error.message)
}

/** 모든 저장소가 함께 쓰는 [safeApiCall] 감싸개. */
class ApiCaller(private val json: Json, private val network: NetworkMonitor) {
    suspend operator fun <T> invoke(block: suspend () -> T): ApiResult<T> = safeApiCall(json, network, block)
}
