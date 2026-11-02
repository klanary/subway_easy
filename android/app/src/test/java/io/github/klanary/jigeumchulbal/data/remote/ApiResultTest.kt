package io.github.klanary.jigeumchulbal.data.remote

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.ui.common.Copy
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiResultTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val online = NetworkMonitor { true }
    private val offline = NetworkMonitor { false }

    private fun httpError(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test fun serverErrorBodyBecomesServerError() = runTest {
        val body = """{"error":{"code":"NO_ROUTE","reason":"NO_START_STOP","message":"출발지 근처에서 탈 수 있는 대중교통이 없어요."}}"""
        assertEquals(
            ApiResult.Err(ApiError.Server("NO_ROUTE", "NO_START_STOP", "출발지 근처에서 탈 수 있는 대중교통이 없어요.")),
            safeApiCall(json, online) { throw httpError(404, body) },
        )
    }

    @Test fun nonJsonErrorBodyGetsGenericMessage() = runTest {
        assertEquals(
            ApiResult.Err(ApiError.Server("HTTP_502", null, Copy.SERVER_PROBLEM)),
            safeApiCall(json, online) { throw httpError(502, "<html>") },
        )
    }

    @Test fun ioExceptionWhileOfflineIsOffline() = runTest {
        assertEquals(ApiResult.Err(ApiError.Offline), safeApiCall(json, offline) { throw IOException() })
    }

    @Test fun ioExceptionWhileOnlineIsNetworkError() = runTest {
        assertEquals(
            ApiResult.Err(ApiError.Network),
            safeApiCall(json, online) { throw SocketTimeoutException() },
        )
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() = runTest {
        safeApiCall(json, online) { throw CancellationException("화면을 떠남") }
    }

    @Test fun queryUsesDotDecimalInAnyLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("37.5571,126.9245", GeoPoint(37.5571, 126.9245).toQuery())
        } finally {
            Locale.setDefault(saved)
        }
    }
}
