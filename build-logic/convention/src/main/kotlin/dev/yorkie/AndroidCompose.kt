package dev.yorkie

import com.android.build.api.dsl.CommonExtension
import dev.yorkie.dsl.androidTestImplementation
import dev.yorkie.dsl.debugImplementation
import dev.yorkie.dsl.implementation
import dev.yorkie.extensions.libs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Configure Compose-specific options
 */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        buildFeatures {
            compose = true
        }

        composeOptions {
            kotlinCompilerExtensionVersion =
                libs.versions.androidxComposeCompiler.get().toString()
        }

        dependencies {
            val bom = libs.androidx.compose.bom
            implementation(platform(bom))
            androidTestImplementation(platform(bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.tooling.preview)
            debugImplementation(libs.androidx.compose.ui.tooling)
            debugImplementation(libs.androidx.compose.ui.test.manifest)
        }
    }
}
