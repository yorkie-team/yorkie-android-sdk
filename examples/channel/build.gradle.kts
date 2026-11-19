import dev.yorkie.dsl.implementation

plugins {
    alias(libs.plugins.yorkie.examples.android.application)
    alias(libs.plugins.yorkie.examples.android.application.compose)
}

android {
    namespace = "dev.yorkie.example.channel"

    defaultConfig {
        applicationId = "dev.yorkie.example.channel"
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

    // Core Android dependencies
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(libs.androidx.compose.material)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel)

    // Coroutines
    implementation(libs.kotlinx.coroutines)

    implementation(libs.timber)

    // Debug
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
