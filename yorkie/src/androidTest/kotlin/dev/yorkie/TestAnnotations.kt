@file:Suppress("ktlint:standard:filename")

package dev.yorkie

/**
 * Annotation to prevent TooManyRequestsException in JsonTree-related tests
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class TreeTest
