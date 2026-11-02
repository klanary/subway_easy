package io.github.klanary.jigeumchulbal

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
    }
}
