# Progress

Session-to-session state handoff.

## Last Session

**Date**: 2026-04-21
**Session**: `/harness-auto` single-spec run for RTCOLLABPLATFORM-643 — port of yorkie-js-sdk#1211 End-token guard.

### Completed
- Harness adopt on worktree `.worktrees/harness-adopt` (branch `chore/harness-adopt`) — scaffolding + arch-lint + verify lanes + evaluator config.
- Spec `docs/specs/001_RTCOLLABPLATFORM-643-tree-end-token-guard.md` — single-file port with JS file:line anchors.
- Exec-plan `docs/exec-plans/completed/RTCOLLABPLATFORM-643-tree-end-token-guard.md` — 4 tickets (T1 helper, T2 style guard, T3 removeStyle guard, T4 divergence test).
- Implementation on branch `feat/RTCOLLABPLATFORM-643-tree-end-token-guard`:
  - `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt` — `hasUnknownSplitSibling` helper + End-token guard in `style` and `removeStyle`
  - `yorkie/src/test/kotlin/dev/yorkie/document/crdt/CrdtTreeStyleDivergenceTest.kt` — 6 tests (concurrent style+split, concurrent removeStyle+split, null-versionVector no-op, text-sibling edge)
- QA round 2 verdict PASS at **13/14** (no auto-fails), no fix round needed.
- Polish commit applied: removed tautological `assertTrue(size >= 0)`, corrected KDoc dangling reference to `advancePastUnknownSplitSiblings` (JS-only helper).
- Carried forward to `docs/specs-backlog.md` (MEDIUM): `CrdtTreeNode.clone()` shallow-copies `_attributes` — a pre-existing within-replica bug the End-token guard does not fix.
- Carried forward to `docs/specs-backlog.md` (LOW): `ClientTest.should retry on network failure…` flake via `NoWhenBranchMatchedException` in `Document.presenceEventReadyToBePublished`.
- New lessons appended (Kotlin `copy(...)` pitfall for mutable properties; tautological assertions).

### Next
- Open PR `feat/RTCOLLABPLATFORM-643-tree-end-token-guard` → `chore/harness-adopt` (until `chore/harness-adopt` lands on `develop`).
- File follow-up tickets for the two MEDIUM/LOW items carried forward (see `docs/specs-backlog.md`).
- Consider acting on Stage 4 harness proposals U1–U4 (evaluator pipeline hygiene) and P2 (arch-lint baseline) — all recorded in `docs/round-reports/round-2-improvements.md`.

### Decisions Made
- Branch naming follows project `.claude/rules/git-workflow.md` (`feat/RTCOLLABPLATFORM-{N}-{desc}`) rather than the harness skill default (`harness/NNN-{name}`) — project instructions win per CLAUDE.md priority.
- Severity of the `CrdtTreeNode.clone()` finding downgraded from HIGH (evaluator proposal) to MEDIUM for backlog purposes — the bug is pre-existing on `develop` and the End-token guard addresses the primary cross-replica symptom; the deep-copy fix is a separate follow-up.
- No instrumented (`androidTest`) coverage added for this ticket — spec explicitly scopes it as unit-only, matching the JS reference (JS PR added no new test file either; coverage is inherited from the Go server-side suite).

### Blockers
- None.
