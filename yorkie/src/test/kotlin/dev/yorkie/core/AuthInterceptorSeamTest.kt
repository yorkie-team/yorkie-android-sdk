package dev.yorkie.core

import com.connectrpc.Code
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.http.UnaryHTTPRequest
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.YorkieServiceClient
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Direct seam test for [authInterceptor]: [MockYorkieService]-backed `Client` tests bypass
 * the interceptor entirely (they swap `Client.service` for the mock), so the
 * interceptor-to-[AuthTokenRefresher] wiring -- the loop/one-shot gate and the
 * [LOOP_RETRY_HEADER] strip -- has no other test coverage. Calls [authInterceptor]'s returned
 * [com.connectrpc.Interceptor.unaryFunction]'s `requestFunction` directly against a stub
 * [UnaryHTTPRequest].
 */
class AuthInterceptorSeamTest {

    private fun fakeRequest(extraHeaders: Map<String, List<String>> = emptyMap()) =
        mockk<UnaryHTTPRequest>(relaxed = true) {
            every { headers } returns mapOf("x-shard-key" to listOf("k/v")) + extraHeaders
            // `timeout` is a `Duration?` (Kotlin inline value class) -- mockk's relaxed
            // auto-mocking for it returns the unboxed raw Long, which crashes the real
            // `clone()`'s `timeout: Duration? = this.timeout` default with a
            // ClassCastException. Stub it explicitly instead.
            every { timeout } returns null
        }

    @Test
    fun `a loop request inside the backoff window reuses the cached token, never the injector`() {
        var now = 0L
        val refresher = AuthTokenRefresher(clock = { now }, random = { 0.0 })
        var injectorCalls = 0
        val options = Client.Options(
            apiKey = null,
            fetchAuthToken = {
                injectorCalls++
                if (injectorCalls == 1) "token-1" else throw IllegalStateException("boom")
            },
        )
        val interceptor = checkNotNull(options.authInterceptor({ false }, refresher) {})
        val requestFn = interceptor.unaryFunction().requestFunction

        // 1st loop request succeeds and caches "token-1".
        val first = requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertEquals(listOf("token-1"), first.headers["authorization"])
        assertEquals(1, injectorCalls)

        // 2nd loop request: the injector now throws, arming the backoff window -- the loop
        // still sends the cached token, never the throw itself.
        val second = requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertEquals(listOf("token-1"), second.headers["authorization"])
        assertEquals(2, injectorCalls)

        // 3rd loop request, still inside the window the 2nd call just armed: must NOT call
        // the injector again.
        val third = requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertEquals(listOf("token-1"), third.headers["authorization"])
        assertEquals(
            2,
            injectorCalls,
            "a loop request inside the window must not call the injector",
        )
    }

    @Test
    fun `a one-shot request always calls the injector and its rejection surfaces, unwrapped`() {
        val refresher = AuthTokenRefresher(clock = { 0L }, random = { 0.0 })
        val injectorError = IllegalStateException("token endpoint down")
        val options = Client.Options(apiKey = null, fetchAuthToken = { throw injectorError })
        val interceptor = checkNotNull(options.authInterceptor({ false }, refresher) {})
        val requestFn = interceptor.unaryFunction().requestFunction

        val thrown = assertFailsWith<AuthTokenInjectorException> {
            requestFn(fakeRequest())
        }
        assertEquals(injectorError, thrown.unwrapInjectorError())
    }

    @Test
    fun `a one-shot request calls the injector even while another is in the backoff window`() {
        val refresher = AuthTokenRefresher(clock = { 0L }, random = { 0.0 })
        var injectorCalls = 0
        val options = Client.Options(
            apiKey = null,
            fetchAuthToken = {
                injectorCalls++
                throw IllegalStateException("boom-$injectorCalls")
            },
        )
        val interceptor = checkNotNull(options.authInterceptor({ false }, refresher) {})
        val requestFn = interceptor.unaryFunction().requestFunction

        // A loop request first, so the backoff window is armed (no token ever cached).
        val loopResult = requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertNull(loopResult.headers["authorization"], "no header is sent before any success")
        assertEquals(1, injectorCalls)

        // A one-shot request right after must still call the injector itself.
        assertFailsWith<AuthTokenInjectorException> { requestFn(fakeRequest()) }
        assertEquals(2, injectorCalls, "a one-shot request ignores the backoff window")
    }

