---
name: team-review
description: |
  Multi-agent review for yorkie-android-sdk. Runs critic-reviewer and test-writer in parallel;
  adds api-compat-checker when Client, Document, JsonObject/Array/Text/Tree, or .proto files are in the diff;
  adds protobuf-converter-checker when .proto files are in the diff.
  Triggered by "team review", "review changes", "review this", "review PR", "code review".
argument-hint: "[--target plan|changes|branch] [--method subagent|team] [--base main]"
---

## Step 1: Confirm review target

If `--target` is not specified, ask:
- **plan**: current plan mode content
- **changes**: uncommitted staged + unstaged changes
- **branch**: full diff against `--base` (default: `main`)

## Step 2: Confirm method

If `--method subagent` is specified, skip. Otherwise confirm with the user.

## Step 3: Build review package

```bash
# --target changes:
git diff --name-only
git diff --cached --name-only

# --target branch:
git diff main...HEAD --name-only
```

Pass only the file list. Each agent reads what it needs via `git diff -- <file>`.
For plan mode, pass the plan content and referenced source file paths.

## Step 4: Select agents

Always run: `critic-reviewer`, `test-writer`

Also run `yorkie-js-researcher` if the JIRA issue or PR description references a JS SDK PR (`yorkie-js-sdk#N`), or if the diff touches CRDT/operation logic under `document/crdt/` or `document/operation/`. Prompt it to compare the Android implementation against the JS SDK reference and flag any divergences.

Also run `api-compat-checker` if any of these appear in the changed file list:
- `Client.kt`, `Document.kt`
- `JsonObject.kt`, `JsonArray.kt`, `JsonText.kt`, `JsonTree.kt`
- Any file matching `*.proto`

Also run `protobuf-converter-checker` if any file matching `*.proto` appears in the changed file list.

## Step 5: Project context (include in every agent prompt)

```
This is yorkie-android-sdk — an Android CRDT-based real-time collaboration SDK.
Documents are edited via JsonObject/JsonArray/JsonText/JsonTree (user-facing API) backed by
CrdtObject/CrdtArray/CrdtText/CrdtTree. Client manages server sync via Connect-RPC push-pull loop.
Each Client and Document runs on a dedicated single-threaded dispatcher.

Critical invariants:
1. CRDT correctness: TimeTicket uniqueness, tombstone tracking in GC pairs, lamport clock ordering
2. Single-threaded dispatch: Client and Document operations must stay on their dedicated dispatcher
3. Mutex guards: all attachment state mutations in Client must hold the mutex
4. API stability: public methods must not break without prior deprecation

Current review mode: {plan or code}
```

## Step 6: Execute review

Dispatch all selected agents as Task agents simultaneously. Wait for all. If one fails, continue.

## Step 7: Report

```
## Review Results

### Key Findings
- [Severity] Item (`file:line`)

### By Agent
- **critic-reviewer**: summary
- **test-writer**: summary
- **api-compat-checker** (if run): summary
- **protobuf-converter-checker** (if run): summary

### Recommended Actions
1. Must fix before merge
2. Should review
3. Notes
```
