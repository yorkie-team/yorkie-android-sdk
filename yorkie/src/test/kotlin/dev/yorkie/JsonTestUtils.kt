

@file:Suppress("ktlint:standard:filename")

package dev.yorkie

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals

private val gson = Gson()

fun assertJsonContentEquals(expected: String, actual: String) {
    val expectedJson = gson.fromJson(expected, JsonObject::class.java)
    val actualJson = gson.fromJson(actual, JsonObject::class.java)
    assertEquals(expectedJson, actualJson)
}
