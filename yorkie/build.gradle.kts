import java.util.Properties

plugins {
    alias(libs.plugins.yorkie.android.library)
    alias(libs.plugins.yorkie.android.library.jacoco)
    alias(libs.plugins.yorkie.maven.publish)
    alias(libs.plugins.yorkie.buf.generate)
}

tasks.register<Zip>("stuffZip") {
    archiveBaseName.set("stuff")
    from("src/stuff")
}

// Load properties from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "dev.yorkie"
    version = findProperty("VERSION_NAME").toString()

    defaultConfig {
        buildConfigField("String", "VERSION_NAME", """"$version"""")
        buildConfigField(
            "String",
            "YORKIE_SERVER_URL",
            "\"${localProperties.getProperty("YORKIE_SERVER_URL") ?: error("YORKIE_SERVER_URL missing in local.properties")}\"",
        )
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
}

dependencies {
    api(libs.bundles.rpc)
    implementation(libs.guava)

    implementation(libs.protobuf.javalite)
    compileOnly(libs.protobuf.java)

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.apache.commons.collections)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.gson)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.gson)
    androidTestImplementation(kotlin("test"))
}
