@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "yorkie-android-sdk"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":examples:todomvc")
include(":examples:simultaneous-cursors")
include(":examples:scheduler")
include(":examples:rich-text-editor")
include(":examples:collaborative-edit-demo")
include(":examples:core:common")
include(":examples:feature:enter-document-key")
include(":yorkie")
include(":collaborative-editing")
include(":microbenchmark")
