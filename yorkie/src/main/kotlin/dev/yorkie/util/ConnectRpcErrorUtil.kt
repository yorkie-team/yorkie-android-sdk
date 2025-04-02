package dev.yorkie.util

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.google.rpc.ErrorInfo
import dev.yorkie.util.YorkieException.Code.*

/**
 * [handleConnectException] will return true if the given error is retryable.
 *
 * If caller want to handle the error about [ErrClientNotActivated] or [ErrClientNotFound],
 * then pass the lambda function to [handleError].
 */
internal suspend fun handleConnectException(
    exception: ConnectException?,
    handleError: (suspend (ConnectException) -> Unit)? = null,
): Boolean {
    val errorCode = exception?.code ?: return false

    if (errorCode == Code.CANCELED ||
        errorCode == Code.UNKNOWN ||
        errorCode == Code.RESOURCE_EXHAUSTED ||
        errorCode == Code.UNAVAILABLE
    ) {
        return true
    }

    val yorkieErrorCode = errorCodeOf(exception)
    if (yorkieErrorCode == ErrClientNotActivated.codeString ||
        yorkieErrorCode == ErrClientNotFound.codeString ||
        yorkieErrorCode == ErrUnauthenticated.codeString
    ) {
        handleError?.invoke(exception)
    }
    return false
}

fun errorCodeOf(exception: ConnectException): String {
    val infos = exception.unpackedDetails(ErrorInfo::class)
    for (info in infos) {
        info.metadataMap["code"]?.let { return it }
    }
    return ""
}

fun errorMetadataOf(exception: ConnectException): Map<String, String> {
    val infos = exception.unpackedDetails(ErrorInfo::class)
    for (info in infos) {
        return info.metadataMap
    }
    return emptyMap()
}
