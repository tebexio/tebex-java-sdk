// Settings for the standalone Tebex Java SDK build.
//
// This repository is consumed by Tebex-Minecraft (and any other JVM integration)
// as a git submodule wired in with `includeBuild`, so THIS file is the single
// authoritative description of the SDK's module structure — a consuming build
// never redeclares it.
//
// Modules:
//   :tbx          — the hand-written, platform-agnostic SDK. Never depends on any
//                   Minecraft package.
//   :headless-api — the OpenAPI-generated Headless API client, regenerated with
//                   `docker compose run --rm headless-api-generator` from
//                   apis/headless-api.yaml.
//   :checkout-api — the OpenAPI-generated Checkout API client, regenerated with
//                   `docker compose run --rm checkout-api-generator` from
//                   apis/checkout-api.yaml.
//   :headless-integration — live tests of the generated Headless client against
//                   the real API. Never published; skipped unless
//                   TEBEX_IT_PUBLIC_TOKEN is set (see its build.gradle.kts).
//   :checkout-integration — live tests of the generated Checkout client against
//                   the real API. Never published; skipped unless
//                   TEBEX_IT_CHECKOUT_PUBLIC_TOKEN/PRIVATE_KEY are set (see its
//                   build.gradle.kts).
//   :checkout-webhooks-integration — offline tests that simulate Tebex delivering
//                   signed Checkout webhooks to a local endpoint and parse them
//                   with the generated Checkout models. Never published.
//
// The foojay toolchain resolver is required, not optional: every module pins
// a Java 8 toolchain (CODE_006) and no JDK 8 is expected to be installed locally,
// so Gradle has to be able to download one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Must not collide with the consuming build's rootProject.name.
rootProject.name = "tebex-java-sdk"

listOf(
    "tbx",
    "headless-api",
    "checkout-api",
    "headless-integration",
    "checkout-integration",
    "checkout-webhooks-integration"
).forEach(::include)
