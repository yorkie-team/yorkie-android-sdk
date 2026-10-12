package dev.yorkie.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * `YorkieError` is an error returned by a Yorkie operation.
 *
 * This was a `data class` until [ChangeApplyError] (JS `4251ab7e`, #1403) needed to
 * extend it: a `data class` cannot be a supertype in Kotlin, so this is now a
 * `public open class` with the exact same public surface hand-written back in —
 * `equals`/`hashCode`/`toString` reproduce the data-class text/semantics, `copy` and
 * `component1`/`component2` behave the same way for a plain [YorkieException].
 * [equals] additionally compares the runtime class, so a [ChangeApplyError] is never
 * equal to a plain [YorkieException] with the same [code] and [errorMessage]. [copy]
 * always returns a plain [YorkieException] — even when called on a subclass — because
 * a subclass such as [ChangeApplyError] carries additional state (`cause`, `changeID`,
 * ...) that a generic copy cannot reconstruct; this is a deliberate, documented
 * divergence from a data class's generated `copy`. Pinned by `YorkieExceptionCompatTest`.
 *
 * Discriminate a caught [YorkieException] by `is ChangeApplyError`, not by [code]: a
 * future error type could reuse a [Code] value, but the runtime type is exact.
 */
public open class YorkieException(
    public val code: Code,
    public val errorMessage: String,
) : RuntimeException(errorMessage) {

    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as YorkieException
        if (code != other.code) return false
        if (errorMessage != other.errorMessage) return false
        return true
    }

    final override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + errorMessage.hashCode()
        return result
    }

    /**
     * Reproduces the data-class text `YorkieException(code=…, errorMessage=…)` for a
     * plain [YorkieException] — a hard-coded literal, NOT `javaClass.simpleName`, so
     * the base class's own name stays byte-identical to the pre-D1 data-class output
     * even under R8 minification in a consumer app with no keep rule for it (harness
     * QA + Sonnet cross-judge LOW-2, spec 034 round-1 review: `javaClass.simpleName`
     * alone is not R8-stable). A subclass — e.g.
     * `ChangeApplyError(code=…, errorMessage=…)` — still uses
     * [Any.javaClass]/[Class.getSimpleName], so its OWN name can be obfuscated by R8 in
     * a consumer app without a keep rule for that subclass; a consumer-rules follow-up
     * (e.g. a `-keep` entry, or extending the literal approach to subclasses) is
     * intentionally not added here, out of this fix's scope.
     */
    final override fun toString(): String {
        return if (this::class == YorkieException::class) {
            "YorkieException(code=$code, errorMessage=$errorMessage)"
        } else {
            "${javaClass.simpleName}(code=$code, errorMessage=$errorMessage)"
        }
    }

    /**
     * Returns a plain [YorkieException] with the given [code] and [errorMessage],
     * each defaulting to this instance's own value. Unlike a data class's generated
     * `copy`, this always constructs the base [YorkieException] type, even when called
     * on a subclass such as [ChangeApplyError] (documented divergence, see the class
     * KDoc above).
     */
    public fun copy(
        code: Code = this.code,
        errorMessage: String = this.errorMessage,
    ): YorkieException = YorkieException(code, errorMessage)

    public operator fun component1(): Code = code

    public operator fun component2(): String = errorMessage

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

        // ErrDocumentOpenElsewhere is returned when an offline-persistence attach
        // finds the document already open in another session, so a consumer can fall
        // back to a non-persisting client on exactly this condition without matching
        // on message text.
        ErrDocumentOpenElsewhere("ErrDocumentOpenElsewhere"),

        // ErrChangeApplyFailed is returned when a change cannot be applied to a
        // document. The checkpoint only advances after the changes in a pack have
        // been applied, so the server redelivers a pack whose change throws: the
        // document stops making progress until the cause is fixed. This code names
        // that condition so it is diagnosable instead of surfacing as whatever the
        // failing operation happened to throw.
        ErrChangeApplyFailed("ErrChangeApplyFailed"),
    }
}

/**
 * Builds the [ChangeApplyError] message, ported verbatim from JS `error.ts`
 * `ChangeApplyError`'s constructor (`4251ab7e`, #1403): `failed to apply change
 * <changeID>[ of document "<docKey>"][ at operation <opIndex> (<operation>)]:
 * <cause message>`. A standalone function (not a method) because it must be callable
 * as a [YorkieException] constructor argument, before `this` exists.
 */
private fun changeApplyErrorMessage(
    docKey: String?,
    changeID: String,
    opIndex: Int?,
    operation: String?,
    cause: Throwable?,
): String {
    val where = docKey?.let { " of document \"$it\"" }.orEmpty()
    val which = opIndex?.let { " at operation $it ($operation)" }.orEmpty()
    val reason = cause?.let { it.message ?: it.toString() } ?: "unknown cause"
    return "failed to apply change $changeID$where$which: $reason"
}

/**
 * `ChangeApplyError` is thrown when a change cannot be applied to a document
 * (JS `error.ts` `ChangeApplyError`, `4251ab7e`, #1403).
 *
 * It names the document, the change and the operation that failed, and keeps the
 * original error reachable as [Throwable.cause] (via [initCause], the standard JVM
 * cause chain — JS re-declares `cause` as its own field since it has no such chain).
 * Without it the only signal is whatever the operation threw, which says nothing
 * about which change is stuck — and because the checkpoint does not advance past a
 * change that throws, the server keeps redelivering it.
 *
 * [operation] names the operation by its type and the target element's ticket ONLY
 * — it MUST NOT carry the operation's payload (plaintext document content). This
 * means [errorMessage] (and so [Throwable.message]/[toString]) never carries the
 * operation's payload either — **but it DOES splice in the original [cause]'s own
 * message** (`cause.message ?: cause.toString()`, [changeApplyErrorMessage]), which
 * can carry arbitrary content a lower layer put there (e.g. a collection's "requested
 * value: ..." text). `Document.applyChangePack`'s stuck-checkpoint log line is
 * therefore metadata-only on purpose: it logs the failing exception's class name, not
 * this message — callers that log or surface a caught [ChangeApplyError]'s own
 * message should be aware it is not payload-free the way [operation] alone is.
 */
public class ChangeApplyError(
    public val docKey: String? = null,
    public val changeID: String,
    public val opIndex: Int? = null,
    public val operation: String? = null,
    cause: Throwable?,
) : YorkieException(
    Code.ErrChangeApplyFailed,
    changeApplyErrorMessage(docKey, changeID, opIndex, operation, cause),
) {

    init {
        initCause(cause)
    }

    /**
     * Returns this error named with the given [docKey]. The change knows which
     * operation failed but not which document it belongs to, so the document adds
     * its key as the error passes through (`Document.applyChanges`'s outer catch).
     */
    public fun withDocKey(docKey: String): ChangeApplyError {
        if (this.docKey == docKey) {
            return this
        }
        return ChangeApplyError(
            docKey = docKey,
            changeID = changeID,
            opIndex = opIndex,
            operation = operation,
            cause = cause,
        )
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
