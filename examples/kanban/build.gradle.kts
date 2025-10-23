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
    namespace = "com.example.kanbanapp"

    defaultConfig {
        applicationId = "com.example.kanbanapp"
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "YORKIE_SERVER_URL", "\"${localProperties.getProperty("YORKIE_SERVER_URL")?: error("YORKIE_SERVER_URL missing in local.properties")}\"")
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
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons.extended)
}
