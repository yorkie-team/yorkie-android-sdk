---
name: test-writer
description: Writes unit and instrumented tests matching yorkie-android-sdk conventions — English backtick names, MockK, CoroutineRule, JUnit 4, Given-When-Then.
model: sonnet
tools: Glob, Grep, Read, Write, Bash
---

# test-writer

## When to use

- After implementing new CRDT operation or sync logic
- After fixing a bug (regression test)
- When coverage of an existing class needs improvement

## Two test targets

### Unit tests — `yorkie/src/test/kotlin/dev/yorkie/`

JVM only, no emulator or Docker required.

```bash
./gradlew yorkie:testDebugUnitTest
./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.document.crdt.CrdtTreeTest"
```

Focus: CRDT data structures, converters, utility classes.
Use MockK to mock `YorkieService` and coroutine collaborators.

### Instrumented tests — `yorkie/src/androidTest/kotlin/dev/yorkie/`

Require Android emulator AND running Yorkie Docker server:
```bash
docker compose -f docker/docker-compose.yml up --build -d
./gradlew yorkie:connectedDebugAndroidTest
```

Focus: document sync, presence, schema validation, full client lifecycle.
Use `TestUtils`, `ApiUtils`, `JsonTestUtils` helpers.
Always call `client.detachDocument(doc)` and `client.deactivateAsync()` in `@After`.

## Conventions

### Test naming — English backticks

```kotlin
@Test
fun `crdt tree split preserves left and right node references`() { ... }

@Test
fun `document update async applies operation to root`() { ... }

@Test
fun `client attach fails when document is already attached`() { ... }
```

### Given-When-Then structure

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

### CoroutineRule

```kotlin
class DocumentTest {
    @get:Rule
    val coroutineRule = CoroutineRule()

    @Test
    fun `update async applies changes`() = runTest {
        val document = mockk<Document>(relaxed = true)
        coEvery { document.updateAsync(any()) } just Runs

        document.updateAsync { root -> root["key"] = "value" }
        advanceUntilIdle()

        coVerify { document.updateAsync(any()) }
    }
}
```

### MockK patterns

```kotlin
val document = mockk<Document>(relaxed = true)
coEvery { document.updateAsync(any()) } just Runs
coVerify { document.updateAsync(any()) }
coVerify(exactly = 0) { document.updateAsync(any()) }
```

### JUnit 4 only

```kotlin
// ✅ JUnit 4
@Test fun `my test`() { }
@Before fun setUp() { }
@After fun tearDown() { }
@get:Rule val rule = CoroutineRule()

// ❌ Not used
@BeforeEach  // JUnit 5
@ExtendWith  // JUnit 5
```

## What to prioritize

1. CRDT edge cases: empty document, single-char text, zero-length ranges, concurrent edits
2. Client lifecycle: activate → attach → detach → deactivate in success and error paths
3. GC: verify deleted nodes are registered in GC pairs
4. Sync: push-pull round-trip, offline queue draining
