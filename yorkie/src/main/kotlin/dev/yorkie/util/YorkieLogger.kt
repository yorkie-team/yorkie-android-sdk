package dev.yorkie.util

internal object YorkieLogger {
    private const val TAG_PREFIX = "Yorkie."

    var logger: Logger? = null

    fun d(tag: String, message: String, t: Throwable? = null) {
        logger?.d("$TAG_PREFIX$tag", message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        logger?.e("$TAG_PREFIX$tag", message)
    }
}

interface Logger {
    fun d(tag: String, message: String)

    fun e(tag: String, message: String)
}
