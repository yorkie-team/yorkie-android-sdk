package dev.yorkie.util

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun createSingleThreadDispatcher(threadName: String) = ThreadPoolExecutor(
    0,
    1,
    5,
    TimeUnit.MINUTES,
    LinkedBlockingQueue(),
    ThreadFactory {
        Thread(it, threadName).apply {
            if (isDaemon) {
                isDaemon = false
            }
            if (priority != Thread.NORM_PRIORITY) {
                priority = Thread.NORM_PRIORITY
            }
        }
    },
).asCoroutineDispatcher()
