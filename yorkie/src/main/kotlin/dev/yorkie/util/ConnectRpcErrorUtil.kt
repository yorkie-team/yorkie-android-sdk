package dev.yorkie.util

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException

/**
 * `isRetryable` will return true if the given error is retryable.
 */
public fun isRetryable(exception: ConnectException?): Boolean {
    if (exception == null) {
        return false
    }

    val errorCode = exception.code
    val isRetryableErrorCode = errorCode === Code.CANCELED ||
        errorCode === Code.UNKNOWN ||
        errorCode === Code.RESOURCE_EXHAUSTED ||
        errorCode === Code.UNAVAILABLE

    return isRetryableErrorCode
}
