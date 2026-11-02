package io.github.klanary.jigeumchulbal.di

import android.content.Context
import io.github.klanary.jigeumchulbal.BuildConfig
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.ConnectivityNetworkMonitor
import io.github.klanary.jigeumchulbal.data.remote.NetworkMonitor
import io.github.klanary.jigeumchulbal.data.repository.DefaultPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultRouteRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultServerRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultStationRepository
import io.github.klanary.jigeumchulbal.data.repository.PlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.data.repository.ServerRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** 앱의 의존 객체를 손으로 연결한다(스펙 5.3). App.onCreate에서 한 번 만든다. */
class AppContainer(context: Context) {
    val json = Json { ignoreUnknownKeys = true }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Render 무료 서버는 잠들었다 깨어나는 데 1분 가까이 걸린다(스펙 10.1).
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    private val api: ApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.SERVER_BASE_URL)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)

    val networkMonitor: NetworkMonitor = ConnectivityNetworkMonitor(context.applicationContext)

    private val apiCaller = ApiCaller(json, networkMonitor)

    val stationRepository: StationRepository = DefaultStationRepository(api, apiCaller)
    val placeRepository: PlaceRepository = DefaultPlaceRepository(api, apiCaller)
    val routeRepository: RouteRepository = DefaultRouteRepository(api, apiCaller)
    val serverRepository: ServerRepository = DefaultServerRepository(api, apiCaller)
}
