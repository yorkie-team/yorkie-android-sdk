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
