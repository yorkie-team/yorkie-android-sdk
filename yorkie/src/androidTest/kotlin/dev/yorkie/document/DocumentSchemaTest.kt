package dev.yorkie.document

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.yorkie.core.Client
import dev.yorkie.core.TEST_API_ID
import dev.yorkie.core.TEST_API_PW
import dev.yorkie.core.createClient
import dev.yorkie.core.toDocKey
import dev.yorkie.test.BuildConfig
import dev.yorkie.util.YorkieException
import dev.yorkie.util.parseError
import dev.yorkie.util.postApi
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DocumentSchemaTest {
    private lateinit var client: OkHttpClient
    private lateinit var gson: Gson

    private lateinit var adminToken: String

    private val time = System.currentTimeMillis()

    @Before
    fun setup() {
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        gson = GsonBuilder().create()

        val loginResponse = client.postApi<Map<String, Any>>(
            url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/LogIn",
            requestMap = mapOf(
                "username" to TEST_API_ID,
                "password" to TEST_API_PW,
            ),
            gson = gson,
        )
        adminToken = loginResponse["token"] as String

        client.postApi<Any>(
            url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/CreateSchema",
            headers = mapOf(
                "Authorization" to adminToken,
            ),
            requestMap = mapOf(
                "projectName" to "default",
                "schemaName" to "schema1-$time",
                "schemaVersion" to 1,
                "schemaBody" to "type Document = {title: string;};",
                "rules" to listOf<Map<String, Any>>(
                    mapOf(
                        "path" to "\$.title",
                        "type" to "string",
                    ),
                ),
            ),
            gson = gson,
        )

        client.postApi<Any>(
            url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/CreateSchema",
            headers = mapOf(
                "Authorization" to adminToken,
            ),
            requestMap = mapOf(
                "projectName" to "default",
                "schemaName" to "schema2-$time",
                "schemaVersion" to 1,
                "schemaBody" to "type Document = {title: integer;};",
                "rules" to listOf<Map<String, Any>>(
                    mapOf(
                        "path" to "\$.title",
                        "type" to "integer",
                    ),
                ),
            ),
            gson = gson,
        )
    }

    @Test
    fun can_attach_document_with_schema() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val document = Document(UUID.randomUUID().toString().toDocKey())
            val result = client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "noexist@1",
            ).await()
            assertEquals(
                expected = "noexist 1: schema not found",
                actual = result.exceptionOrNull()?.message.orEmpty(),
            )
        }
    }

    @Test
    fun should_reject_local_update_that_violates_schema() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val document = Document(UUID.randomUUID().toString().toDocKey())
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            val exception = assertThrows(YorkieException::class.java) {
                runBlocking {
                    document.updateAsync { root, _ ->
                        root["title"] = 123
                    }.await()
                }
            }
            assertEquals(
                expected = YorkieException(
                    code = YorkieException.Code.ErrDocumentSchemaValidationFailed,
                    errorMessage = "schema validation failed: Expected string at path \$.title",
                ),
                actual = exception,
            )
            assertEquals(
                expected = "{}",
                actual = document.toJson(),
            )

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )

            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }

    @Test
    fun can_update_schema_with_new_rules_via_UpdateDocument_API() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document1 = Document(documentKey)
            client.attachAsync(
                document = document1,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            document1.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document1.toJson(),
            )

            client.syncAsync(document1).await()
            client.detachAsync(document1).await()

            this@DocumentSchemaTest.client.postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "projectName" to "default",
                    "documentKey" to documentKey.value,
                    "root" to "{\"title\": Int(123)}",
                    "schemaKey" to "schema2-$time@1",
                ),
                gson = gson,
            )

            val document2 = Document(documentKey)
            client.attachAsync(
                document = document2,
                syncMode = Client.SyncMode.Manual,
            ).await()
            assertEquals(
                expected = "{\"title\":123}",
                actual = document2.toJson(),
            )

            client.deactivateAsync().await()
        }
    }

    @Test
    fun should_reject_schema_update_when_document_is_attached() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            val exception = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "{\"title\": Int(123)}",
                        "schemaKey" to "schema2-$time@1",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "document is attached",
                actual = parseError(
                    error = exception.message.orEmpty(),
                    gson = gson,
                )["message"],
            )

            client.deactivateAsync().await()
        }
    }

    @Test
    fun should_reject_schema_update_when_existing_root_violates_new_schema() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )
            client.syncAsync(document).await()
            client.detachAsync(document).await()

            val exception = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "{\"title\": Long(123)}",
                        "schemaKey" to "schema2-$time@1",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "schema validation failed: Expected integer at path \$.title",
                actual = parseError(
                    error = exception.message.orEmpty(),
                    gson = gson,
                )["message"],
            )

            client.deactivateAsync().await()
        }
    }

    @Test
    fun can_detach_schema_via_UpdateDocument_API() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )
            client.syncAsync(document).await()

            // Should fail when document is attached
            val exception = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "",
                        "schemaKey" to "",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "document is attached",
                actual = parseError(
                    error = exception.message.orEmpty(),
                    gson = gson,
                )["message"],
            )

            client.detachAsync(document).await()

            // Should succeed after detach
            this@DocumentSchemaTest.client.postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "projectName" to "default",
                    "documentKey" to documentKey.value,
                    "root" to "",
                    "schemaKey" to "",
                ),
                gson = gson,
            )

            val document2 = Document(documentKey)
            client.attachAsync(
                document = document2,
                syncMode = Client.SyncMode.Manual,
            ).await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document2.toJson(),
            )

            // Should now allow violating updates since schema is detached
            document2.updateAsync { root, _ ->
                root["title"] = 123
            }.await()
            assertEquals(
                expected = "{\"title\":123}",
                actual = document2.toJson(),
            )

            client.deactivateAsync().await()
        }
    }

    @Test
    fun can_attach_schema_via_UpdateDocument_API() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
            ).await()

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )
            client.syncAsync(document).await()

            // Should fail when document is attached
            val exception1 = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "",
                        "schemaKey" to "schema2-$time@1",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "document is attached",
                actual = parseError(
                    error = exception1.message.orEmpty(),
                    gson = gson,
                )["message"],
            )
            client.detachAsync(document).await()

            // Should fail with incompatible schema
            // Should fail when document is attached
            val exception2 = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "",
                        "schemaKey" to "schema2-$time@1",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "schema validation failed: Expected integer at path \$.title",
                actual = parseError(
                    error = exception2.message.orEmpty(),
                    gson = gson,
                )["message"],
            )

            // Should succeed with compatible schema
            this@DocumentSchemaTest.client.postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "projectName" to "default",
                    "documentKey" to documentKey.value,
                    "root" to "",
                    "schemaKey" to "schema1-$time@1",
                ),
                gson = gson,
            )

            val document2 = Document(documentKey)
            client.attachAsync(
                document = document2,
                syncMode = Client.SyncMode.Manual,
            ).await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document2.toJson(),
            )

            // Should now reject violating updates since schema is attached
            val exception = assertThrows(YorkieException::class.java) {
                runBlocking {
                    document2.updateAsync { root, _ ->
                        root["title"] = 123
                    }.await()
                }
            }
            assertEquals(
                expected = YorkieException(
                    code = YorkieException.Code.ErrDocumentSchemaValidationFailed,
                    errorMessage = "schema validation failed: Expected string at path \$.title",
                ),
                actual = exception,
            )

            client.deactivateAsync().await()
        }
    }

    @Test
    fun can_update_schema_only() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )
            client.syncAsync(document).await()
            client.detachAsync(document).await()

            // TODO(chacha912): We can verify schema-only updates work correctly
            // after features like conditional types are implemented in schema-ruleset.
            val exception = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "",
                        "schemaKey" to "schema2-$time@1",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "schema validation failed: Expected integer at path \$.title",
                actual = parseError(
                    error = exception.message.orEmpty(),
                    gson = gson,
                )["message"],
            )
            client.deactivateAsync().await()
        }
    }

    @Test
    fun can_update_root_only() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
            ).await()

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )
            client.syncAsync(document).await()

            this@DocumentSchemaTest.client.postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "projectName" to "default",
                    "documentKey" to documentKey.value,
                    "root" to "{\"title\": Int(123)}",
                    "schemaKey" to "",
                ),
                gson = gson,
            )
            client.detachAsync(document).await()

            val document2 = Document(documentKey)
            client.attachAsync(
                document = document2,
                syncMode = Client.SyncMode.Manual,
            ).await()
            assertEquals(
                expected = "{\"title\":123}",
                actual = document2.toJson(),
            )

            client.deactivateAsync().await()
        }
    }

    @Test
    fun can_update_root_only_when_document_has_attached_schema() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            client.attachAsync(
                document = document,
                syncMode = Client.SyncMode.Manual,
                schema = "schema1-$time@1",
            ).await()

            document.updateAsync { root, _ ->
                root["title"] = "hello"
            }.await()
            assertEquals(
                expected = "{\"title\":\"hello\"}",
                actual = document.toJson(),
            )
            client.syncAsync(document).await()
            client.detachAsync(document).await()

            // Should fail with incompatible root update
            val exception1 = assertThrows(IllegalStateException::class.java) {
                this@DocumentSchemaTest.client.postApi<Any>(
                    url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                    headers = mapOf(
                        "Authorization" to adminToken,
                    ),
                    requestMap = mapOf(
                        "projectName" to "default",
                        "documentKey" to documentKey.value,
                        "root" to "{\"title\": Int(123)}",
                        "schemaKey" to "",
                    ),
                    gson = gson,
                )
            }
            assertEquals(
                expected = "schema validation failed: Expected string at path \$.title",
                actual = parseError(
                    error = exception1.message.orEmpty(),
                    gson = gson,
                )["message"],
            )

            // Should succeed with compatible root update
            this@DocumentSchemaTest.client.postApi<Any>(
                url = "${BuildConfig.YORKIE_SERVER_URL}/yorkie.v1.AdminService/UpdateDocument",
                headers = mapOf(
                    "Authorization" to adminToken,
                ),
                requestMap = mapOf(
                    "projectName" to "default",
                    "documentKey" to documentKey.value,
                    "root" to "{\"title\": \"world\"}",
                    "schemaKey" to "",
                ),
                gson = gson,
            )

            val document2 = Document(documentKey)
            client.attachAsync(
                document = document2,
                syncMode = Client.SyncMode.Manual,
            ).await()
            assertEquals(
                expected = "{\"title\":\"world\"}",
                actual = document2.toJson(),
            )

            // Should still enforce schema validation for new updates
            val exception = assertThrows(YorkieException::class.java) {
                runBlocking {
                    document2.updateAsync { root, _ ->
                        root["title"] = 123
                    }.await()
                }
            }
            assertEquals(
                expected = YorkieException(
                    code = YorkieException.Code.ErrDocumentSchemaValidationFailed,
                    errorMessage = "schema validation failed: Expected string at path \$.title",
                ),
                actual = exception,
            )

            client.deactivateAsync().await()
        }
    }
}
