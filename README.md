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
$ docker compose -f docker/docker-compose.yml up --build -d
```

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md) for details on submitting patches and the contribution workflow.


## Contributors ✨

Thanks goes to these incredible people:

<a href="https://github.com/yorkie-team/yorkie-android-sdk/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=yorkie-team/yorkie-android-sdk" />
</a>
