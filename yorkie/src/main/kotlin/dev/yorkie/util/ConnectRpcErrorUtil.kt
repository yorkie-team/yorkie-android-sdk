package dev.yorkie.util

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.google.rpc.ErrorInfo
import dev.yorkie.util.YorkieException.Code.ErrClientNotActivated
import dev.yorkie.util.YorkieException.Code.ErrClientNotFound
import dev.yorkie.util.YorkieException.Code.ErrTooManySubscribers
import dev.yorkie.util.YorkieException.Code.ErrUnauthenticated

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

    if (yorkieErrorCode == ErrTooManySubscribers.codeString) {
        return true
    }

    if (yorkieErrorCode == ErrClientNotActivated.codeString ||
        yorkieErrorCode == ErrClientNotFound.codeString ||
        yorkieErrorCode == ErrUnauthenticated.codeString
    ) {
        handleError?.invoke(exception)
    }
    return false
}

fun errorCodeOf(exception: ConnectException): String {
    return errorMetadataOf(exception)?.get("code").orEmpty()
}

fun errorMetadataOf(exception: ConnectException): Map<String, String>? {
    val infos = exception.unpackedDetails(ErrorInfo::class)
    return infos.firstOrNull()?.metadataMap
}
