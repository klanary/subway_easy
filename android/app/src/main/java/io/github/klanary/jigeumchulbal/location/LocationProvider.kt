package io.github.klanary.jigeumchulbal.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

interface LocationProvider {
    fun hasPermission(): Boolean

    /** 현재 위치. 권한이 없거나 위치를 얻지 못하면 null. */
    suspend fun current(): GeoPoint?
}

/**
 * 현재 위치를 [timeoutMs] 안에 얻지 못하면(실내·에뮬레이터 등) 마지막으로 알려진 위치를 쓴다(스펙 7.4).
 * 둘 다 없으면 null이며, 화면은 출발 칸을 비워 두고 검색을 안내한다.
 */
suspend fun currentOrLast(
    current: suspend () -> GeoPoint?,
    last: suspend () -> GeoPoint?,
    timeoutMs: Long = 5_000,
): GeoPoint? = withTimeoutOrNull(timeoutMs) { current() } ?: last()

class FusedLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }

    // await(CancellationTokenSource): 코루틴이 취소되면(5초 제한) 위치 요청도 함께 취소한다.
    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission") // hasPermission()으로 먼저 확인한다
    override suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        return try {
            currentOrLast(
                current = {
                    val cancellation = CancellationTokenSource()
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                        .await(cancellation)
                        ?.toGeoPoint()
                },
                last = { client.lastLocation.await()?.toGeoPoint() },
            )
        } catch (e: SecurityException) {
            null // 확인한 뒤 사용자가 권한을 거둔 경우
        }
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
}
