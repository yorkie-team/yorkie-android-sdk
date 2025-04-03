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

internal fun Client.Options.authInterceptor(
    shouldRefreshTokenProvider: () -> Boolean,
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
                    val token = runBlocking {
                        tokenFetcher.invoke(shouldRefreshTokenProvider())
                    }
                    refreshTokenCallback?.invoke()
                    put("authorization", listOf(token))
                }
                putAll(request.headers)
            }
        }
    }
}
