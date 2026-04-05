# Tasks 2–3: Rules

Creates all 5 files under `.claude/rules/`. Tasks 2 (identical) and 3 (adapted) are combined here since they're all small.

---

## Task 2: Identical rules (kotlin-style, kdoc, changelog)

- [ ] **Step 1: Write `.claude/rules/kotlin-style.md`**

```markdown
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
```

- [ ] **Step 2: Write `.claude/rules/kdoc-rule.md`**

```markdown
---
paths:
  - "**/*.{kt,kts}"
---

# KDoc Style Guide

## Core Rules

| Rule | Description | Example |
|------|-------------|---------|
| Language | English throughout | `Returns the current selection state.` |
| Tone | Declarative, neutral — no polite or passive constructions | ❌ `This method returns` → ✅ `Returns` |
| Endings | Declarative sentence or noun phrase, with period | `Converts upstream operations.` |
| Annotation order | KDoc → annotations → declaration | KDoc must appear above `@Stable`, `@JvmOverloads` |
| Tags | `@param`, `@return`, `@throws` in same declarative style | `@param dispatcher Dispatcher used for Yorkie operations.` |
| Class references | Brackets, not backticks | ❌ `` `Client` `` → ✅ `[Client]` |

## Checklist

- [ ] Written in English
- [ ] No passive or polite constructions ("This returns", "You can use", "Please note")
- [ ] Ends with a period
- [ ] KDoc placed above annotations, not between annotation and declaration
- [ ] Class/function references use `[ClassName]`, not backticks
```

- [ ] **Step 3: Write `.claude/rules/changelog-rule.md`**

```markdown
---
paths:
  - "CHANGELOG.md"
---

# CHANGELOG Rule

- Follow the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.
- Write in English.
- Each entry must include a JIRA ticket reference: `[RTCOLLABPLATFORM-N]`.
- Use noun phrases — no verb sentences. (✅ `CRDT tree node split fix` / ❌ `Fix CRDT tree node split`)
- Breaking changes must use the `**Breaking**:` prefix.
- Use backticks for code references (e.g., `Document`, `JsonTree`).
- One entry per line, starting with `-`.
- Record only the final change relative to `main` — do not include intermediate branch history.

## Example

```markdown
## [Unreleased]

### Added
- [RTCOLLABPLATFORM-42] `JsonTree` node move operation support
- [RTCOLLABPLATFORM-43] `Client.syncMode` configuration at attach time

### Fixed
- [RTCOLLABPLATFORM-44] **Breaking**: `Document.updateAsync` — callback now receives `JsonObject` root directly
```
```

- [ ] **Step 4: Commit**

```bash
git add .claude/rules/kotlin-style.md .claude/rules/kdoc-rule.md .claude/rules/changelog-rule.md
git commit -m "chore: add identical rules — kotlin-style, kdoc, changelog"
```

---

## Task 3: Adapted rules (testing-patterns, git-workflow)

- [ ] **Step 5: Write `.claude/rules/testing-patterns.md`**

```markdown
---
paths:
  - "**/*Test.kt"
  - "**/test/**/*.kt"
  - "**/androidTest/**/*.kt"
---

# Testing Patterns

## Core Rules

| Rule | Description |
|------|-------------|
| Test names | English backticks, descriptive and imperative |
| Assertions | Standard JUnit (`assertEquals`, `assertTrue`, `assertNull`) |
| Structure | Given-When-Then |
| Mocking | MockK (`mockk`, `every`, `verify`, `coEvery`, `coVerify`) |
| JUnit version | JUnit 4 only (`@Test`, `@Before`, `@After`, `@get:Rule`) |
| Coroutine testing | `CoroutineRule` + `runTest` + `advanceUntilIdle()` |
| Debugging code | Remove `println()` and temporary logs before committing |

## Two Test Targets

### Unit tests — `yorkie/src/test/kotlin/dev/yorkie/`

- JVM only — no emulator, no Docker
- Run: `./gradlew yorkie:testDebugUnitTest`
- Run single: `./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.document.crdt.CrdtTreeTest"`
- Cover: CRDT data structures, converters, utility classes

