import dev.yorkie.dsl.implementation

plugins {
    alias(libs.plugins.yorkie.examples.android.application)
    alias(libs.plugins.yorkie.examples.android.application.compose)
}

android {
    namespace = "dev.yorkie.example.simultaneouscursors"

    defaultConfig {
        applicationId = "dev.yorkie.example.simultaneouscursors"
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
    implementation(projects.yorkie)
    implementation(projects.examples.feature.enterDocumentKey)

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
