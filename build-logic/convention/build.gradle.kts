import org.gradle.initialization.DependenciesAccessors
import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    `kotlin-dsl`
    alias(libs.plugins.kotlinter)
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.dokka.gradlePlugin)

    // OkHttp for uploading to Central Portal
    implementation(libs.okhttp)

    // Buf plugin for protocol buffer generation
    implementation(libs.buf.gradle.plugin)

    // Ref https://medium.com/@mohammadfallah840/access-to-libs-toml-file-from-convention-plugins-a6571cdc511a
    gradle.serviceOf<DependenciesAccessors>().classes.asFiles.forEach {
        compileOnly(files(it.absolutePath))
    }
}

gradlePlugin {
    plugins {
        register("androidLibraryConvention") {
            id = libs.plugins.yorkie.android.library.asProvider().get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("mavenPublishConvention") {
            id = libs.plugins.yorkie.maven.publish.get().pluginId
            implementationClass = "MavenPublishConventionPlugin"
        }
        register("protocolBufferGenerationConvention") {
            id = libs.plugins.yorkie.protocol.buffer.generation.get().pluginId
            implementationClass = "ProtocolBufferGenerationConventionPlugin"
        }
        register("androidLibraryJacocoConvention") {
            id = libs.plugins.yorkie.android.library.jacoco.get().pluginId
            implementationClass = "AndroidLibraryJacocoConventionPlugin"
        }
        register("examplesAndroidApplicationConvention") {
            id = libs.plugins.yorkie.examples.android.application.asProvider().get().pluginId
            implementationClass = "ExamplesAndroidApplicationConventionPlugin"
        }
        register("examplesAndroidApplicationComposeConvention") {
            id = libs.plugins.yorkie.examples.android.application.compose.get().pluginId
            implementationClass = "ExamplesAndroidApplicationComposeConventionPlugin"
        }
        register("examplesAndroidLibraryConvention") {
            id = libs.plugins.yorkie.examples.android.library.asProvider().get().pluginId
            implementationClass = "ExamplesAndroidLibraryConventionPlugin"
        }
        register("examplesAndroidLibraryComposeConvention") {
            id = libs.plugins.yorkie.examples.android.library.compose.get().pluginId
            implementationClass = "ExamplesAndroidLibraryComposeConventionPlugin"
        }
        register("examplesAndroidFeatureConvention") {
            id = libs.plugins.yorkie.examples.android.feature.get().pluginId
            implementationClass = "ExamplesAndroidFeatureConventionPlugin"
        }
    }
}

