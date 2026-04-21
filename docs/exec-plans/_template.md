---
id: NNN
title: "{title}"
status: todo
priority: P1
domain: "{domain}"
layer: "{layer}"
depends_on: []
created: YYYY-MM-DD
---

# {title}

## Description
What this ticket accomplishes and why.

## Scope
- Domain: `yorkie/src/main/kotlin/dev/yorkie/{domain}/`
- Layer: `{layer}`  (api | core | document/crdt | document/json | document/operation | document/change | document/time | document/presence | util)
- Estimated effort: <2 hours

## Success Metrics (DoD)
- [ ] Unit tests pass
- [ ] Instrumented tests pass if applicable
- [ ] `./gradlew lintKotlin` clean
- [ ] `bash scripts/lint_architecture.sh` clean
- [ ] Implementation matches spec

## Evidence
(Filled after implementation)
