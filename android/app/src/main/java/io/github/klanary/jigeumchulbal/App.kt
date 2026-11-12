package io.github.klanary.jigeumchulbal

import android.app.Application
import io.github.klanary.jigeumchulbal.di.AppContainer
import io.github.klanary.jigeumchulbal.session.createNotificationChannels

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        container = AppContainer(this)
    }
}
