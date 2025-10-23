@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
rootProject.name = "Yorkie Android SDK"
include(":examples:kanban", ":examples:texteditor", ":examples:todomvc", ":examples:simultaneous-cursors", ":examples:scheduler", ":examples:rich-text-editor", ":yorkie", ":microbenchmark")
