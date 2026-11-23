package io.github.klanary.jigeumchulbal.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 오류를 "오프라인"과 "서버 연결 실패"로 나누기 위한 연결 상태 확인. */
fun interface NetworkMonitor {
    fun isOnline(): Boolean
}

class ConnectivityNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
