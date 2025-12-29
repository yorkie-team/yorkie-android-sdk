package dev.yorkie.example.todomvc

import android.app.Application
import dev.yorkie.example.core.common.logger.YorkieLogger

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        YorkieLogger.initLogger()
    }
}
