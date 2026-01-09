package dev.yorkie

import com.android.build.api.variant.AndroidComponentsExtension
import dev.yorkie.extensions.libs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

private val coverageExclusions = listOf(
    "**/dev/yorkie/api/v1/**",
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*Test*.*",
    "android/**/*.*",
)

internal fun Project.configureJacoco(
    androidComponentsExtension: AndroidComponentsExtension<*, *, *>,
) {
    configure<JacocoPluginExtension> {
        toolVersion = libs.versions.jacoco.get().toString()
    }

    tasks.register<JacocoReport>("jacocoDebugTestReport") {
        reports {
            xml.required.set(true)
            csv.required.set(false)
            html.required.set(true)
        }

        classDirectories.setFrom(
            fileTree("${layout.buildDirectory.asFile.get()}/tmp/kotlin-classes/debug") {
                exclude(coverageExclusions)
            },
        )
        sourceDirectories.setFrom("${project.projectDir}/src/main/kotlin")
        executionData.setFrom(
            fileTree(layout.buildDirectory.asFile.get()) {
                include(
                    "jacoco/testDebugUnitTest.exec",
                    "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                    "outputs/code_coverage/debugAndroidTest/connected/**/coverage.ec",
                )
            },
        )
    }
}
