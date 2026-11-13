package dev.yorkie.core

import com.connectrpc.Headers
import com.connectrpc.Interceptor
import com.connectrpc.StreamFunction
import com.connectrpc.UnaryFunction
import com.connectrpc.http.HTTPRequest
import com.connectrpc.http.clone
import dev.yorkie.BuildConfig
import kotlinx.coroutines.runBlocking

internal val UserAgentInterceptor = object : Interceptor {

    override fun streamFunction(): StreamFunction {
        return StreamFunction(
            requestFunction = {
                it.clone(headers = headers(it))
            },
        )
    }

    override fun unaryFunction(): UnaryFunction {
        return UnaryFunction(
            requestFunction = {
                it.clone(headers = headers(it))
            },
        )
    }

    private fun headers(request: HTTPRequest): Headers {
        return mapOf(
            "x-yorkie-user-agent" to
                listOf("yorkie-android-sdk/${BuildConfig.VERSION_NAME}"),
        ) + request.headers
    }
}

/**
 * Internal per-request marker read by [authInterceptor] to decide whether a
 * request is a LOOP call (sync-loop push-pull/channel refresh, Watch, a broadcast retry) or a
 * ONE-SHOT call (every other RPC, plus a broadcast's first try) -- see
 * [AuthTokenRefresher.token]. Carried as an ordinary header, not a `Client`-level or
 * `ThreadLocal` flag: the interceptor runs on connect's own IO dispatcher, not the client's
 * single-thread dispatcher, so a shared flag would race across the suspension points between
 * a loop tick deciding to sync and its RPC actually firing. Always stripped below -- never
 * reaches the wire.
 */
internal const val LOOP_RETRY_HEADER = "x-yorkie-internal-loop-retry"

internal fun Client.Options.authInterceptor(
    shouldRefreshTokenProvider: () -> Boolean,
    authTokenRefresher: AuthTokenRefresher,
    refreshTokenCallback: (() -> Unit)? = null,
): Interceptor? {
    if (apiKey == null && fetchAuthToken == null) {
        return null
    }
    return object : Interceptor {
        override fun streamFunction(): StreamFunction {
            return StreamFunction(
                requestFunction = {
                    it.clone(headers = headers(it))
                },
            )
        }

        override fun unaryFunction(): UnaryFunction {
            return UnaryFunction(
                requestFunction = {
                    it.clone(headers = headers(it))
                },
            )
        }

        private fun headers(request: HTTPRequest): Headers {
            return buildMap {
                if (apiKey != null) {
                    put("x-api-key", listOf(apiKey))
                }
                fetchAuthToken?.let { tokenFetcher ->
                    val isLoopRequest = LOOP_RETRY_HEADER in request.headers
                    val token = authTokenRefresher.token(isLoopRequest) {
                        runBlocking { tokenFetcher.invoke(shouldRefreshTokenProvider()) }
                            .also { refreshTokenCallback?.invoke() }
                    }
                    // A loop request inside the backoff window with no token cached yet
                    // (the injector has never once succeeded) sends no auth header at all,
                    // rather than the literal string "null" -- the server then answers its
                    // own 401, which the loop's existing ErrUnauthenticated handling covers.
                    token?.let { put("authorization", listOf(it)) }
                }
                putAll(request.headers - LOOP_RETRY_HEADER)
            }
        }
    }
}
