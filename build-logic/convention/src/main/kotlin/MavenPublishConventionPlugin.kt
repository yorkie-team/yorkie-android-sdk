import com.android.build.gradle.LibraryExtension
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension
import java.net.URLEncoder
import java.util.Base64

class MavenPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("maven-publish")
                apply("signing")
                apply("org.jetbrains.dokka")
            }

            val projectVersion = findProperty("VERSION_NAME")?.toString() ?: "unspecified"

            // Define repository URLs
            val mavenReleasePublishUrl =
                layout.buildDirectory.dir("maven/artifacts").get().toString()
            val mavenSnapshotPublishUrl = "https://central.sonatype.com/repository/maven-snapshots/"

            // Define Sonatype credentials
            val sonatypeUsername = System.getenv("MAVEN_USERNAME").orEmpty()
            val sonatypePassword = System.getenv("MAVEN_PASSWORD").orEmpty()

            // Configure Android library publishing
            extensions.configure<LibraryExtension> {
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                        withJavadocJar()
                    }
                }
            }

            // Configure publishing extension
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "CentralPortal"
                        if (projectVersion.endsWith("-SNAPSHOT")) {
                            setUrl(mavenSnapshotPublishUrl)
                            credentials {
                                username = sonatypeUsername
                                password = sonatypePassword
                            }
                        } else {
                            setUrl(mavenReleasePublishUrl)
                        }
                    }
                }
            }

            afterEvaluate {
                extensions.configure<PublishingExtension> {
                    publications.create<MavenPublication>("release") {
                        from(components.getByName("release"))
                        groupId = project.findProperty("GROUP")?.toString() ?: ""
                        artifactId =
                            project.findProperty("POM_ARTIFACT_ID")?.toString() ?: project.name
                        version = projectVersion

                        pom {
                            name.set(project.findProperty("POM_NAME")?.toString() ?: "")
                            description.set(
                                project.findProperty("POM_DESCRIPTION")?.toString() ?: "",
                            )
                            url.set(project.findProperty("POM_URL")?.toString() ?: "")

                            scm {
                                url.set(project.findProperty("POM_SCM_URL")?.toString() ?: "")
                                connection.set(
                                    project.findProperty("POM_SCM_CONNECTION")?.toString() ?: "",
                                )
                                developerConnection.set(
                                    project.findProperty("POM_SCM_DEV_CONNECTION")?.toString()
                                        ?: "",
                                )
                            }

                            licenses {
                                license {
                                    name.set(
                                        project.findProperty("POM_LICENCE_NAME")?.toString() ?: "",
                                    )
                                    url.set(
                                        project.findProperty("POM_LICENCE_URL")?.toString() ?: "",
                                    )
                                    distribution.set(
                                        project.findProperty("POM_LICENCE_DIST")?.toString() ?: "",
                                    )
                                }
                            }

                            developers {
                                developer {
                                    id.set(
                                        project.findProperty("POM_DEVELOPER_ID")?.toString() ?: "",
                                    )
                                    name.set(
                                        project.findProperty("POM_DEVELOPER_NAME")?.toString()
                                            ?: "",
                                    )
                                    url.set(
                                        project.findProperty("POM_DEVELOPER_URL")?.toString() ?: "",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Configure signing only for publish tasks (not for publishToMavenLocal)
            if (gradle.startParameter.taskNames.any { it.contains("publish") && !it.contains("MavenLocal") }) {
                extensions.configure<SigningExtension> {
                    useInMemoryPgpKeys(
                        System.getenv("PGP_KEY_ID"),
                        System.getenv("PGP_SECRET_KEY"),
                        System.getenv("PGP_PASSWORD"),
                    )
                    sign(extensions.getByType<PublishingExtension>().publications)
                }
            }

            // Package Maven artifacts for Central Portal
            val packageMavenArtifacts = tasks.register<Zip>("packageMavenArtifacts") {
                group = "publish"
                dependsOn("publishReleasePublicationToCentralPortalRepository")
                from(mavenReleasePublishUrl)
                archiveFileName.set("${project.name}-artifacts.zip")
                destinationDirectory.set(layout.buildDirectory)
            }

            // Upload to Sonatype Central Portal
            tasks.register<UploadToCentralPortalTask>("publishToCentralPortal") {
                group = "publish"
                description = "Publishes artifacts to Sonatype Central Portal"
                dependsOn(packageMavenArtifacts)

                bundleFile.set(packageMavenArtifacts.flatMap { it.archiveFile })
                deploymentName.set("${project.name}-$projectVersion")
                username.set(sonatypeUsername)
                password.set(sonatypePassword)
            }
        }
    }
}

abstract class UploadToCentralPortalTask : DefaultTask() {
    @get:Internal
    abstract val bundleFile: RegularFileProperty

    @get:Internal
    abstract val deploymentName: Property<String>

    @get:Internal
    abstract val username: Property<String>

    @get:Internal
    abstract val password: Property<String>

    @TaskAction
    fun upload() {
        val bundle = bundleFile.get().asFile

        // Validate bundle file exists at execution time
        if (!bundle.exists()) {
            error("Bundle file does not exist: ${bundle.absolutePath}")
        }

        val uriBase = "https://central.sonatype.com/api/v1/publisher/upload"
        val publishingType = "USER_MANAGED"
        val encodedName = URLEncoder.encode(deploymentName.get(), Charsets.UTF_8)
        val uri = "$uriBase?name=$encodedName&publishingType=$publishingType"

        val bearerToken = Base64.getEncoder()
            .encodeToString("${username.get()}:${password.get()}".toByteArray())

        println("Uploading to Central Portal: $uri")
        println("Bundle file: ${bundle.absolutePath}")

        val client = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofMinutes(5))
            .writeTimeout(java.time.Duration.ofMinutes(5))
            .build()
        val request = Request.Builder()
            .url(uri)
            .header("Authorization", "Bearer $bearerToken")
            .post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "bundle",
                        bundle.name,
                        bundle.asRequestBody("application/zip".toMediaType()),
                    )
                    .build(),
            )
            .build()

        client.newCall(request).execute().use { response ->
            val statusCode = response.code
            val responseBody = response.body?.string() ?: "No response body"

            println("Upload status code: $statusCode")
            println("Upload result: $responseBody")

            if (statusCode !in 200..299) {
                error("Upload error to Central repository. Status code $statusCode. Response: $responseBody")
            } else {
                println("Successfully uploaded to Central Portal!")
            }
        }
    }
}
