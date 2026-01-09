import dev.yorkie.dsl.implementation
import dev.yorkie.extensions.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

class ExamplesAndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "yorkie.examples.android.library")
            apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

            dependencies {
                implementation(libs.androidx.navigation.compose)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
