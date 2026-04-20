# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build
./gradlew build

# Unit tests (fast, JVM only)
./gradlew yorkie:testDebugUnitTest

# Run a single unit test class
./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.document.DocumentTest"

# Instrumented tests (requires running Yorkie server via Docker)
docker compose -f docker/docker-compose.yml up --build -d
./scripts/config-yorkie-local-server.sh   # must be run before instrumented tests — writes YORKIE_SERVER_URL to local.properties
./gradlew yorkie:connectedDebugAndroidTest

# Lint
./gradlew lintKotlin
./gradlew formatKotlin

# Code coverage report
./gradlew yorkie:jacocoDebugTestReport
```

**Local setup notes:**
- Mac users: add `protoc_platform=osx-x86_64` to `local.properties`
- Integration tests need a Yorkie server; use `./scripts/config-yorkie-local-server.sh` to auto-configure the IP

## Architecture Overview

The SDK is organized into a single `yorkie` library module. The public API entry points are `Client` and `Document`.

### Two-Layer API Design

There are two layers — never mix them:

1. **CRDT layer** (`document/crdt/`) — Internal implementation of conflict-free data structures. Not exposed to users.
2. **JSON API layer** (`document/json/`) — User-facing wrappers (`JsonObject`, `JsonArray`, `JsonText`, `JsonTree`) that delegate to CRDT types.

When implementing new data operations, the pattern is: add the CRDT logic in `crdt/`, expose it via `json/`, add an `Operation` in `operation/`, wire conversion in `api/OperationConverter.kt` and `api/ElementConverter.kt`.

### Client–Document–Server Flow

- `Client` manages the gRPC/Connect-RPC connection (via OkHttp) and lifecycle of attached documents.
- `Document` holds the CRDT root and queues local changes. It can operate fully offline.
- Sync happens via `Client.runSyncLoop()`: push local `ChangePack` → pull remote `ChangePack` → apply to CRDT root.
- Each client/document pair uses a dedicated single-threaded coroutine dispatcher to avoid races.

### Change Identity

Every change carries a `ChangeID` (lamport clock + actorID + clientSeq + versionVector). `TimeTicket` is the logical timestamp used to order CRDT operations. `VersionVector` handles causal ordering across replicas.

### Presence

Presence (cursors, selections, arbitrary metadata) is tracked separately from document content. It flows through the watch stream, not the push-pull sync. See `document/presence/` and `core/Client.kt` watch handling.

### Protobuf & API Converters

Protobuf definitions live in `yorkie/proto/yorkie/v1/`. Conversion between domain objects and protobuf is in `api/*Converter.kt` files — one per concern (changes, operations, elements, presence, time, version vector).

## Testing Conventions

- **Unit tests** (`yorkie/src/test/`) — cover CRDT logic, converters, and utilities. Use MockK to mock `YorkieService`.
- **Instrumented tests** (`yorkie/src/androidTest/`) — integration tests against a real Yorkie server running in Docker. These are the authoritative tests for document sync, presence, and schema validation.
- Test helpers are in `TestUtils.kt`, `ApiUtils.kt`, and `JsonTestUtils.kt` under `androidTest/`.

## Module Layout

| Path | Purpose |
|------|---------|
| `yorkie/` | Main SDK library (only publishable module) |
| `examples/` | Sample Android apps (todomvc, scheduler, rich-text-editor, simultaneous-cursors) |
| `build-logic/convention/` | Shared Gradle convention plugins (AndroidLibrary, MavenPublish, ProtocolBuffer) |
| `microbenchmark/` | Performance benchmarks |
| `docker/` | Docker Compose files for local and CI Yorkie server |
| `scripts/` | Helper scripts for dev setup |

## Key Files

| File | Role |
|------|------|
| `yorkie/src/main/kotlin/dev/yorkie/core/Client.kt` | Main client: connect, attach, sync loop, presence |
| `yorkie/src/main/kotlin/dev/yorkie/document/Document.kt` | Document lifecycle, `updateAsync`, change tracking |
| `yorkie/src/main/kotlin/dev/yorkie/document/crdt/` | All CRDT implementations |
| `yorkie/src/main/kotlin/dev/yorkie/document/json/` | Public JSON-like API wrappers |
| `yorkie/src/main/kotlin/dev/yorkie/api/` | Protobuf converters |
| `yorkie/src/main/kotlin/dev/yorkie/util/IndexTree.kt` | Positional index tree used by `CrdtTree` |
| `gradle/libs.versions.toml` | Centralized dependency versions |
