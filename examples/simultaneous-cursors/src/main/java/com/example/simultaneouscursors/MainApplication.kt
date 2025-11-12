package com.example.simultaneouscursors

import android.app.Application
import dev.yorkie.core.common.logger.YorkieLogger

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        YorkieLogger.initLogger()
    }
}
