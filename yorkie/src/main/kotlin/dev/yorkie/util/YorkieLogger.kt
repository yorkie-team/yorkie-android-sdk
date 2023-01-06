package dev.yorkie.util

internal object YorkieLogger {
    private const val TAG_PREFIX = "Yorkie."

    private var logger: Logger? = null

    fun d(tag: String, message: String) {
        logger?.d("$TAG_PREFIX$tag", message)
    }

    fun e(tag: String, message: String) {
        logger?.e("$TAG_PREFIX$tag", message)
        throw Exception(message)
    }
}

internal interface Logger {
    fun d(tag: String, message: String)

    fun e(tag: String, message: String)
}
