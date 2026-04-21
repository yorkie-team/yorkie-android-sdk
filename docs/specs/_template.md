---
status: backlog
created: YYYY-MM-DD
updated: YYYY-MM-DD
---

# Feature: {name}

## Problem
What problem does this solve?

## Scope
- In scope: ...
- Out of scope: ...

## Scenarios
1. Happy path: ...
2. Edge case: ...
3. Error case: ...

## Definition of Done
- [ ] Unit tests pass (`./gradlew yorkie:testDebugUnitTest`)
- [ ] Instrumented tests pass if sync/watch/CRDT behavior changed (`./gradlew yorkie:connectedDebugAndroidTest`)
- [ ] `./gradlew lintKotlin` clean
- [ ] `bash scripts/lint_architecture.sh` clean
- [ ] Documentation updated
- [ ] Evidence captured
