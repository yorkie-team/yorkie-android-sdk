plugins {
    alias(libs.plugins.yorkie.android.library)
}

android {
    namespace = "dev.yorkie.collaborative.editing"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
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
    api(projects.yorkie)

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
}

