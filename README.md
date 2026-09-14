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

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md) for details on submitting patches and the contribution workflow.

## Contributors ✨

Thanks goes to these incredible people:

<a href="https://github.com/yorkie-team/yorkie-android-sdk/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=yorkie-team/yorkie-android-sdk" />
</a>
