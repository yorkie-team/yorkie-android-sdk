plugins {
    alias(libs.plugins.yorkie.android.application)
    alias(libs.plugins.yorkie.android.application.compose)
}

android {
    namespace = "com.example.todomvc"

    defaultConfig {
        applicationId = "com.example.todomvc"
        versionCode = 1
        versionName = "1.0.0"
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
