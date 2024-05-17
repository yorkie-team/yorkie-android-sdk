package dev.yorkie.util

import android.util.Log

public interface Logger {
    /**
     * Minimum priority to log.
     * It should be either [Log.DEBUG] or [Log.ERROR]
     */
    val minimumPriority: Int

    fun d(tag: String, message: String? = null, throwable: Throwable? = null)

    fun e(tag: String, message: String? = null, throwable: Throwable? = null)

    companion object {
        private var instance: Logger? = null

        public fun init(logger: Logger) {
            instance = logger
        }

        @Suppress("NOTHING_TO_INLINE")
        internal inline fun log(
            priority: Int,
            tag: String,
            message: String? = null,
            throwable: Throwable? = null,
        ) {
            val logger = instance ?: return
            if (priority < logger.minimumPriority) {
                return
            }
            if (message == null && throwable == null) {
                return
            }

            when (priority) {
                Log.DEBUG -> {
                    logger.d(tag, message, throwable)
                }

                Log.ERROR -> {
                    logger.e(tag, message, throwable)
                }

                else -> return
            }
        }

        internal inline fun log(
            priority: Int,
            tag: String,
            throwable: Throwable? = null,
            message: () -> String,
        ) {
            // return fast if Logger is not set
            val logger = instance ?: return
            if (priority < logger.minimumPriority) {
                return
            }
            log(priority, tag, message(), throwable)
        }

        @Suppress("NOTHING_TO_INLINE")
        internal inline fun logError(
            tag: String,
            message: String? = null,
            throwable: Throwable? = null,
        ) {
            log(Log.ERROR, tag, message, throwable)
        }

        internal inline fun logError(
            tag: String,
            throwable: Throwable? = null,
            message: () -> String,
        ) {
            log(Log.ERROR, tag, throwable, message)
        }

        @Suppress("NOTHING_TO_INLINE")
        internal inline fun logDebug(
            tag: String,
            message: String? = null,
            throwable: Throwable? = null,
        ) {
            log(Log.DEBUG, tag, message, throwable)
        }

        internal inline fun logDebug(
            tag: String,
            throwable: Throwable? = null,
            message: () -> String,
        ) {
            log(Log.DEBUG, tag, throwable, message)
        }
    }
}
