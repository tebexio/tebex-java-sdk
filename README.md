# Tebex Java SDK

The platform-agnostic Java SDK for [Tebex](https://tebex.io) — the merchant-of-record
platform that lets server administrators run a store for their game servers.

This repository is consumed by [Tebex-Minecraft](https://github.com/tebexio/Tebex-Minecraft)
as a git submodule, and is intended to be reusable by any JVM integration.

## Modules

| Module | Description |
| --- | --- |
| `tbx` | The hand-written SDK: store/package models, the plugin API client, the command queue and the platform hook interfaces. Contains **no** Minecraft (or any other game) types. |
| `headless-api` | The [Headless API](https://docs.tebex.io/developers/headless-api/overview) client, generated from `apis/headless-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. |

`tbx` re-exports `headless-api` with Gradle's `api` configuration, so a consumer that
depends on `tbx` alone gets the generated Headless types on its compile classpath.

## Building

```bash
./gradlew build     # compile both modules
./gradlew test      # run the requirement suite
```

Both modules target **Java 8** bytecode, so that even the oldest game-server platforms
can consume them. No local JDK 8 install is needed — the
[foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) configured in
`settings.gradle.kts` downloads one on demand.

## Regenerating the Headless API client

The `headless-api` sources are generated from the OpenAPI contract, which is the
source of truth for every Headless operation and schema:

```bash
docker compose run --rm sdk-generator
```

This rewrites `headless-api/src` from `apis/headless-api.yaml`. The hand-written
`headless-api/build.gradle.kts` and `.openapi-generator-ignore` are preserved — see
that ignore file for the full skip list.

## Testing approach

The suite is requirements-based: every test carries an `@Requirement("ID")` tag and
`TraceabilityTest` fails the build on any requirement without a covering test, or any
test without a requirement. `CodeRequirementsTest` and `BytecodeTargetTest` enforce
source hygiene (`CODE_001`–`CODE_005`) and the Java 8 target (`CODE_006`).

Two gates are currently red by design, carried over as known debt at the time of
extraction: `CODE_003` (Javadoc coverage) and `TraceabilityTest` (27 requirements
without tests). A build is "clean" today at **106 tests, 2 failures**; a third failure
is a regression.

## Consuming as a submodule

```bash
git submodule add https://github.com/tebexio/tebex-java-sdk.git tebex-java-sdk
```

Then wire it into the consuming Gradle build as a composite build:

```kotlin
// settings.gradle.kts
includeBuild("tebex-java-sdk")
```

```kotlin
// build.gradle.kts of the consuming module
dependencies {
    implementation("io.tebex:tbx:3.0.0")
}
```

Gradle substitutes the coordinate with the included build's `:tbx` project, so the
submodule is built from source rather than resolved from a repository.

## License

See [LICENSE](LICENSE).
