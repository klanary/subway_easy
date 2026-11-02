package io.github.klanary.jigeumchulbal

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk
import io.github.klanary.jigeumchulbal.di.AppContainer
import io.github.klanary.jigeumchulbal.session.createNotificationChannels

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        createNotificationChannels(this)
        container = AppContainer(this)
    }
}
