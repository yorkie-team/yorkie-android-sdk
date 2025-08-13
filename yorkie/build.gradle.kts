import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    kotlin("android")
    id("jacoco")
    id("maven-publish")
    id("org.jetbrains.dokka")
    id("signing")
    id("build.buf") version "0.9.0"
}

jacoco {
    toolVersion = "0.8.8"
}

tasks.register<JacocoReport>("jacocoDebugTestReport") {
    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.required.set(true)
    }

    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.asFile.get()}/tmp/kotlin-classes/debug") {
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
        fileTree(layout.buildDirectory.asFile.get()) {
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

/*
 * === Enhanced Protocol Buffer Generation with Incremental Build Support ===
 *
 * This configuration optimizes buf generation to only run when necessary:
 *
 * 1. **Incremental Builds**: bufGenerate only runs when proto files or buf config change
 * 2. **Build Cache**: Generated files are cached and reused when possible
 * 3. **Rate Limit Protection**: Avoids unnecessary network calls to buf.build
 *
 * Available tasks:
 * - `./gradlew :yorkie:cleanGeneratedProto` - Clean generated proto files
 * - `./gradlew :yorkie:bufGenerate` - Generate proto files (only if needed)
 */

// Enhanced buf generation with incremental build support
// Only runs when proto files (.proto) or buf configuration files change
tasks.named("bufGenerate") {
    notCompatibleWithConfigurationCache("buf generation requires network access")

    // Configure inputs - proto files and buf configuration
    // This ensures the task only runs when these files change
    inputs.files(fileTree("proto") {
        include("**/*.proto")
    })
    inputs.files("buf.gen.yaml", "buf.work.yaml", "buf.yaml")

    // Configure outputs - generated code directory
    // This enables Gradle's up-to-date checking and build cache
    val generatedDir = "${layout.buildDirectory.asFile.get()}/bufbuild/generated"
    outputs.dir(generatedDir)
    outputs.cacheIf { true }

    doFirst {
        println("Running buf generate - proto files or configuration changed")
    }
}

// Clean generated proto files when proto files change
tasks.register("cleanGeneratedProto") {
    description = "Clean generated proto files"
    group = "build"
    notCompatibleWithConfigurationCache("Deletes files")

    doLast {
        val generatedDir = File(layout.buildDirectory.asFile.get(), "bufbuild/generated")
        if (generatedDir.exists()) {
            generatedDir.deleteRecursively()
            println("Cleaned generated proto files")
        }
    }
}

// Make bufGenerate depend on cleaning when proto files change
tasks.named("bufGenerate") {
    // Only clean and regenerate if proto files actually changed
    inputs.files(fileTree("proto")).skipWhenEmpty()
    mustRunAfter("cleanGeneratedProto")
}

tasks.withType<KotlinCompile> {
    dependsOn("bufGenerate")
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

// Load properties from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
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
        buildConfigField("String", "YORKIE_SERVER_URL", "\"${localProperties.getProperty("YORKIE_SERVER_URL") ?: error("YORKIE_SERVER_URL missing in local.properties")}\"")
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

    sourceSets["main"].java {
        val generatedDir = "${layout.buildDirectory.asFile.get()}/bufbuild/generated"
        srcDirs("${generatedDir}/kotlin", "${generatedDir}/java")
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
