import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.kotlinter) apply true
    alias(libs.plugins.dokka) apply false
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
                exclude {
                    it.file.path.contains("generated/")
                }
            }

            tasks.named<SourceTask>("lintKotlinMain") {
                exclude {
                    it.file.path.contains("generated/")
                }
            }
        }
    }
}
