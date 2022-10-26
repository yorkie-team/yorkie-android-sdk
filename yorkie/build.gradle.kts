import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.protobuf

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.protobuf")
    id("com.dicedmelon.gradle.jacoco-android")
}

jacoco {
    toolVersion = "0.8.8"
}

android {
    namespace = "dev.yorkie"
    compileSdk = 33

    defaultConfig {
        minSdk = 23
        targetSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
    jacocoAndroidUnitTestReport {
        excludes = excludes + "**/dev/yorkie/api/v1/**"
        csv.enabled(false)
        xml.enabled(true)
        html.enabled(false)
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${Versions.protobuf}${protocPlatform}"
    }
    plugins {
        id("java") {
            artifact = "io.grpc:protoc-gen-grpc-java:${Versions.grpc}${protocPlatform}"
        }
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${Versions.grpc}${protocPlatform}"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${Versions.grpcKotlin}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("java") {
                    option("lite")
                }
                id("grpc") {
                    option("lite")
                }
                id("grpckt") {
                    option("lite")
                }
            }
            task.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.test.ext:junit-ktx:1.1.3")
    protobuf(project(":yorkie:proto"))

    implementation("io.grpc:grpc-stub:${Versions.grpc}")
    implementation("io.grpc:grpc-protobuf-lite:${Versions.grpc}")
    implementation("io.grpc:grpc-kotlin-stub:${Versions.grpcKotlin}")
    implementation("com.google.protobuf:protobuf-kotlin-lite:${Versions.protobuf}")
    implementation("io.grpc:grpc-android:${Versions.grpc}")
    implementation("io.grpc:grpc-okhttp:${Versions.grpc}")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}")

    implementation("org.apache.commons:commons-collections4:${Versions.apacheCommonCollection}")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}