### Instrumented tests — `yorkie/src/androidTest/kotlin/dev/yorkie/`

- Require Yorkie Docker server: `docker compose -f docker/docker-compose.yml up --build -d`
- Run: `./gradlew yorkie:connectedDebugAndroidTest`
- Use helpers: `TestUtils`, `ApiUtils`, `JsonTestUtils`
- Cover: document sync, presence, schema validation, full client lifecycle
- Always detach and deactivate `Client` in `@After`

## Test Name Examples

```kotlin
@Test
fun `document update applies operation to crdt root`() { ... }

@Test
fun `client attach fails when document already attached`() { ... }

@Test
fun `json tree node split preserves parent child references`() { ... }
```

## CoroutineRule Setup

```kotlin
class MyTest {
    @get:Rule
    val coroutineRule = CoroutineRule()

    @Test
    fun `example`() = runTest {
        // test body
        advanceUntilIdle()
    }
}
```

## Given-When-Then Structure

```kotlin
@Test
fun `crdt counter increments value by given delta`() {
    // given
    val counter = CrdtCounter(0L, TimeTicket.InitialTimeTicket)

    // when
    counter.increase(5L)

    // then
    assertEquals(5L, counter.value)
}
```

## MockK Patterns

```kotlin
val document = mockk<Document>(relaxed = true)
coEvery { document.updateAsync(any()) } just Runs
coVerify { document.updateAsync(any()) }
coVerify(exactly = 0) { document.updateAsync(any()) }
```
```

- [ ] **Step 6: Write `.claude/rules/git-workflow.md`**

```markdown
# Git Workflow

## Branching

- Always branch from `develop` (not `main`)
- Branch naming:
  - Feature: `feat/RTCOLLABPLATFORM-{N}-{short-desc}`
  - Bug fix: `fix/RTCOLLABPLATFORM-{N}-{short-desc}`
  - Chore / tooling: `chore/{short-desc}`
- Use `--no-track` when creating branches: `git checkout -b {branch} --no-track origin/develop`
- Never commit directly to `main` or `develop`

## Commits

Format: `{type}: {description}`

| Type | When |
|------|------|
| `feat` | New functionality |
| `fix` | Bug fix |
| `chore` | Tooling, config, dependencies |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | No behaviour change |
| `task` | Migration, cleanup, non-feature work |

- Lowercase after the colon, no period at the end
- Run `./gradlew formatKotlin` before every commit
- Run `./gradlew yorkie:testDebugUnitTest lintKotlin` before pushing

## Pull Requests

- All PRs target `develop`
- Title format: `[RTCOLLABPLATFORM-N] {ticket title}` (if ticket exists), otherwise `{type}: {description}`
- Use `/create-pr` skill to create PRs

## Release Flow

1. Cut `release/vX.X.X` from `develop`
2. Bump version in `gradle.properties`, stabilise (bug fixes only)
3. Run: `./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport`
4. PR `release/vX.X.X` → `main`, title: `release: vX.X.X`
5. Tag: `git tag vX.X.X && git push origin vX.X.X`
6. Merge `main` → `develop` to sync

## Hotfix Flow

1. Branch `hotfix/{desc}` from `main`
2. Fix, test, PR to `main`
3. Tag patch version: `vX.X.{N+1}`
4. PR to `develop` to keep it in sync

## Full workflow reference

See `.claude/reference/git-strategy.md` for diagrams and detailed commands.
```

- [ ] **Step 7: Verify**

```bash
ls .claude/rules/
# Expected: changelog-rule.md  git-workflow.md  kdoc-rule.md  kotlin-style.md  testing-patterns.md
```

- [ ] **Step 8: Commit**

```bash
git add .claude/rules/testing-patterns.md .claude/rules/git-workflow.md
git commit -m "chore: add adapted rules — testing-patterns and git-workflow"
```
