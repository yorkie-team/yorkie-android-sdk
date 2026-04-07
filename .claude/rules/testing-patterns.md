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
