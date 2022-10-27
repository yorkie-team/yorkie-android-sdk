package dev.yorkie.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal val YorkieScope = CoroutineScope(
    SupervisorJob() +
        Executors.newSingleThreadExecutor().asCoroutineDispatcher() +
        CoroutineExceptionHandler { _, throwable ->
            YorkieLogger.e("", throwable.stackTraceToString())
        },
)
