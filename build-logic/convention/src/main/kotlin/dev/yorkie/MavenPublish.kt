package dev.yorkie

import java.net.URLEncoder
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension

internal fun Project.configureMavenPublish() {
    val projectVersion = findProperty("VERSION_NAME")?.toString() ?: "unspecified"

    // Define repository URLs
    val mavenReleasePublishUrl =
        layout.buildDirectory.dir("maven/artifacts").get().toString()
    val mavenSnapshotPublishUrl = "https://central.sonatype.com/repository/maven-snapshots/"

    // Define Sonatype credentials - use providers for configuration cache compatibility
    val sonatypeUsername = providers.environmentVariable("MAVEN_USERNAME").orElse("")
    val sonatypePassword = providers.environmentVariable("MAVEN_PASSWORD").orElse("")

    // Use pluginManager.withPlugin instead of afterEvaluate for configuration cache compatibility
    // This ensures publishing is configured after the android library plugin is applied
    pluginManager.withPlugin("com.android.library") {
        configurePublishing(
            projectVersion = projectVersion,
            mavenReleasePublishUrl = mavenReleasePublishUrl,
            mavenSnapshotPublishUrl = mavenSnapshotPublishUrl,
            sonatypeUsername = sonatypeUsername.get(),
            sonatypePassword = sonatypePassword.get(),
            publishingExtension =
                extensions.findByType(PublishingExtension::class.java) as PublishingExtension,
        )
    }

    configureSigning()

    registerUploadToCentralPortalTask(
        projectVersion = projectVersion,
        mavenReleasePublishUrl = mavenReleasePublishUrl,
        sonatypeUsername = sonatypeUsername.get(),
        sonatypePassword = sonatypePassword.get(),
    )
}

private fun Project.configurePublishing(
    projectVersion: String,
    mavenReleasePublishUrl: String,
    mavenSnapshotPublishUrl: String,
    sonatypeUsername: String,
    sonatypePassword: String,
    publishingExtension: PublishingExtension,
) {
    publishingExtension.apply {
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

        // Use register instead of create for lazy configuration (configuration cache friendly)
        publications.register<MavenPublication>("release") {
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

        // Bind the Android release component to the publication lazily
        // This is configuration cache compatible as it uses configureEach
        components.matching { it.name == "release" }.configureEach {
            publications.named<MavenPublication>("release") {
                from(this@configureEach)
            }
        }
    }
}

private fun Project.configureSigning() {
    // Use providers for environment variables (configuration cache compatible)
    val pgpKeyId = providers.environmentVariable("PGP_KEY_ID")
    val pgpSecretKey = providers.environmentVariable("PGP_SECRET_KEY")
    val pgpPassword = providers.environmentVariable("PGP_PASSWORD")

    extensions.configure<SigningExtension> {
        // Only configure signing if credentials are available
        if (pgpSecretKey.isPresent) {
            useInMemoryPgpKeys(
                pgpKeyId.orNull,
                pgpSecretKey.get(),
                pgpPassword.orNull,
            )
            sign(extensions.getByType<PublishingExtension>().publications)
        }

        // Make signing required only for remote publishing (not MavenLocal)
        // The Callable is evaluated lazily at execution time, making it configuration cache compatible
        setRequired(
            {
                gradle.taskGraph.allTasks.any { task ->
                    task.name.contains("publish") &&
                        task.name.contains("CentralPortal") &&
                        !task.name.contains("MavenLocal")
                }
            },
        )
    }
}

private fun Project.registerUploadToCentralPortalTask(
    projectVersion: String,
    mavenReleasePublishUrl: String,
    sonatypeUsername: String,
    sonatypePassword: String,
) {
    // Package Maven artifacts for Central Portal (release versions only)
    val packageMavenArtifacts = tasks.register<Zip>("packageMavenArtifacts") {
        group = "publish"
        // Only run for non-SNAPSHOT versions (SNAPSHOTs are published directly)
        enabled = !projectVersion.endsWith("-SNAPSHOT")
        onlyIf { !projectVersion.endsWith("-SNAPSHOT") }

        dependsOn("publishReleasePublicationToCentralPortalRepository")
        from(mavenReleasePublishUrl)
        archiveFileName.set("${project.name}-artifacts.zip")
        destinationDirectory.set(layout.buildDirectory)
    }

    // Upload to Sonatype Central Portal (release versions only)
    tasks.register<UploadToCentralPortalTask>("publishToCentralPortal") {
        group = "publish"
        description = "Publishes release artifacts to Sonatype Central Portal. " +
            "For SNAPSHOTs, use publishReleasePublicationToCentralPortalRepository"

        // Only run for non-SNAPSHOT versions
        enabled = !projectVersion.endsWith("-SNAPSHOT")
        onlyIf { !projectVersion.endsWith("-SNAPSHOT") }

        dependsOn(packageMavenArtifacts)

        bundleFile.set(packageMavenArtifacts.flatMap { it.archiveFile })
        deploymentName.set("${project.name}-$projectVersion")
        username.set(sonatypeUsername)
        password.set(sonatypePassword)

        doFirst {
            if (projectVersion.endsWith("-SNAPSHOT")) {
                val message = "publishToCentralPortal is only for release versions. " +
                    "For SNAPSHOTs, use: ./gradlew " +
                    "publishReleasePublicationToCentralPortalRepository"
                throw GradleException(message)
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
                error(
                    "Upload error to Central repository. " +
                        "Status code $statusCode. Response: $responseBody",
                )
            } else {
                println("Successfully uploaded to Central Portal!")
            }
        }
    }
}
