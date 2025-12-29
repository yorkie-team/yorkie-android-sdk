import dev.yorkie.dsl.implementation

plugins {
    alias(libs.plugins.yorkie.examples.android.application)
    alias(libs.plugins.yorkie.examples.android.application.compose)
}

android {
    namespace = "dev.yorkie.example.todomvc"

    defaultConfig {
        applicationId = "dev.yorkie.example.todomvc"
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
    implementation(projects.yorkie)
    implementation(projects.examples.feature.enterDocumentKey)

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
    implementation(libs.timber)
}
