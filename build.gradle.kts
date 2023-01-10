plugins {
    id("com.android.application") version "7.3.1" apply false
    id("com.android.library") version "7.3.1" apply false
    id("org.jetbrains.kotlin.android") version "1.7.20" apply false
    id("org.jetbrains.kotlin.jvm") version "1.7.20" apply true
    id("com.google.protobuf") version "0.9.1" apply false
    id("org.jmailen.kotlinter") version "3.11.1" apply true
    id("com.dicedmelon.gradle.jacoco-android") version "0.1.5" apply false
    id("androidx.benchmark") version "1.2.0-alpha08" apply false
    id("org.jetbrains.dokka") version "1.7.20" apply false
}

allprojects {
    apply(plugin = "org.jmailen.kotlinter")

    kotlinter {
        version = "0.46.1"
        disabledRules = emptyArray()
    }
}

tasks.check {
    dependsOn("installKotlinterPrePushHook")
}

tasks.lintKotlinMain {
    exclude("com/example/**/generated/*.kt")
}
