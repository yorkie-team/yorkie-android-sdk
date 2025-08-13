package dev.yorkie.document

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.yorkie.core.Client
import dev.yorkie.core.TEST_API_ID
import dev.yorkie.core.TEST_API_PW
import dev.yorkie.core.createClient
import dev.yorkie.core.toDocKey
import dev.yorkie.document.json.JsonText
import dev.yorkie.test.BuildConfig
import dev.yorkie.util.DataSize
import dev.yorkie.util.YorkieException
import dev.yorkie.util.totalDocSize
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DocumentLimitTest {
    private lateinit var client: OkHttpClient
    private lateinit var gson: Gson

    private lateinit var adminToken: String

    @Before
    fun setup() {
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        gson = GsonBuilder().create()

        val loginResponse = postApi<Map<String, Any>>(
            url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/LogIn",
            requestMap = mapOf(
                "username" to TEST_API_ID,
                "password" to TEST_API_PW,
            ),
        )
        adminToken = loginResponse["token"] as String
    }

    @Test
    fun should_successfully_assign_size_limit_to_document() {
        runBlocking {
            val now = System.currentTimeMillis()
            val createProjectResponse = postApi<Map<String, Any>>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/CreateProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "name" to "doc-size-$now",
                ),
            )
            val projectId = (createProjectResponse["project"] as Map<String, Any>)["id"] as String
            val sizeLimit = 10 * 1024 * 1024

            postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "id" to projectId,
                    "fields" to mapOf(
                        "max_size_per_document" to sizeLimit,
                    ),
                ),
            )

            val projectResponse = postApi<Map<String, Any>>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/GetProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "name" to "doc-size-$now",
                ),
            )
            val project = projectResponse["project"] as Map<String, Any>
            assertEquals(
                expected = sizeLimit.toDouble(),
                actual = project["maxSizePerDocument"] as Double,
            )

            val client = createClient(
                options = Client.Options(
                    apiKey = project["publicKey"] as String,
                ),
            )
            client.activateAsync().await()

            val document = Document(UUID.randomUUID().toString().toDocKey())
            client.attachAsync(document).await()

            assertEquals(
                expected = sizeLimit,
                actual = document.getMaxSizePerDocument(),
            )

            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }

    @Test
    fun should_reject_local_update_that_exceeds_document_size_limit() {
        runBlocking {
            val now = System.currentTimeMillis()
            val createProjectResponse = postApi<Map<String, Any>>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/CreateProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "name" to "doc-size-$now",
                ),
            )
            val project = createProjectResponse["project"] as Map<String, Any>
            val projectId = project["id"] as String
            val sizeLimit = 76

            postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "id" to projectId,
                    "fields" to mapOf(
                        "max_size_per_document" to sizeLimit,
                    ),
                ),
            )

            val client = createClient(
                options = Client.Options(
                    apiKey = project["publicKey"] as String,
                ),
            )
            client.activateAsync().await()

            val document = Document(UUID.randomUUID().toString().toDocKey())
            client.attachAsync(document).await()

            document.updateAsync { root, _ ->
                root.setNewText("text")
            }.await()

            assertEquals(
                expected = DataSize(
                    data = 0,
                    meta = 48,
                ),
                actual = document.getDocSize().live,
            )
            assertEquals(
                expected = document.clone?.root?.docSize,
                actual = document.getDocSize(),
            )

            val exception = assertThrows(YorkieException::class.java) {
                runBlocking {
                    document.updateAsync { root, _ ->
                        val text = root.getAs<JsonText>("text")
                        text.edit(0, 0, "helloworld")
                    }.await()
                }
            }

            assertEquals(
                expected = YorkieException(
                    code = YorkieException.Code.ErrDocumentSizeExceedsLimit,
                    errorMessage = "document size exceeded: 92 > 76",
                ),
                actual = exception,
            )

            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }

    @Test
    fun should_allow_remote_updates_even_if_they_exceed_document_size_limit() {
        runBlocking {
            val now = System.currentTimeMillis()
            val createProjectResponse = postApi<Map<String, Any>>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/CreateProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "name" to "doc-size-$now",
                ),
            )
            val project = createProjectResponse["project"] as Map<String, Any>
            val projectId = project["id"] as String
            val sizeLimit = 76

            val documentKey = UUID.randomUUID().toString().toDocKey()

            postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateProject",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "id" to projectId,
                    "fields" to mapOf(
                        "max_size_per_document" to sizeLimit,
                    ),
                ),
            )

            val client1 = createClient(
                options = Client.Options(
                    apiKey = project["publicKey"] as String,
                ),
            )
            client1.activateAsync().await()

            val document1 = Document(documentKey)
            client1.attachAsync(document1).await()

            val client2 = createClient(
                options = Client.Options(
                    apiKey = project["publicKey"] as String,
                ),
            )
            client2.activateAsync().await()

            val document2 = Document(documentKey)
            client2.attachAsync(document2).await()

            document1.updateAsync { root, _ ->
                root.setNewText("text")
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(
                expected = 48,
                actual = totalDocSize(document1.getDocSize()),
            )

            document1.updateAsync { root, _ ->
                val text = root.getAs<JsonText>("text")
                text.edit(0, 0, "aa")
            }.await()
            assertEquals(
                expected = DataSize(
                    data = 4,
                    meta = 72,
                ),
                actual = document1.getDocSize().live,
            )
            client1.syncAsync().await()

            document2.updateAsync { root, _ ->
                val text = root.getAs<JsonText>("text")
                text.edit(0, 0, "a")
            }.await()
            assertEquals(
                expected = DataSize(
                    data = 2,
                    meta = 72,
                ),
                actual = document2.getDocSize().live,
            )
            client2.syncAsync().await()
            // Pulls changes - should succeed despite exceeding limit
            assertEquals(
                expected = DataSize(
                    data = 6,
                    meta = 96,
                ),
                actual = document2.getDocSize().live,
            )

            client1.syncAsync().await()
            // Pulls changes - should succeed despite exceeding limit
            assertEquals(
                expected = DataSize(
                    data = 6,
                    meta = 96,
                ),
                actual = document1.getDocSize().live,
            )

            val exception = assertThrows(YorkieException::class.java) {
                runBlocking {
                    document1.updateAsync { root, _ ->
                        val text = root.getAs<JsonText>("text")
                        text.edit(0, 0, "a")
                    }.await()
                }
            }

            assertEquals(
                expected = YorkieException(
                    code = YorkieException.Code.ErrDocumentSizeExceedsLimit,
                    errorMessage = "document size exceeded: 128 > 76",
                ),
                actual = exception,
            )

            client1.detachAsync(document1).await()
            client1.deactivateAsync().await()
            client2.detachAsync(document2).await()
            client2.deactivateAsync().await()
        }
    }

    private inline fun <reified ResponseType : Any> postApi(
        url: String,
        headers: Map<String, String> = emptyMap(),
        requestMap: Map<String, Any>,
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

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }

            val bodyStr = response.body?.string()
                ?: throw IllegalStateException("Empty body")

            return gson.fromJson(bodyStr, ResponseType::class.java)
        }
    }
}
