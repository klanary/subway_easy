package io.github.klanary.jigeumchulbal.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

interface LocationProvider {
    fun hasPermission(): Boolean

    /** 현재 위치. 권한이 없거나 위치를 얻지 못하면 null. */
    suspend fun current(): GeoPoint?

    /**
     * [intervalMs]마다 받는 위치(탑승 감지, 위치 결합 스펙 7.1). 모으는 동안만 위치를 받고, 권한이 없으면 빈 흐름이다.
     * 위치는 기기 안에서만 쓰고 서버로 보내지 않는다(3.2).
     */
    fun updates(intervalMs: Long = 10_000): Flow<LocationFix> = emptyFlow()
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

    @SuppressLint("MissingPermission") // hasPermission()으로 먼저 확인하고, 그새 거두면 SecurityException을 잡는다
    override fun updates(intervalMs: Long): Flow<LocationFix> {
        if (!hasPermission()) return emptyFlow()
        return callbackFlow {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { trySend(it.toFix()) }
                }
            }
            try {
                // 높은 정확도, 10초 간격(7.1). 지하에서는 와이파이·기지국 위치가 와서 정확도가 나쁠 수 있다.
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build()
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                close() // 확인한 뒤 사용자가 권한을 거둔 경우: 빈 흐름으로 끝낸다
                return@callbackFlow
            }
            awaitClose { client.removeLocationUpdates(callback) }
        }
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    /** 정확도를 모르는 위치는 쓰지 않도록 가장 나쁜 값으로 둔다. */
    private fun Location.toFix() = LocationFix(GeoPoint(latitude, longitude), if (hasAccuracy()) accuracy else Float.MAX_VALUE, time)
}
