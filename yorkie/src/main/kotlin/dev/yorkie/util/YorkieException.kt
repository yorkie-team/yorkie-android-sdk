package dev.yorkie.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * `YorkieError` is an error returned by a Yorkie operation.
 */
public data class YorkieException(
    public val code: Code,
    public val errorMessage: String,
) : RuntimeException(errorMessage) {
    public enum class Code(val codeString: String) {
        // Ok is returned when the operation completed successfully.
        Ok("ok"),

        // ErrClientNotActivated is returned when the client is not active.
        ErrClientNotActivated("ErrClientNotActivated"),

        // ErrClientNotFound is returned when the client is not found.
        ErrClientNotFound("ErrClientNotFound"),

        // ErrUnimplemented is returned when the operation is not implemented.
        ErrUnimplemented("ErrUnimplemented"),

        // Unsupported is returned when the operation is not supported.
        Unsupported("Unsupported"),

        // ErrDocumentNotAttached is returned when the document is not attached.
        ErrDocumentNotAttached("ErrDocumentNotAttached"),

        // ErrDocumentNotDetached is returned when the document is not detached.
        ErrDocumentNotDetached("ErrDocumentNotDetached"),

        // ErrDocumentRemoved is returned when the document is removed.
        ErrDocumentRemoved("ErrDocumentRemoved"),

        // InvalidObjectKey is returned when the object key is invalid.
        ErrInvalidObjectKey("ErrInvalidObjectKey"),

        // ErrInvalidArgument is returned when the argument is invalid.
        ErrInvalidArgument("ErrInvalidArgument");
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
