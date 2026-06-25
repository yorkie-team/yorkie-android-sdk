package dev.yorkie.dsl

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.add

fun DependencyHandler.dependAs(
    configuration: String,
    notation: Any,
    dependencyConfiguration: (ExternalModuleDependency.() -> Unit)? = null,
): Dependency? = dependencyConfiguration?.let {
    if (notation is String) {
        add(configuration, notation, dependencyConfiguration)
    } else {
        add(configuration, notation)
    }
} ?: add(configuration, notation)

fun DependencyHandler.implementation(
    notation: Any,
    dependencyConfiguration: (ExternalModuleDependency.() -> Unit)? = null,
): Dependency? = dependAs("implementation", notation, dependencyConfiguration)

fun DependencyHandler.api(
    notation: Any,
    dependencyConfiguration: (ExternalModuleDependency.() -> Unit)? = null,
): Dependency? = dependAs("api", notation, dependencyConfiguration)

fun DependencyHandler.debugImplementation(
    notation: Any,
    dependencyConfiguration: (ExternalModuleDependency.() -> Unit)? = null,
): Dependency? = dependAs("debugImplementation", notation, dependencyConfiguration)

fun DependencyHandler.androidTestImplementation(
    notation: Any,
    dependencyConfiguration: (ExternalModuleDependency.() -> Unit)? = null,
): Dependency? = dependAs("androidTestImplementation", notation, dependencyConfiguration)

fun DependencyHandler.testImplementation(
    notation: Any,
    dependencyConfiguration: (ExternalModuleDependency.() -> Unit)? = null,
): Dependency? = dependAs("testImplementation", notation, dependencyConfiguration)
