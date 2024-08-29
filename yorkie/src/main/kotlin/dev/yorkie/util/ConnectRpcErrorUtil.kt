package dev.yorkie.util

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException

/**
 * `handleConnectError` handles the given error. If the given error can be
 * retried after handling, it returns true.
 */
fun isRetryable(exception: ConnectException?): Boolean {
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
