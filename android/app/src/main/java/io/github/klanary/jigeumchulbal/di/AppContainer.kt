package io.github.klanary.jigeumchulbal.di

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import io.github.klanary.jigeumchulbal.BuildConfig
import io.github.klanary.jigeumchulbal.data.local.AppDatabase
import io.github.klanary.jigeumchulbal.data.local.SettingsStore
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.ConnectivityNetworkMonitor
import io.github.klanary.jigeumchulbal.data.remote.NetworkMonitor
import io.github.klanary.jigeumchulbal.data.repository.DefaultFavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultMyPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultRecentSearchRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultRouteRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultServerRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultStationRepository
import io.github.klanary.jigeumchulbal.data.repository.DefaultTrainRepository
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.MyPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.PlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.RecentSearchRepository
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.data.repository.ServerRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.data.repository.TrainRepository
import io.github.klanary.jigeumchulbal.location.FusedLocationProvider
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.session.AlarmScheduler
import io.github.klanary.jigeumchulbal.session.AndroidAlarmScheduler
import io.github.klanary.jigeumchulbal.session.AndroidServiceLauncher
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionNotifier
import io.github.klanary.jigeumchulbal.session.SessionStore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/** 앱의 의존 객체를 손으로 연결한다(스펙 5.3). App.onCreate에서 한 번 만든다. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

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

    val networkMonitor: NetworkMonitor = ConnectivityNetworkMonitor(appContext)

    private val apiCaller = ApiCaller(json, networkMonitor)

    val stationRepository: StationRepository = DefaultStationRepository(api, apiCaller)
    val placeRepository: PlaceRepository = DefaultPlaceRepository(api, apiCaller)
    val routeRepository: RouteRepository = DefaultRouteRepository(api, apiCaller)
    val serverRepository: ServerRepository = DefaultServerRepository(api, apiCaller)
    val trainRepository: TrainRepository = DefaultTrainRepository(api, apiCaller)

    // 기기 저장소: 내 장소·즐겨찾기·최근 검색은 기기에만 둔다(스펙 5.4).
    val database: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "jigeumchulbal.db").build()

    val settingsStore = SettingsStore(appContext.settingsDataStore)

    val myPlaceRepository: MyPlaceRepository = DefaultMyPlaceRepository(database.myPlaceDao())
    val favoriteRepository: FavoriteRepository = DefaultFavoriteRepository(database.favoriteStationDao(), database.favoriteRouteDao())
    val recentSearchRepository: RecentSearchRepository = DefaultRecentSearchRepository(database.recentSearchDao())

    val locationProvider: LocationProvider = FusedLocationProvider(appContext)

    // 통학 세션: 알람·알림·대기 서비스를 안드로이드 구현으로 연결한다.
    val alarmScheduler: AlarmScheduler =
        AndroidAlarmScheduler(appContext, appContext.getSystemService(AlarmManager::class.java))
    val sessionNotifier = SessionNotifier(appContext, alarmScheduler)
    val sessionController = SessionController(
        store = SessionStore(appContext.sessionDataStore, json),
        scheduler = alarmScheduler,
        notifier = sessionNotifier,
        launcher = AndroidServiceLauncher(appContext),
        clock = System::currentTimeMillis,
    )
}
