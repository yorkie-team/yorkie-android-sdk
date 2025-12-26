package dev.yorkie.extensions

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.accessors.runtime.extensionOf

internal val Project.libs
    get(): LibrariesForLibs = extensionOf(this, "libs") as LibrariesForLibs
