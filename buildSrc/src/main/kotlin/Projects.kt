import org.gradle.api.Project

val Project.protocPlatform: String
    get() = findProperty("protoc_platform")?.toString().orEmpty()
