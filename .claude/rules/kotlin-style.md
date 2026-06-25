---
paths:
  - "**/*.kt"
---

# Kotlin Style Conventions

Baseline: https://kotlinlang.org/docs/reference/coding-conventions.html

## Naming

This project uses PascalCase for all constant-like declarations. Callers should not need to know
whether `Foo` is an `object`, `enum` entry, `const val`, or top-level `val` — naming is consistent
regardless of implementation.

| Type                            | Convention | Example             |
|---------------------------------|------------|---------------------|
| Constants (`const val`)         | PascalCase | `DefaultKeyName`    |
| Immutable vals (singleton-like) | PascalCase | `StructurallyEqual` |
| Enum values                     | PascalCase | `Status.Idle`       |
| Sealed class objects            | PascalCase | `Result.Success`    |
| Singleton objects               | PascalCase | `ReferenceEqual`    |

```kotlin
// ✅ Do
const val DefaultKeyName = "__defaultKey"
enum class Status { Idle, Busy }

// ❌ Don't
const val DEFAULT_KEY_NAME = "__defaultKey"
enum class Status { IDLE, BUSY }
```

**Exception**: External API constants (e.g., `Typeface.BOLD`, `AnnotationTarget.CLASS`) keep their
original naming — only rename declarations owned by this project.
