# Tebex Java SDK

The platform-agnostic Java SDK for [Tebex](https://tebex.io) - the merchant-of-record
platform that lets server administrators run a store for their game servers.

This repository is primarily consumed by [Tebex-Minecraft](https://github.com/tebexio/Tebex-Minecraft) as a git submodule, and is intended to be reusable by any JVM integration.

## Modules

| Module | Description |
| --- | --- |
| `tbx` | The **published** SDK: store/package models, the plugin API client, the command queue and the platform hook interfaces, hand-written and containing **no** Minecraft (or any other game) types — plus the generated Headless client, physically merged into `tbx`'s own jar (see below). |
| `headless-api` | The [Headless API](https://docs.tebex.io/developers/headless-api/overview) client, generated from `apis/headless-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |

`headless-api` is not a dependency of `tbx` in the usual sense: `tbx/build.gradle.kts`
compiles against it with `compileOnly` and then merges its compiled classes directly
into `tbx`'s own jar and sources jar, so `io.tebex:tbx` is a single, self-contained
artifact — a consumer needs nothing named `headless-api` on their classpath at all,
just `tbx` and its ordinary third-party dependencies (okhttp, gson, ...).

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
    implementation("io.tebex:tbx:0.0.1")
}
```

Gradle substitutes the coordinate with the included build's `:tbx` project, so the
submodule is built from source rather than resolved from a repository.

## Publishing to Maven Central

Only `io.tebex:tbx` is published — `headless-api` is never published on its own
(see [Modules](#modules) above); `tbx` embeds it. Publishing is configured with the
[Vanniktech Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
in the root `build.gradle.kts`, applied only to `:tbx`, targeting Sonatype's
[Central Portal](https://central.sonatype.com/).

One-time account setup (not part of this repository):

1. A [Central Portal](https://central.sonatype.com/) account with the `io.tebex`
   namespace verified (proven via a DNS TXT record on `tebex.io`).
2. A dedicated PGP key pair for release signing, with the public key published
   to a keyserver (e.g. `keyserver.ubuntu.com`).
3. A Central Portal user token (Account → Generate User Token) — not your
   account password.

To publish from a maintainer's machine, add to `~/.gradle/gradle.properties`
(never to this repository):

```properties
mavenCentralUsername=<user token username>
mavenCentralPassword=<user token password>
signing.keyId=<last 8 chars of the key id>
signing.password=<key passphrase>
signing.secretKeyRingFile=<path to a secring.gpg exported from the key>
```

then bump `version` in `tbx/build.gradle.kts` (and, for consistency, the same
value in `headless-api/build.gradle.kts` and `artifactVersion` in
`docker-compose.yml` — neither is published, but both feed into `tbx`'s
embedded classes/User-Agent string) and run:

```bash
./gradlew publishToMavenCentral   # stages the deployment for review at central.sonatype.com
./gradlew publishAndReleaseToMavenCentral   # stages and releases in one step
```

The same thing runs from CI via the [`release` workflow](.github/workflows/release.yml)
(reading the equivalent credentials from repository secrets instead), which
fires automatically on every push to `main` — so a merged version-bump PR is
enough to stage a new deployment — and can also be run on demand from the
Actions tab. Either way it only stages the deployment; a release, once
published, can never be deleted, so someone still has to review it and click
Publish at central.sonatype.com, or re-run the workflow manually with
"automaticRelease" checked to do both steps at once.

## License

See [LICENSE](LICENSE).
