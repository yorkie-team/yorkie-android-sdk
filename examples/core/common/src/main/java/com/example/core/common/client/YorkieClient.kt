package com.example.core.common.client

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object YorkieClient {
    fun createUnaryClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .build()
    }

    fun createStreamClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.MINUTES)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
