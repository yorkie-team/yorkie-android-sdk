# Tree Merge/Split — Expose Public API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose existing internal `splitByPath()` and `mergeByPath()` methods as public API on `JsonTree`, matching JS SDK v0.6.36.

**Architecture:** Both methods already exist and work correctly as package-private. We add `public` visibility, KDoc documentation, and comprehensive instrumented tests covering single-client and concurrent scenarios.

**Tech Stack:** Kotlin, Android instrumented tests, JUnit 4, Yorkie Docker server

---

### Task 1: Make `splitByPath` and `mergeByPath` public with KDoc

**Files:**
- Modify: `yorkie/src/main/kotlin/dev/yorkie/document/json/JsonTree.kt:189-250`

- [ ] **Step 1: Add `public` modifier and KDoc to `splitByPath`**

In `JsonTree.kt`, replace lines 189-192:

```kotlin
    /**
     * `splitByPath` splits the tree by the given [path].
     */
    fun splitByPath(path: List<Int>) {
```

with:

```kotlin
    /**
     * Splits the tree at the given [path].
     *
     * The node at [path] is split into two sibling nodes. Content after the
     * split point moves into a new node inserted immediately after. Internally
     * decomposes into one or two [edit] calls (delete tail + insert new node).
     */
    public fun splitByPath(path: List<Int>) {
```

- [ ] **Step 2: Add `public` modifier and KDoc to `mergeByPath`**

In `JsonTree.kt`, replace lines 220-223:

```kotlin
    /**
     * `mergeByPath` merges the tree by the given [path].
     */
    fun mergeByPath(path: List<Int>) {
```

with:

```kotlin
    /**
     * Merges the element node at the given [path] into its left sibling.
     *
     * The children of the node at [path] are moved into the preceding sibling,
     * then the now-empty node is deleted. Only element nodes can be merged;
     * merging a text node throws [YorkieException]. Internally decomposes into
     * one or two [edit] calls (delete node + insert children).
     */
    public fun mergeByPath(path: List<Int>) {
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew yorkie:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add yorkie/src/main/kotlin/dev/yorkie/document/json/JsonTree.kt
git commit -m "feat: expose splitByPath and mergeByPath as public API on JsonTree"
```

---

### Task 2: Add single-client split tests

**Files:**
- Modify: `yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt`

- [ ] **Step 1: Write test for split at text offset**

Add to `JsonTreeTest` class, before the `companion object`:

```kotlin
@Test
fun test_tree_split_at_text_offset() {
    withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
        // Initial: <doc><p>helloworld</p></doc>
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        text { "helloworld" }
                    }
                },
            )
        }.await()
        assertEquals("<doc><p>helloworld</p></doc>", d1.getRoot().rootTree().toXml())

        // Split <p> at text offset 5 → <doc><p>hello</p><p>world</p></doc>
        d1.updateAsync { root, _ ->
            root.rootTree().splitByPath(listOf(0, 5))
        }.await()
        assertEquals(
            "<doc><p>hello</p><p>world</p></doc>",
            d1.getRoot().rootTree().toXml(),
        )
    }
}
```

- [ ] **Step 2: Write test for split at element boundary**

```kotlin
@Test
fun test_tree_split_at_element_boundary() {
    withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
        // Initial: <doc><p><b>ab</b><i>cd</i></p></doc>
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("b") { text { "ab" } }
                        element("i") { text { "cd" } }
                    }
                },
            )
        }.await()
        assertEquals(
            "<doc><p><b>ab</b><i>cd</i></p></doc>",
            d1.getRoot().rootTree().toXml(),
        )

        // Split <p> at child index 1 → <doc><p><b>ab</b></p><p><i>cd</i></p></doc>
        d1.updateAsync { root, _ ->
            root.rootTree().splitByPath(listOf(0, 1))
        }.await()
        assertEquals(
            "<doc><p><b>ab</b></p><p><i>cd</i></p></doc>",
            d1.getRoot().rootTree().toXml(),
        )
    }
}
```

- [ ] **Step 3: Run tests locally**

Run: `./gradlew yorkie:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.yorkie.document.json.JsonTreeTest#test_tree_split_at_text_offset,dev.yorkie.document.json.JsonTreeTest#test_tree_split_at_element_boundary`
Expected: 2 tests PASS

- [ ] **Step 4: Commit**

```bash
git add yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt
git commit -m "test: add single-client tree split tests"
```

---

### Task 3: Add single-client merge tests

**Files:**
- Modify: `yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt`

- [ ] **Step 1: Write test for merging adjacent elements**

```kotlin
@Test
fun test_tree_merge_adjacent_elements() {
    withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
        // Initial: <doc><p>hello</p><p>world</p></doc>
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "hello" } }
                    element("p") { text { "world" } }
                },
            )
        }.await()
        assertEquals(
            "<doc><p>hello</p><p>world</p></doc>",
            d1.getRoot().rootTree().toXml(),
        )

        // Merge second <p> into first → <doc><p>helloworld</p></doc>
        d1.updateAsync { root, _ ->
            root.rootTree().mergeByPath(listOf(1))
        }.await()
        assertEquals(
            "<doc><p>helloworld</p></doc>",
            d1.getRoot().rootTree().toXml(),
        )
    }
}
```

