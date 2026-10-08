# Yorkie Android SDK

[![codecov](https://codecov.io/gh/yorkie-team/yorkie-android-sdk/branch/main/graph/badge.svg?token=USX8DU19YO)](https://codecov.io/gh/yorkie-team/yorkie-android-sdk)
[![Maven Central](https://img.shields.io/maven-central/v/dev.yorkie/yorkie-android.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22dev.yorkie%22%20AND%20a:%22yorkie-android%22)

Yorkie Android SDK provides a suite of tools for building real-time collaborative applications.

## How to use

See [Getting Started with Android SDK](https://yorkie.dev/docs/getting-started/with-android-sdk) for the instructions.

Example projects can be found in the [examples](https://github.com/yorkie-team/yorkie-android-sdk/tree/main/examples) folder.

Read the [full documentation](https://yorkie.dev/docs) for all details.

## Developing the SDK

To work on this project, make sure you have Android Studio version `Hedgehog | 2023.1.1` or later installed.

For developers with MAC, you should add `protoc_platform=osx-x86_64` to your `local.properties`.

## Testing yorkie-android-sdk with Envoy, Yorkie and MongoDB.

Start MongoDB, Yorkie and Envoy proxy in a terminal session.

```bash
$ docker compose -f docker/docker-compose.yml up --build -d
```

Start the test in another terminal session.

```bash
$ ./gradlew test
```

To get the latest server locally, run the command below then restart containers again:

```bash
$ docker pull yorkieteam/yorkie:latest
$ docker-compose -f docker/docker-compose.yml up --build -d
```

## Config connect Yorkie Server

### Local

#### Install Yorkie Server

##### Using Docker

```bash
$ docker compose -f docker/docker-compose.yml up --build -d
```

##### Using CLI

Install Yorkie CLI following [guidance](https://yorkie.dev/docs/cli).

Note: consider installing version is the same with version of SDK

Start Yorkie server

```bash
yorkie server --rpc-addr 0.0.0.0:8080
```

#### Config yorkie local server

```bash
./scripts/config-yorkie-local-server.sh
```

### Public Cloud

Start and create your project and get API Key on [Public Cloud](https://yorkie.dev)

Config variables in `local.properties` file with your Yorkie Public Cloud server URL and API key

```bash
YORKIE_SERVER_URL=https://api.yorkie.dev
YORKIE_API_KEY=Your Yorkie API key
```

### Self-Hosted Server

If you're running your own Yorkie server, config variables in `local.properties` file with your server URL and API key

```bash
YORKIE_SERVER_URL=https://your-yorkie-server.com
YORKIE_API_KEY=Your Yorkie API key
```

### Compatibility

Undo/redo of `JsonText` and `JsonTree` is identity-preserving: each undo/redo travels in
operation fields that exist only from Yorkie server 0.7.14 and the matching SDK releases
(`Edit` and `TreeEdit`: `restore_spans`, `restore_mode`, `retombstone_spans`). Every
participant in a document must understand them — the server and every peer SDK alike:

| Participant | Minimum version |
|-------------|-----------------|
| Yorkie server (self-hosted or hosted) | 0.7.14 |
| yorkie-android-sdk peers | a release with identity-preserving undo/redo (0.7.12 and earlier do not) |
| yorkie-js-sdk peers | 0.7.14 |
| yorkie-ios-sdk peers | not yet supported |

An older server or peer drops those fields and sees only the base edit, which this SDK
always emits as a zero-width, empty edit. That participant deletes nothing, but the undo/redo
never applies there: it takes effect only on the client that issued it, and the replicas silently
diverge for good, with no error on either side. Every other SDK feature works against earlier
servers and peers. Upgrade every participant before relying on undo/redo in a mixed fleet.

#### Styling text or tree nodes that a peer removed concurrently (0.7.23)

From 0.7.23 a style or remove-style operation on text that is concurrent with a removal is applied
on every replica that receives it (and so are the pieces of a tree element that a concurrent split
exposes), as it already was on the replica that issued it (yorkie-js-sdk #1368, yorkie server
#2012). Earlier releases could skip such a style on a replica that received it after the removal,
so replicas held different attributes on the removed content. That difference becomes visible when
the removal is later undone. Replicas agree on those attributes only if every participant is on
0.7.23 or later:

| Participant | Minimum version |
|-------------|-----------------|
| Yorkie server | 0.7.23 |
| yorkie-android-sdk peers | this release |
| yorkie-js-sdk peers | 0.7.23 |

Two cases still diverge, with every participant on 0.7.23. First, if a replica garbage-collects the
removed content before a peer undoes the removal, that replica rebuilds the text from the peer's
deletion-time snapshot and loses a style it had applied to the tombstone, while the peer and the
server keep it; the same outcome was executed on the yorkie-js-sdk reference, and an instrumented
test pins it. Second, a style on a tree element that a peer removes as a whole never takes effect
on the peer that removed it, so after the removal is undone the two replicas hold different
attributes on that element; earlier releases and yorkie-js-sdk behave the same way.

A plain string attribute is sent as the raw string (`color="red"` is sent as `red`), the encoding
yorkie-js-sdk 0.7.23 and the server use; older yorkie-js-sdk peers expect the JSON-encoded form. A
string that is itself JSON (such as `1` or `true`) is also sent raw, where yorkie-js-sdk keeps it
quoted, so a JS peer may read it as the JSON value.

Offline local persistence and the stable actor (v0.7.20 sync): a persisted document is stored as
a snapshot plus an append-only change log and a small header (checkpoint, changeID, compaction
epoch) rather than one opaque blob — each local edit appends one small log entry instead of
re-serializing the whole document, and the log is folded back into a fresh snapshot once it
grows past a size-relative threshold. Resuming replays the log on top of the snapshot and
re-pushes whatever the header shows as un-acknowledged against the persisted checkpoint and epoch,
and watch peers are keyed by the stable actor — both need Yorkie server >= 0.7.20 (yorkie#1969,
#1970). Against an older server the SDK falls back to the session id as the actor: with a
`docStore`, every activation gets a new actor, so each restart's restore fails the actor guard —
un-pushed offline edits are **discarded** (`Document.Event.LocalChangesDropped`, reason
`ActorMismatch`) and the entry is removed; without a store nothing changes. Only one active
session per document is allowed under offline persistence: a second concurrent resume fails fast
with `ErrDocumentOpenElsewhere` instead of silently racing the first. The persisted entry is
removed once the document is detached, removed, or learned removed via a sync, and is kept across
a deactivate so the next session can resume it.

| Participant | Minimum version |
|-------------|-----------------|
| Yorkie server (self-hosted or hosted) | 0.7.20 |
| yorkie-js-sdk peers | 0.7.20 |
| yorkie-ios-sdk peers | 0.7.20 (PR #274) |
| yorkie-android-sdk peers | this release |

**Behavioural change:** peers in presence/watch events are now keyed by the stable actor, not the
per-session client id. An app that identified itself among `presences` via
`Status.Activated.clientId` / `Client.requireClientId()` must switch to
`Status.Activated.actorId` / `Client.requireActorId()`.

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md) for details on submitting patches and the contribution workflow.

## Contributors ✨

Thanks goes to these incredible people:

<a href="https://github.com/yorkie-team/yorkie-android-sdk/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=yorkie-team/yorkie-android-sdk" />
</a>
