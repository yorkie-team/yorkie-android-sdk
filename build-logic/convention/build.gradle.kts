plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.dokka.gradlePlugin)

    // OkHttp for uploading to Central Portal
    implementation(libs.okhttp)

    // Buf plugin for protocol buffer generation
    implementation(libs.buf.gradle.plugin)
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
        register("bufGenerateConvention") {
            id = libs.plugins.yorkie.buf.generate.get().pluginId
            implementationClass = "BufGenerateConventionPlugin"
        }
        register("androidLibraryJacocoConvention") {
            id = libs.plugins.yorkie.android.library.jacoco.get().pluginId
            implementationClass = "AndroidLibraryJacocoConventionPlugin"
        }
        register("androidApplicationConvention") {
            id = libs.plugins.yorkie.android.application.asProvider().get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidApplicationComposeConvention") {
            id = libs.plugins.yorkie.android.application.compose.get().pluginId
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
    }
}

