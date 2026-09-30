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

        // ErrAlreadyAttached is returned when a document with the same key is
        // already attached (or being attached) to this client.
        ErrAlreadyAttached("ErrAlreadyAttached"),

        // ErrDocumentSizeExceedsLimit is returned when the document size exceeds the limit.
        ErrDocumentSizeExceedsLimit("ErrDocumentSizeExceedsLimit"),

        // ErrDocumentSchemaValidationFailed is returned when the document schema validation failed.
        ErrDocumentSchemaValidationFailed("ErrDocumentSchemaValidationFailed"),

        // InvalidObjectKey is returned when the object key is invalid.
        ErrInvalidObjectKey("ErrInvalidObjectKey"),

        // ErrInvalidArgument is returned when the argument is invalid.
        ErrInvalidArgument("ErrInvalidArgument"),

        // ErrPermissionDenied is returned when the authorization webhook denies the request.
        ErrPermissionDenied("ErrPermissionDenied"),

        // ErrUnauthenticated is returned when the request does not have valid authentication credentials.
        ErrUnauthenticated("ErrUnauthenticated"),

        // ErrTooManySubscribers is returned when the number of subscribers exceeds the limit.
        ErrTooManySubscribers("ErrTooManySubscribers"),

        // ErrTooManyAttachments is returned when the number of attachments exceeds the limit.
        ErrTooManyAttachments("ErrTooManyAttachments"),

        // ErrEpochMismatch is returned when the document has been compacted
        // and the client's epoch no longer matches the server's epoch.
        ErrEpochMismatch("ErrEpochMismatch"),

        // ErrInvalidServerSeq is returned when a checkpoint's serverSeq exceeds
        // the server's document state. The server checks the seeded epoch
        // FIRST and only returns ErrEpochMismatch once epochs already match
        // (server/packs/pushpull.go:285-320 in yorkie 0.7.20); an envelope
        // written before the client learned the epoch — or a document never
        // compacted before this session — instead hits this code after a
        // compaction or purge (round-2 QA HIGH-1, spec 025).
        ErrInvalidServerSeq("ErrInvalidServerSeq"),

        // ErrSessionNotFound is returned when the server no longer recognizes
        // a channel session_id (e.g. reclaimed after TTL). Treated as
        // "session expired": the next refresh retries as a first call
        // (empty session_id).
        ErrSessionNotFound("ErrSessionNotFound"),
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
