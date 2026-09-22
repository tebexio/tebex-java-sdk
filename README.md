# Tebex Java SDK

The platform-agnostic Java SDK for [Tebex](https://tebex.io) - the merchant-of-record platform that lets server administrators run a store for their game servers.

This contains mappings for various Tebex APIs: **Checkout**, **Headless**, and **Plugin**, to allow integration in a wide variety of situations.  

See [Tebex-Minecraft](https://github.com/tebexio/Tebex-Minecraft) for its use in a game server plugin. It is intended to be reusable by any JVM integration.

## Modules

| Module | Description |
| --- | --- |
| `tbx` | The **published** SDK: store/package models, the plugin API client, the command queue and the platform hook interfaces, hand-written and containing **no** Minecraft (or any other game) types — plus the generated Headless and Checkout clients, physically merged into `tbx`'s own jar (see below). |
| `headless-api` | The [Headless API](https://docs.tebex.io/developers/headless-api/overview) client, generated from `apis/headless-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |
| `checkout-api` | The [Checkout API](https://docs.tebex.io/developers/checkout-api/overview) client, generated from `apis/checkout-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |

Neither `headless-api` nor `checkout-api` is a dependency of `tbx` in the usual sense: `tbx/build.gradle.kts` compiles against 
them with `compileOnly` and then merges their compiled classes directly into `tbx`'s own jar and sources jar, so `io.tebex:tbx` 
is a single, self-contained artifact — a consumer needs nothing named `headless-api` or `checkout-api` on their classpath at all, just `tbx` and its ordinary third-party dependencies (okhttp, gson, ...).

## Building

```bash
./gradlew build     # compile all three modules
./gradlew test      # run the requirement suite
```

## Regenerating the generated API clients

The `headless-api` and `checkout-api` sources are each generated from their OpenAPI contract, which is the source of truth for every operation and schema:

```bash
docker compose run --rm headless-api-generator
docker compose run --rm checkout-api-generator
```

## Using with Maven

`io.tebex:tbx` is published on Maven Central, so no extra repository declaration is needed:

```xml
<dependency>
    <groupId>io.tebex</groupId>
    <artifactId>tbx</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Consuming as a submodule

```bash
git submodule add https://github.com/tebexio/tebex-java-sdk.git tebex-java-sdk
```

Then add this into the consuming Gradle build as a composite build:

```kotlin
// settings.gradle.kts
includeBuild("tebex-java-sdk")
```

```kotlin
// build.gradle.kts of the consuming module
dependencies {
    implementation("io.tebex:tbx:1.0.0")
}
```

Gradle substitutes the coordinate with the included build's `:tbx` project, so the submodule is built from source rather than resolved from a repository.

## License

See [LICENSE](LICENSE).
