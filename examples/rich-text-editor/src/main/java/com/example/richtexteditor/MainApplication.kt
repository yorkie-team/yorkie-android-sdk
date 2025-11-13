package com.example.richtexteditor

import android.app.Application
import com.example.core.common.logger.YorkieLogger

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        YorkieLogger.initLogger()
    }
}
