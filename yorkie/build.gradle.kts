import com.google.protobuf.gradle.id

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.protobuf")
    id("jacoco")
    id("maven-publish")
    id("org.jetbrains.dokka")
    id("signing")
}

jacoco {
    toolVersion = "0.8.8"
}

tasks.register<JacocoReport>("jacocoDebugTestReport") {
    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.required.set(false)
    }

    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") {
            exclude(
                "**/dev/yorkie/api/v1/**",
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.*",
                "**/Manifest*.*",
                "**/*Test*.*",
                "android/**/*.*",
            )
        },
    )
    sourceDirectories.setFrom("${project.projectDir}/src/main/kotlin")
    executionData.setFrom(
        fileTree(project.buildDir) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "outputs/code_coverage/debugAndroidTest/connected/**/coverage.ec",
            )
        },
    )
}

tasks.register<Zip>("stuffZip") {
    archiveBaseName.set("stuff")
    from("src/stuff")
}

signing {
    useInMemoryPgpKeys(
        System.getenv("PGP_KEY_ID"),
        System.getenv("PGP_SECRET_KEY"),
        System.getenv("PGP_PASSWORD"),
    )
    sign(tasks["stuffZip"])
    sign(publishing.publications)
}

android {
    namespace = "dev.yorkie"
    version = findProperty("VERSION_NAME").toString()
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "VERSION_NAME", """"$version"""")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "OSSRH"
                    setUrl("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    credentials {
                        username = System.getenv("MAVEN_USERNAME")
                        password = System.getenv("MAVEN_PASSWORD")
                    }
                }
            }
            afterEvaluate {
                publications.create<MavenPublication>("release") {
                    from(components["release"])
                    groupId = findProperty("GROUP").toString()
                    artifactId = property("POM_ARTIFACT_ID").toString()
                    version = findProperty("VERSION_NAME").toString()
                    pom {
                        name.set(findProperty("POM_NAME").toString())
                        description.set(findProperty("POM_DESCRIPTION").toString())
                        url.set(findProperty("POM_URL").toString())
                        scm {
                            url.set(findProperty("POM_SCM_URL").toString())
                            connection.set(findProperty("POM_SCM_CONNECTION").toString())
                            developerConnection.set(findProperty("POM_SCM_DEV_CONNECTION").toString())
                        }
                        licenses {
                            license {
                                name.set(findProperty("POM_LICENCE_NAME").toString())
                                url.set(findProperty("POM_LICENCE_URL").toString())
                                distribution.set(findProperty("POM_LICENCE_DIST").toString())
                            }
                        }
                        developers {
                            developer {
                                id.set(findProperty("POM_DEVELOPER_ID").toString())
                                name.set(findProperty("POM_DEVELOPER_NAME").toString())
                                url.set(findProperty("POM_DEVELOPER_URL").toString())
                            }
                        }
                    }
                }
            }
        }
    }
}

protobuf {
    val protocPlatform = findProperty("protoc_platform")?.toString().orEmpty()

    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}${protocPlatform}"
    }
    plugins {
        val protocJava =
            "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.asProvider().get()}${protocPlatform}"
        id("java") {
            artifact = protocJava
        }
        id("grpc") {
            artifact = protocJava
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpc.kotlin.get()}:jdk8@jar"
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
    protobuf(project(":yorkie:proto"))

    api(libs.bundles.grpc)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.apache.commons.collections)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.gson)
    testImplementation(libs.grpc.testing)
    testImplementation(libs.grpc.inprocess)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.gson)
    androidTestImplementation(kotlin("test"))
}
