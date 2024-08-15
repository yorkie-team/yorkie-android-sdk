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
    println("isRetryable.errorCode: $errorCode")
    return errorCode === Code.CANCELED ||
        errorCode === Code.UNKNOWN ||
        errorCode === Code.RESOURCE_EXHAUSTED ||
        errorCode === Code.UNAVAILABLE
}


/**
 * `errorCodeOf` returns the error code of the given connect error.
 */
fun errorCodeOf(error: ConnectException): String {
    // NOTE(hackerwins): Currently, we only use the first detail to represent the
    // error code.
    val errorDetails = error.unpackedDetails(ConnectErrorDetail::class)
//    error.withErrorDetails()
    return "";
}
