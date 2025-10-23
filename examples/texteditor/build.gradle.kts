import java.util.Properties

plugins {
    alias(libs.plugins.yorkie.android.application)
}

// Load properties from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.texteditor"

    defaultConfig {
        applicationId = "com.example.texteditor"
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "YORKIE_SERVER_URL", "\"${localProperties.getProperty("YORKIE_SERVER_URL") ?: error("YORKIE_SERVER_URL missing in local.properties")}\"")
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":yorkie"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.material)
    implementation(libs.gson)
}
