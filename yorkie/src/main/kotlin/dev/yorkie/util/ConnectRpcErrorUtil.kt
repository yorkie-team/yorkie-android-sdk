package dev.yorkie.util

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException

/**
 * [isRetryable] will return true if the given error is retryable.
 */
public fun isRetryable(exception: ConnectException?): Boolean {
    val errorCode = exception?.code ?: return false
    return errorCode == Code.CANCELED ||
        errorCode == Code.UNKNOWN ||
        errorCode == Code.RESOURCE_EXHAUSTED ||
        errorCode == Code.UNAVAILABLE
}
