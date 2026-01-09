import com.android.build.api.dsl.ApplicationExtension
import dev.yorkie.configureKotlinAndroid
import dev.yorkie.dsl.implementation
import dev.yorkie.extensions.libs
import java.util.Properties
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class ExamplesAndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            apply(plugin = "org.jetbrains.kotlin.android")
            apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)

                defaultConfig {
                    targetSdk = 36

                    // Load properties from local.properties
                    val localProperties = Properties()
                    val localPropertiesFile = rootProject.file("local.properties")
                    if (localPropertiesFile.exists()) {
                        localProperties.load(localPropertiesFile.inputStream())
                    }

                    buildConfigField(
                        "String",
                        "YORKIE_SERVER_URL",
                        "\"${localProperties.getProperty("YORKIE_SERVER_URL").orEmpty()}\"",
                    )
                    buildConfigField(
                        "String",
                        "YORKIE_API_KEY",
                        "\"${localProperties.getProperty("YORKIE_API_KEY").orEmpty()}\"",
                    )
                }
            }

            dependencies {
                implementation(project(":examples:core:common"))
                implementation(libs.androidx.navigation.compose)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
