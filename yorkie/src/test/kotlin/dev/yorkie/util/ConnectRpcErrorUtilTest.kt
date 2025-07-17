package dev.yorkie.util

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.google.rpc.ErrorInfo
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ConnectRpcErrorUtilTest {
    @Test
    fun `should return not retryable if exception is null`() = runTest {
        assertEquals(
            expected = false,
            actual = handleConnectException(
                exception = null,
                handleError = null,
            ),
        )
    }

    @Test
    fun `should return retryable if error code is canceled`() = runTest {
        assertEquals(
            expected = true,
            actual = handleConnectException(
                exception = mockk<ConnectException>(relaxed = true) {
                    every { code } returns Code.CANCELED
                },
                handleError = null,
            ),
        )
    }

    @Test
    fun `should return retryable if error code is unknown`() = runTest {
        assertEquals(
            expected = true,
            actual = handleConnectException(
                exception = mockk<ConnectException>(relaxed = true) {
                    every { code } returns Code.UNKNOWN
                },
                handleError = null,
            ),
        )
    }

    @Test
    fun `should return retryable if error code is resource exhausted`() = runTest {
        assertEquals(
            expected = true,
            actual = handleConnectException(
                exception = mockk<ConnectException>(relaxed = true) {
                    every { code } returns Code.RESOURCE_EXHAUSTED
                },
                handleError = null,
            ),
        )
    }

    @Test
    fun `should return retryable if error code is unavailable`() = runTest {
        assertEquals(
            expected = true,
            actual = handleConnectException(
                exception = mockk<ConnectException>(relaxed = true) {
                    every { code } returns Code.UNAVAILABLE
                },
                handleError = null,
            ),
        )
    }

    @Test
    fun `should return retryable if error code in metadata is ErrTooManySubscribers`() = runTest {
        val connectException =
            produceConnectException(YorkieException.Code.ErrTooManySubscribers.codeString)
        assertEquals(
            expected = true,
            actual = handleConnectException(
                exception = connectException,
                handleError = null,
            ),
        )
    }

    @Test
    fun `should trigger handle error if error code in metadata is ErrClientNotActivated`() =
        runTest {
            val connectException =
                produceConnectException(YorkieException.Code.ErrClientNotActivated.codeString)
            val handleError: suspend (ConnectException) -> Unit = mockk(relaxed = true)
            assertEquals(
                expected = false,
                actual = handleConnectException(
                    exception = connectException,
                    handleError = handleError,
                ),
            )
            coVerify(exactly = 1) {
                handleError(connectException)
            }
        }

    @Test
    fun `should trigger handle error if error code in metadata is ErrClientNotFound`() = runTest {
        val connectException =
            produceConnectException(YorkieException.Code.ErrClientNotFound.codeString)
        val handleError: suspend (ConnectException) -> Unit = mockk(relaxed = true)
        assertEquals(
            expected = false,
            actual = handleConnectException(
                exception = connectException,
                handleError = handleError,
            ),
        )
        coVerify(exactly = 1) {
            handleError(connectException)
        }
    }

    @Test
    fun `should trigger handle error if error code in metadata is ErrUnauthenticated`() = runTest {
        val connectException =
            produceConnectException(YorkieException.Code.ErrUnauthenticated.codeString)
        val handleError: suspend (ConnectException) -> Unit = mockk(relaxed = true)
        assertEquals(
            expected = false,
            actual = handleConnectException(
                exception = connectException,
                handleError = handleError,
            ),
        )
        coVerify(exactly = 1) {
            handleError(connectException)
        }
    }

    @Test
    fun `should return not retryable if error code in metadata is ErrTooManyAttachments`() =
        runTest {
            val connectException =
                produceConnectException(YorkieException.Code.ErrTooManyAttachments.codeString)
            assertEquals(
                expected = false,
                actual = handleConnectException(
                    exception = connectException,
                    handleError = null,
                ),
            )
        }

    private fun produceConnectException(yorkieErrorCode: String): ConnectException {
        // Create ErrorInfo with unauthenticated error code
        val errorInfo = ErrorInfo.newBuilder()
            .putMetadata("code", yorkieErrorCode)
            .build()

        return mockk<ConnectException>(relaxed = true) {
            every {
                unpackedDetails(ErrorInfo::class)
            } returns listOf(errorInfo)
        }
    }
}
