plugins {
    id("com.android.library")
    id("androidx.benchmark")
    kotlin("android")
}

android {
    namespace = "dev.yorkie.microbenchmark"
    compileSdk = 33

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        minSdk = 23
        targetSdk = 33

        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY,UNLOCKED"
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
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.2.0-alpha08")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}")
}
