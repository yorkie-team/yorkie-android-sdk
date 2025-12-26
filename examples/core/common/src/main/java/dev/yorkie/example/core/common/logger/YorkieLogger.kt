package dev.yorkie.example.core.common.logger

import android.util.Log
import dev.yorkie.example.core.common.BuildConfig
import dev.yorkie.util.Logger
import timber.log.Timber

object YorkieLogger {
    fun initLogger() {
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
