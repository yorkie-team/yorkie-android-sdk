package com.google.protobuf

/**
 * Stub annotation for protobuf-generated files.
 * The buf plugin generates @com.google.protobuf.Generated
 * but this annotation doesn't exist in the protobuf library.
 * This annotation is compatible with both Kotlin (@file annotation)
 * and Java (class annotation).
 */
@Target(
    AnnotationTarget.FILE,
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Generated
