package io.github.klanary.jigeumchulbal

import android.app.Application
import io.github.klanary.jigeumchulbal.di.AppContainer
import io.github.klanary.jigeumchulbal.session.createNotificationChannels
import io.github.klanary.jigeumchulbal.ui.linemap.LineMapPictureLoader

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 노선도 해석은 오래 걸려 화면이 뜨기 전에 시작한다(화면 다듬기 Task 3).
        LineMapPictureLoader.preload(this)
        createNotificationChannels(this)
        container = AppContainer(this)
    }
}
