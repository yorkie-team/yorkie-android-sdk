# Yorkie Android SDK

Kotlin client SDK for real-time collaboration on Android. Connect RPC + Protobuf + Coroutines.

## Development Commands

```sh
./gradlew test                    # Run unit tests
./gradlew connectedAndroidTest    # Instrumented tests (requires emulator)
./gradlew lintKotlin              # Lint check (Kotlinter/ktlint)
./gradlew formatKotlin            # Auto-format code
./gradlew build                   # Full build

# Integration test server
docker compose -f docker/docker-compose.yml up -d
./scripts/config-yorkie-local-server.sh  # Configure local server IP for emulator
```

## After Making Changes

Always run before submitting:
```sh
./gradlew lintKotlin && ./gradlew test
```

## Gotchas

- Apache 2.0 license header required on all files
- On ARM Macs, add `protoc_platform=osx-x86_64` to `local.properties` for protobuf generation
- Build config is centralized in `build-logic/convention/` — don't add config directly to module build files
- Proto generation uses buf CLI, outputs to `build/bufbuild/generated`
- Test method names use backtick syntax: `` `should handle concurrent edits` ``
- Trailing commas required, force multiline when 3+ function parameters
- Version is in `gradle.properties` (`VERSION_NAME=x.y.z`)
- Version catalog at `gradle/libs.versions.toml`
