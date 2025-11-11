package com.example.todomvc

import android.app.Application
import android.util.Log
import dev.yorkie.util.Logger
import timber.log.Timber

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initLogger()
    }

    private fun initLogger() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Logger.init(
            object : Logger {
                override val minimumPriority: Int = Log.DEBUG

                override fun d(
                    tag: String,
                    message: String?,
                    throwable: Throwable?,
                ) {
                    Timber.d("$tag: ${message.orEmpty()}\n$throwable")
                }

                override fun e(
                    tag: String,
                    message: String?,
                    throwable: Throwable?,
                ) {
                    Timber.e("$tag: ${message.orEmpty()}\n$throwable")
                }
            },
        )
    }
}
