package dev.yorkie.util

public object YorkieLogger {
    private const val TAG_PREFIX = "Yorkie."

    public var logger: Logger? = null

    fun d(tag: String, message: String) {
        logger?.d("$TAG_PREFIX$tag", message)
    }

    fun e(tag: String, message: String) {
        logger?.e("$TAG_PREFIX$tag", message)
    }
}

public interface Logger {
    fun d(tag: String, message: String)

    fun e(tag: String, message: String)
}