- [ ] **Step 2: Write test for merging element with children**

```kotlin
@Test
fun test_tree_merge_with_children() {
    withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
        // Initial: <doc><p><b>ab</b></p><p><i>cd</i></p></doc>
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { element("b") { text { "ab" } } }
                    element("p") { element("i") { text { "cd" } } }
                },
            )
        }.await()
        assertEquals(
            "<doc><p><b>ab</b></p><p><i>cd</i></p></doc>",
            d1.getRoot().rootTree().toXml(),
        )

        // Merge second <p> into first → children of second move to first
        d1.updateAsync { root, _ ->
            root.rootTree().mergeByPath(listOf(1))
        }.await()
        assertEquals(
            "<doc><p><b>ab</b><i>cd</i></p></doc>",
            d1.getRoot().rootTree().toXml(),
        )
    }
}
```

- [ ] **Step 3: Run tests locally**

Run: `./gradlew yorkie:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.yorkie.document.json.JsonTreeTest#test_tree_merge_adjacent_elements,dev.yorkie.document.json.JsonTreeTest#test_tree_merge_with_children`
Expected: 2 tests PASS

- [ ] **Step 4: Commit**

```bash
git add yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt
git commit -m "test: add single-client tree merge tests"
```

---

### Task 4: Add concurrent split/merge tests

**Files:**
- Modify: `yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt`

- [ ] **Step 1: Write concurrent split test**

```kotlin
@Test
fun test_tree_concurrent_split() {
    withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
        // Initial: <doc><p>abcdef</p></doc>
        updateAndSync(
            Updater(c1, d1) { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") { text { "abcdef" } }
                    },
                )
            },
            Updater(c2, d2),
        )
        assertTreesXmlEquals("<doc><p>abcdef</p></doc>", d1, d2)

        // c1 splits at offset 2, c2 splits at offset 4 concurrently
        updateAndSync(
            Updater(c1, d1) { root, _ ->
                root.rootTree().splitByPath(listOf(0, 2))
            },
            Updater(c2, d2) { root, _ ->
                root.rootTree().splitByPath(listOf(0, 4))
            },
        )

        // Both should converge to the same tree
        val d1Xml = d1.getRoot().rootTree().toXml()
        val d2Xml = d2.getRoot().rootTree().toXml()
        assertEquals(d1Xml, d2Xml)
    }
}
```

- [ ] **Step 2: Write concurrent merge test**

```kotlin
@Test
fun test_tree_concurrent_merge() {
    withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
        // Initial: <doc><p>a</p><p>b</p><p>c</p></doc>
        updateAndSync(
            Updater(c1, d1) { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") { text { "a" } }
                        element("p") { text { "b" } }
                        element("p") { text { "c" } }
                    },
                )
            },
            Updater(c2, d2),
        )
        assertTreesXmlEquals("<doc><p>a</p><p>b</p><p>c</p></doc>", d1, d2)

        // c1 merges index 1 (<p>b</p> into <p>a</p>)
        // c2 merges index 2 (<p>c</p> into <p>b</p>) concurrently
        updateAndSync(
            Updater(c1, d1) { root, _ ->
                root.rootTree().mergeByPath(listOf(1))
            },
            Updater(c2, d2) { root, _ ->
                root.rootTree().mergeByPath(listOf(2))
            },
        )

        // Both should converge to the same tree
        val d1Xml = d1.getRoot().rootTree().toXml()
        val d2Xml = d2.getRoot().rootTree().toXml()
        assertEquals(d1Xml, d2Xml)
    }
}
```

- [ ] **Step 3: Run all tree split/merge tests**

Run: `./gradlew yorkie:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.yorkie.document.json.JsonTreeTest#test_tree_split_at_text_offset,dev.yorkie.document.json.JsonTreeTest#test_tree_split_at_element_boundary,dev.yorkie.document.json.JsonTreeTest#test_tree_merge_adjacent_elements,dev.yorkie.document.json.JsonTreeTest#test_tree_merge_with_children,dev.yorkie.document.json.JsonTreeTest#test_tree_concurrent_split,dev.yorkie.document.json.JsonTreeTest#test_tree_concurrent_merge`
Expected: 6 tests PASS

- [ ] **Step 4: Commit**

```bash
git add yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt
git commit -m "test: add concurrent tree split and merge tests"
```

---

### Task 5: Final verification

- [ ] **Step 1: Run formatKotlin**

Run: `./gradlew formatKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run lintKotlin**

Run: `./gradlew lintKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run full unit test suite**

Run: `./gradlew yorkie:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (no regressions)

- [ ] **Step 4: Run full JsonTreeTest instrumented suite**

Run: `./gradlew yorkie:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.yorkie.document.json.JsonTreeTest`
Expected: All tests PASS

- [ ] **Step 5: Commit any formatting changes if needed**

```bash
git add -A
git commit -m "chore: apply formatKotlin"
```
