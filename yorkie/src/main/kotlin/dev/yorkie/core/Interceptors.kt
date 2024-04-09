package dev.yorkie.core

import com.connectrpc.Headers
import com.connectrpc.Interceptor
import com.connectrpc.StreamFunction
import com.connectrpc.UnaryFunction
import com.connectrpc.http.HTTPRequest
import com.connectrpc.http.clone
import dev.yorkie.BuildConfig

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

internal fun Client.Options.authInterceptor(): Interceptor? {
    if (apiKey == null && token == null) {
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
                if (token != null) {
                    put("authorization", listOf(token))
                }
                putAll(request.headers)
            }
        }
    }
}
