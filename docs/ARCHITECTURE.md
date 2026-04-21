# Architecture — yorkie-android-sdk

Android Kotlin SDK for Yorkie, a collaborative document store backed by CRDTs.

## Modules

| Path | Purpose | Publishable |
|------|---------|-------------|
| `yorkie/` | Main SDK library | **yes** (Maven artifact `dev.yorkie:yorkie`) |
| `examples/todomvc` | TodoMVC sample app | no |
| `examples/scheduler` | Scheduler sample app | no |
| `examples/rich-text-editor` | Rich text editor sample | no |
| `examples/simultaneous-cursors` | Cursor sharing sample | no |
| `examples/core/common` | Shared sample code | no |
| `examples/feature/enter-document-key` | Document-key entry screen | no |
| `microbenchmark/` | JMH-style benchmarks | no |
| `build-logic/convention/` | Shared Gradle convention plugins | no |

Only `yorkie/` is published. Nothing in `yorkie/` may depend on `examples/**` or `microbenchmark/**`.

## Layers Inside `yorkie/src/main/kotlin/dev/yorkie/`

```
api/              Protobuf ↔ domain converters (ChangeConverter, OperationConverter,
                  ElementConverter, PresenceConverter, TimeConverter, ...)

core/             Client (gRPC/Connect-RPC over OkHttp), sync loop, watch stream, auth

document/
├── Document.kt   Public document handle; updateAsync; change queue
├── crdt/         INTERNAL CRDT data structures:
│                   CrdtObject, CrdtArray, CrdtText, CrdtTree, ElementRht,
│                   RgaTreeList, RgaTreeSplit, SplayTree, ...
├── json/         PUBLIC user-facing wrappers:
│                   JsonObject, JsonArray, JsonText, JsonTree, JsonCounter, ...
├── operation/    Operations (Add, Move, Remove, Edit, Style, TreeEdit, ...)
├── change/       Change, ChangeID, ChangePack, ChangeContext
├── time/         TimeTicket (Lamport clock), VersionVector, ActorID
├── history/      Undo/Redo history manager
├── presence/     Presence (cursors, selections, custom metadata)
└── schema/       JSON schema validation

util/             IndexTree, SplayTree, SplayTreeSet, logging, ...
```

## Dependency Direction (enforced)

**Rule 1 — Two-layer separation.** `document/crdt/**` MUST NOT import `document/json/**`. The JSON layer wraps the CRDT layer, never the other way around.

```
user code
   │
   ▼
document/json/   ──────────────► document/crdt/   (json wraps crdt)
        │                              ▲
        └──► document/operation/ ──────┘
                     │
                     ▼
               document/change/  ──► document/time/
```

**Rule 2 — No upward imports from utilities.** `util/**` must not import from `document/**`, `core/**`, or `api/**`.

**Rule 3 — Protobuf is an I/O boundary.** Proto messages (generated under `yorkie/build/generated/`) are allowed in `api/*Converter.kt` and in `core/` (where the Connect-RPC Client is the other half of the I/O boundary). Pure domain code (`document/**`, `util/**`) MUST NOT reference proto types directly.

**Rule 4 — Public API surface.** The only types intended for SDK consumers are:
- `dev.yorkie.core.Client` and its public events
- `dev.yorkie.document.Document` and its public events
- `dev.yorkie.document.json.*` (JsonObject, JsonArray, JsonText, JsonTree, JsonCounter, JsonPrimitive)
- `dev.yorkie.document.presence.*` (Presence, PresenceInfo)

Anything in `document/crdt/`, `api/`, and most of `util/` is internal. A public signature must never return, accept, or expose a CRDT type.

## Client–Document–Server Flow

1. `Client.attachAsync(document)` — opens gRPC attachment, pulls initial snapshot.
2. Local mutation: `Document.updateAsync { root -> ... }` — appends a `Change` to the local queue.
3. `Client.runSyncLoop()` — push local `ChangePack` → pull remote `ChangePack` → apply to the CRDT root.
4. `Client.watchStream` — streams remote changes and presence updates.
5. Each `Client`/`Document` pair runs on a dedicated single-threaded coroutine dispatcher; all CRDT mutations happen on that dispatcher to avoid races.

## Change Identity

- `ChangeID` = (Lamport clock + `ActorID` + `ClientSeq` + `VersionVector`)
- `TimeTicket` = the logical timestamp used to order CRDT operations
- `VersionVector` encodes causal ordering across replicas

## Test Surfaces

- **Unit** (`yorkie/src/test/`) — JVM-only. CRDT logic, converters, utilities. MockK for `YorkieService`.
- **Instrumented** (`yorkie/src/androidTest/`) — real Yorkie server over gRPC via Docker Compose. Authoritative for sync, presence, and schema validation.
- **Benchmarks** (`microbenchmark/`) — hot-path performance.

## External Dependencies

- Connect-Kotlin (gRPC) + OkHttp for transport
- protobuf-javalite (runtime) + buf plugin (codegen)
- kotlinx-coroutines, kotlinx-collections-immutable, apache-commons-collections
- Guava (shared with Connect-Kotlin)

## Enforcement

| Rule | Enforcement |
|------|-------------|
| Two-layer, module boundaries, forbidden patterns, file size | `scripts/lint_architecture.sh` |
| Kotlin style | `./gradlew lintKotlin` (ktlint) |
| Android lint | `./gradlew lint` |
| Unit tests | `./gradlew yorkie:testDebugUnitTest` |
| Integration | `./gradlew yorkie:connectedDebugAndroidTest` (needs docker-compose Yorkie server) |
