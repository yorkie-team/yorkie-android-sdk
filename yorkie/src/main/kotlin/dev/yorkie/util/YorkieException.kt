package dev.yorkie.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * `YorkieError` is an error returned by a Yorkie operation.
 */
public data class YorkieException(public val code: Code, public val errorMessage: String) : RuntimeException(errorMessage) {
    public enum class Code {
        // Ok is returned when the operation completed successfully.
        Ok,

        // ErrClientNotActivated is returned when the client is not active.
        ErrClientNotActivated,

        // ErrClientNotFound is returned when the client is not found.
        ErrClientNotFound,

        // ErrUnimplemented is returned when the operation is not implemented.
        ErrUnimplemented,

        // Unsupported is returned when the operation is not supported.
        Unsupported,

        // ErrDocumentNotAttached is returned when the document is not attached.
        ErrDocumentNotAttached,

        // ErrDocumentNotDetached is returned when the document is not detached.
        ErrDocumentNotDetached,

        // ErrDocumentRemoved is returned when the document is removed.
        ErrDocumentRemoved,

        // InvalidObjectKey is returned when the object key is invalid.
        ErrInvalidObjectKey,

        // ErrInvalidArgument is returned when the argument is invalid.
        ErrInvalidArgument,
    }
}

@OptIn(ExperimentalContracts::class)
public fun checkYorkieError(value: Boolean, exception: YorkieException) {
    contract {
        returns() implies value
    }
    if (!value) {
        throw exception
    }
}
