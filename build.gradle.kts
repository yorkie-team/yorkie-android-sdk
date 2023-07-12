import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    id("com.android.application") version libs.versions.agp apply false
    id("com.android.library") version libs.versions.agp apply false
    id("org.jetbrains.kotlin.android") version "1.8.22" apply false
    id("com.google.protobuf") version "0.9.3" apply false
    id("org.jmailen.kotlinter") version "3.15.0" apply true
    id("org.jetbrains.dokka") version "1.8.20" apply false
    alias(libs.plugins.androidx.benchmark) apply false
}

tasks.withType<KotlinCompile> {
    dependsOn("installKotlinterPrePushHook")
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")

    afterEvaluate {
        if (tasks.names.contains("formatKotlinMain")) {
            tasks.named<SourceTask>("formatKotlinMain") {
                exclude("**/generated/**")
            }

            tasks.named<SourceTask>("lintKotlinMain") {
                exclude("**/generated/**")
            }
        }
    }
}
