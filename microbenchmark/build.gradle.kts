@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    id("com.android.library")
    kotlin("android")
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "dev.yorkie.microbenchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,LOW-BATTERY,UNLOCKED"
    }

    testBuildType = "release"

    buildTypes {
        debug {
            // Since isDebuggable can"t be modified by gradle for library modules,
            // it must be done in a manifest - see src/androidTest/AndroidManifest.xml
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "benchmark-proguard-rules.pro",
            )
        }
        release {
            isDefault = true
        }
    }
}

dependencies {
    androidTestImplementation(project(":yorkie"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.benchmark)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
