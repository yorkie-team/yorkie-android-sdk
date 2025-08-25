package dev.yorkie.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

inline fun <reified ResponseType : Any> OkHttpClient.postApi(
    url: String,
    headers: Map<String, String> = emptyMap(),
    requestMap: Map<String, Any>,
    gson: Gson,
): ResponseType {
    val json = gson.toJson(requestMap)
    val body = json.toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url(url)
        .post(body)
        .apply {
            headers.forEach { (key, value) ->
                addHeader(key, value)
            }
        }
        .build()

    newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IllegalStateException(response.body?.string().orEmpty())
        }

        val bodyStr = response.body?.string()
            ?: throw IllegalStateException("Empty body")

        return gson.fromJson(bodyStr, ResponseType::class.java)
    }
}

fun parseError(error: String, gson: Gson): Map<String, Any> {
    val type = object : TypeToken<Map<String, Any>>() {}.type
    return gson.fromJson(error, type)
}
