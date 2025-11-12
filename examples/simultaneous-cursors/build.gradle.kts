plugins {
    alias(libs.plugins.yorkie.android.application)
    alias(libs.plugins.yorkie.android.application.compose)
}

android {
    namespace = "com.example.simultaneouscursors"

    defaultConfig {
        applicationId = "com.example.simultaneouscursors"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":yorkie"))
    implementation(project(":feature:enter-document-key"))

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

    implementation(libs.timber)

    // Debug
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
