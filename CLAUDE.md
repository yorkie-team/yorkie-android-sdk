# Yorkie Android SDK

Kotlin client SDK for Yorkie, providing real-time collaboration primitives for Android applications.

## Tech Stack

- Kotlin 1.9.24, Gradle 8.11.1, Android Gradle Plugin 8.6.1
- MinSDK 24, CompileSDK 36, JVM target 17
- Connect RPC (Kotlin) + Protobuf (javalite), OkHttp, Coroutines
- JUnit 4, MockK, Espresso, Kotlinter (ktlint)

## Development Commands

```sh
./gradlew test                    # Run unit tests
./gradlew connectedAndroidTest    # Run instrumented tests (requires emulator)
./gradlew lintKotlin              # Lint check (Kotlinter)
./gradlew formatKotlin            # Auto-format code
./gradlew build                   # Full build
./gradlew :yorkie:jacocoTestReport  # Generate coverage report

# Integration test server
docker compose -f docker/docker-compose.yml up -d

# Configure local server IP for emulator tests
./scripts/config-yorkie-local-server.sh
```

## Project Structure

```
yorkie/                    # Main SDK module (published as dev.yorkie:yorkie-android)
  src/main/kotlin/dev/yorkie/
    core/                  # Client, Attachment, Interceptors
    document/              # Document, JSON models, CRDT, operations
      crdt/                # CRDT implementations
      json/                # JsonObject, JsonArray, JsonText
      operation/           # Edit, Style, Set operations
      presence/            # Presence tracking
      change/              # Change, ChangeContext, ChangePack
      time/                # TimeTicket, ActorID, VersionVector
    util/                  # Logger, YorkieException, utilities
  src/test/                # Unit tests (JUnit + coroutines)
  src/androidTest/         # Instrumented tests (Espresso)
  proto/                   # Protobuf definitions + buf config
build-logic/convention/    # Gradle convention plugins
  yorkie.android.library         # Android library setup
  yorkie.android.library.jacoco  # Code coverage
  yorkie.maven.publish           # Maven Central publishing
  yorkie.protocol.buffer.generation  # Buf-based proto generation
examples/                  # Sample apps (todomvc, cursors, scheduler, rich-text)
microbenchmark/            # Android benchmark tests
gradle/libs.versions.toml  # Centralized version catalog
```

## Code Conventions

- Apache 2.0 license header on all files
- Official Kotlin coding conventions (`kotlin.code.style=official`)
- 4-space indentation, max line length 100 chars
- Single name imports only, trailing commas required
- Force multiline when 3+ function parameters
- Backtick test method names for readability: `` `should handle concurrent edits` ``
- Coroutine testing with `runTest` and `UnconfinedTestDispatcher`
- Commit messages: subject max 70 chars, body at 80 chars
- Version in `gradle.properties` (`VERSION_NAME=0.6.35`)

## Architecture Notes

- **Convention plugins**: All build config centralized in `build-logic/`
- **Proto generation**: Uses buf CLI with incremental builds, outputs to `build/bufbuild/generated`
- **ProGuard rules**: Preserves `dev.yorkie` public API and protobuf classes
- **Coroutines**: Core async model using Kotlin coroutines and Flow
- **macOS note**: Add `protoc_platform=osx-x86_64` to `local.properties` for ARM Macs
