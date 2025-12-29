import dev.yorkie.dsl.implementation

plugins {
    alias(libs.plugins.yorkie.examples.android.application)
    alias(libs.plugins.yorkie.examples.android.application.compose)
}

android {
    namespace = "dev.yorkie.example.collaborativeeditdemo"

    defaultConfig {
        applicationId = "dev.yorkie.example.collaborativeeditdemo"
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
    // Only using collaborative-editing module (which exposes yorkie transitively via api)
    implementation(projects.collaborativeEditing)
    implementation(projects.examples.feature.enterDocumentKey)

    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(libs.timber)
}

