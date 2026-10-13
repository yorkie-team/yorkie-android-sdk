package dev.yorkie.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the public data-class surface [YorkieException] kept when it was converted from
 * a `data class` to a `public open class` (JS `4251ab7e` #1403) so
 * [ChangeApplyError] can extend it: `equals`/`hashCode`/`toString`/`copy`/`component1`/
 * `component2` behave exactly as the generated data-class members did for a plain
 * [YorkieException], and a [ChangeApplyError] is-a [YorkieException] but never `==` a
 * plain one with the same [YorkieException.code]/[YorkieException.errorMessage]. Also
 * pins [ChangeApplyError]'s own shape (message, fields, `cause`, `withDocKey`).
 */
class YorkieExceptionCompatTest {

    // --- plain YorkieException: data-class-equivalent surface ---

    @Test
    fun `two plain instances with the same code and message are equal`() {
        val a = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        val b = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `instances differing by code or message are not equal`() {
        val base = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        assertNotEquals(base, YorkieException(YorkieException.Code.ErrDocumentNotAttached, "nope"))
        assertNotEquals(base, YorkieException(YorkieException.Code.ErrInvalidArgument, "other"))
    }

    @Test
    fun `equals is reflexive and rejects null and unrelated types`() {
        val a = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        assertEquals(a, a)
        assertFalse(a.equals(null))
        assertFalse(a.equals("nope"))
    }

    @Test
    fun `toString reproduces the data-class text`() {
        val a = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        assertEquals("YorkieException(code=ErrInvalidArgument, errorMessage=nope)", a.toString())
    }

    // R8 stability: a plain
    // YorkieException's toString() takes a dedicated `this::class ==
    // YorkieException::class` branch that returns a hard-coded literal, not
    // `javaClass.simpleName`, so the base class's own name in the text survives R8
    // minification in a consumer app with no keep rule for it.
    //
    // A red run is not constructible in this environment: a JVM unit test never runs R8, so
    // `javaClass.simpleName` for `YorkieException` itself is always the real,
    // unminified "YorkieException" here -- the literal branch and the old
    // `javaClass.simpleName`-only code produce byte-identical output for every
    // instance this test can construct, identical to the assertion already pinned by
    // `toString reproduces the data-class text` above. The divergence this fix
    // guards against only exists once R8 renames the class in a release consumer
    // build, which this suite has no seam to simulate. Pinned by code inspection
    // (reverted this hunk in place, confirmed the test above still passes
    // byte-for-byte -- i.e. genuinely not discriminating here, not hidden) plus
    // this dedicated case asserting the base class takes the literal path.
    @Test
    fun `toString for a plain instance is the R8-stable literal`() {
        val a = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        assertEquals(YorkieException::class, a::class)
        assertEquals("YorkieException(code=ErrInvalidArgument, errorMessage=nope)", a.toString())
    }

    @Test
    fun `copy defaults to the same fields and can override either`() {
        val a = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        val sameCopy = a.copy()
        assertEquals(a, sameCopy)
        assertNotSame(a, sameCopy)

        val codeChanged = a.copy(code = YorkieException.Code.ErrDocumentNotAttached)
        assertEquals(YorkieException.Code.ErrDocumentNotAttached, codeChanged.code)
        assertEquals("nope", codeChanged.errorMessage)

        val messageChanged = a.copy(errorMessage = "other")
        assertEquals(YorkieException.Code.ErrInvalidArgument, messageChanged.code)
        assertEquals("other", messageChanged.errorMessage)
    }

    @Test
    fun `component1 and component2 destructure like a data class`() {
        val a = YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
        val (code, message) = a
        assertEquals(YorkieException.Code.ErrInvalidArgument, code)
        assertEquals("nope", message)
    }

    // --- ChangeApplyError is-a YorkieException, never equal to a plain one ---

    @Test
    fun `a ChangeApplyError is a YorkieException via is-as, not equality`() {
        val changeApplyError: Throwable = ChangeApplyError(
            changeID = "1:ab:1",
            cause = IllegalStateException("boom"),
        )
        assertTrue(changeApplyError is YorkieException)
        assertTrue(changeApplyError is ChangeApplyError)
        val asChangeApplyError = changeApplyError as? ChangeApplyError
        assertEquals("1:ab:1", asChangeApplyError?.changeID)
    }

    @Test
    fun `a ChangeApplyError never equals a plain YorkieException with the same code and message`() {
        val changeApplyError = ChangeApplyError(
            changeID = "1:ab:1",
            cause = IllegalStateException("boom"),
        )
        val plainWithSameCodeAndMessage = YorkieException(
            changeApplyError.code,
            changeApplyError.errorMessage,
        )
        assertNotEquals(changeApplyError, plainWithSameCodeAndMessage)
        assertNotEquals(plainWithSameCodeAndMessage, changeApplyError)
    }

    @Test
    fun `toString prefixes a ChangeApplyError with its own simple name, not YorkieException`() {
        val changeApplyError = ChangeApplyError(
            changeID = "1:ab:1",
            cause = IllegalStateException("boom"),
        )
        assertTrue(changeApplyError.toString().startsWith("ChangeApplyError(code="))
        assertEquals(
            "ChangeApplyError(code=${changeApplyError.code}, " +
                "errorMessage=${changeApplyError.errorMessage})",
            changeApplyError.toString(),
        )
    }

    @Test
    fun `copy on a ChangeApplyError returns a plain YorkieException, never a ChangeApplyError`() {
        val changeApplyError = ChangeApplyError(
            changeID = "1:ab:1",
            cause = IllegalStateException("boom"),
        )
        val copied = changeApplyError.copy()
        assertFalse(copied is ChangeApplyError)
        assertEquals(YorkieException::class, copied::class)
        assertEquals(changeApplyError.code, copied.code)
        assertEquals(changeApplyError.errorMessage, copied.errorMessage)
    }

    // --- ChangeApplyError's own shape ---

    @Test
    fun `message names the change, document and operation in JS order, never more`() {
        val cause = IllegalStateException("boom")
        val error = ChangeApplyError(
            docKey = "d1",
            changeID = "1:ab:1",
            opIndex = 0,
            operation = "SetOperation on 1:ab:0",
            cause = cause,
        )
        assertEquals(YorkieException.Code.ErrChangeApplyFailed, error.code)
        assertEquals("ErrChangeApplyFailed", YorkieException.Code.ErrChangeApplyFailed.codeString)
        assertEquals(
            "failed to apply change 1:ab:1 of document \"d1\" at operation 0 " +
                "(SetOperation on 1:ab:0): boom",
            error.message,
        )
        assertEquals("d1", error.docKey)
        assertEquals("1:ab:1", error.changeID)
        assertEquals(0, error.opIndex)
        assertEquals("SetOperation on 1:ab:0", error.operation)
        assertSame(cause, error.cause)
    }

    @Test
    fun `message omits the document and operation clauses when absent`() {
        val error = ChangeApplyError(changeID = "1:ab:1", cause = IllegalStateException("boom"))
        assertEquals("failed to apply change 1:ab:1: boom", error.message)
        assertNull(error.docKey)
        assertNull(error.opIndex)
        assertNull(error.operation)
    }

    @Test
    fun `withDocKey keeps this when unchanged, else copies with the same other fields`() {
        val cause = IllegalStateException("boom")
        val error = ChangeApplyError(
            docKey = "d1",
            changeID = "1:ab:1",
            opIndex = 0,
            operation = "SetOperation on 1:ab:0",
            cause = cause,
        )
        assertSame(error, error.withDocKey("d1"))

        val renamed = error.withDocKey("d2")
        assertNotSame(error, renamed)
        assertEquals("d2", renamed.docKey)
        assertEquals(error.changeID, renamed.changeID)
        assertEquals(error.opIndex, renamed.opIndex)
        assertEquals(error.operation, renamed.operation)
        assertSame(cause, renamed.cause)
    }

    @Test
    fun `withDocKey on an error with no prior docKey attaches it`() {
        val error = ChangeApplyError(changeID = "1:ab:1", cause = IllegalStateException("boom"))
        val withKey = error.withDocKey("d1")
        assertEquals("d1", withKey.docKey)
        assertTrue(withKey.message?.contains("of document \"d1\"") == true)
    }
}