    @Test
    fun `the loop marker header is stripped and never reaches the wire`() {
        val refresher = AuthTokenRefresher(clock = { 0L }, random = { 0.0 })
        val options = Client.Options(apiKey = "project-key", fetchAuthToken = null)
        val interceptor = checkNotNull(options.authInterceptor({ false }, refresher) {})
        val requestFn = interceptor.unaryFunction().requestFunction

        val result = requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertFalse(LOOP_RETRY_HEADER in result.headers)
        assertEquals(listOf("project-key"), result.headers["x-api-key"])
    }

    @Test
    fun `refreshTokenCallback fires only when the injector was actually called and succeeded`() {
        var now = 0L
        val refresher = AuthTokenRefresher(clock = { now }, random = { 0.0 })
        var callbackCount = 0
        var injectorCalls = 0
        val options = Client.Options(
            apiKey = null,
            fetchAuthToken = {
                injectorCalls++
                if (injectorCalls == 1) "token" else throw IllegalStateException("boom")
            },
        )
        val interceptor = checkNotNull(
            options.authInterceptor({ false }, refresher) { callbackCount++ },
        )
        val requestFn = interceptor.unaryFunction().requestFunction

        // 1st call succeeds -- the callback fires once.
        requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertEquals(1, callbackCount)

        // 2nd call fails, arming the backoff window -- a throw never reaches the callback.
        requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertEquals(1, callbackCount)

        // 3rd call, inside the window just armed: the injector (and so the callback) must
        // not fire again.
        requestFn(fakeRequest(mapOf(LOOP_RETRY_HEADER to listOf("1"))))
        assertEquals(1, callbackCount)
    }

    /**
     * Every other test in this file calls [authInterceptor]'s returned `requestFunction`
     * directly -- it never crosses actual connect-kotlin plumbing, so the wrapping shape
     * [unwrapInjectorError] depends on (documented on [AuthTokenInjectorException]) is only a
     * source-read claim there. This test builds a REAL [ProtocolClient] -- the same
     * construction [Client]'s own `service` property uses, with a real [ConnectOkHttpClient]
     * -- to prove it at runtime instead.
     *
     * No local HTTP stub backs `host`: a rejecting injector throws from inside the
     * interceptor chain while connect-kotlin is still building the request, strictly BEFORE
     * `ProtocolClient.unary` ever reaches the HTTP client, so no request is ever sent for
     * this scenario regardless of what (if anything) is listening. A stub would add
     * dependency weight (`com.sun.net.httpserver` is unavailable on this module's
     * Android-targeted unit-test compile classpath -- "Unresolved reference: sun" --
     * and OkHttp's `mockwebserver` is not an existing project dependency) for no additional
     * coverage here; `host` only needs to parse as a URI, which this never-dialed address
     * does.
     */
    @Test
    fun `a real connect-kotlin call wraps an interceptor throw, Client unwraps it back`() {
        val host = "http://localhost:1"
        val injectorError = IllegalStateException("token endpoint down")
        val options = Client.Options(apiKey = null, fetchAuthToken = { throw injectorError })

        // 1) Raw connect-kotlin shape, through the REAL interceptor chain: connect-kotlin's
        // own `catch (ex: Exception) { asConnectException(ex) }` in `ProtocolClient.unary`
        // is what wraps the interceptor's throw.
        val rawProtocolClient = ProtocolClient(
            ConnectOkHttpClient(),
            ProtocolClientConfig(
                host = host,
                serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                interceptors = buildList {
                    options.authInterceptor({ false }, AuthTokenRefresher()) {}
                        ?.let { interceptor -> add { interceptor } }
                },
            ),
        )
        val rawResult = runBlocking {
            YorkieServiceClient(rawProtocolClient).activateClient(
                ActivateClientRequest.newBuilder().setClientKey("probe").build(),
                emptyMap(),
            )
        }
        val rawFailure = assertIs<ResponseMessage.Failure<*>>(rawResult)
        assertEquals(Code.UNKNOWN, rawFailure.cause.code)
        val wrapped = assertIs<AuthTokenInjectorException>(rawFailure.cause.cause)
        assertEquals(injectorError, wrapped.cause)

        // 2) A real one-shot `Client` call site, through its own `service` construction,
        // surfaces the injector's own error -- already unwrapped from that same transport
        // wrapper just measured above.
        val client = Client(options = options, host = host)
        try {
            val result = runBlocking { client.activateAsync().await() }
            assertEquals(injectorError, result.exceptionOrNull())
        } finally {
            client.close()
        }
    }
}
