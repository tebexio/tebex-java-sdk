// Settings for the standalone Tebex Java SDK build.
//
// This repository is consumed by Tebex-Minecraft (and any other JVM integration)
// as a git submodule wired in with `includeBuild`, so THIS file is the single
// authoritative description of the SDK's module structure — a consuming build
// never redeclares it.
//
// Two modules:
//   :tbx          — the hand-written, platform-agnostic SDK. Never depends on any
//                   Minecraft package.
//   :headless-api — the OpenAPI-generated Headless API client, regenerated with
//                   `docker compose run --rm sdk-generator` from apis/headless-api.yaml.
//
// The foojay toolchain resolver is required, not optional: both modules pin a
// Java 8 toolchain (CODE_006) and no JDK 8 is expected to be installed locally,
// so Gradle has to be able to download one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Must not collide with the consuming build's rootProject.name.
rootProject.name = "tebex-java-sdk"

listOf(
    "tbx",
    "headless-api"
).forEach(::include)
