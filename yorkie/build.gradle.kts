import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.protobuf

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.protobuf")
    id("com.dicedmelon.gradle.jacoco-android")
    id("maven-publish")
    id("org.jetbrains.dokka")
    id("signing")
}

jacoco {
    toolVersion = "0.8.8"
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
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("io.grpc:grpc-testing:${Versions.grpc}")

    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation(kotlin("test"))
}
