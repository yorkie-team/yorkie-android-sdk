import java.util.Properties

plugins {
    alias(libs.plugins.yorkie.android.application)
    alias(libs.plugins.yorkie.android.application.compose)
}

// Load properties from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.simultaneouscursors"

    defaultConfig {
        applicationId = "com.example.simultaneouscursors"
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "YORKIE_SERVER_URL", "\"${localProperties.getProperty("YORKIE_SERVER_URL") ?: "http://localhost:8080"}\"")
        buildConfigField("String", "YORKIE_API_KEY", "\"${localProperties.getProperty("YORKIE_API_KEY").orEmpty()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":yorkie"))

    // Core Android dependencies
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons.extended)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel)

    // Coroutines
    implementation(libs.kotlinx.coroutines)

    // Gson
    implementation(libs.gson)

    // Debug
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
