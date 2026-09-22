# Tebex Java SDK Onboarding Skill

Tebex is a merchant-of-record platform that enables sellers to operate a webstore to sell and deliver virtual items. Packages sold may include ranks, items, and coins. Sellers can include mobile game developers, game server administrators, and game studios.

== Setup ==

## 1. Project Summary
This is the platform-agnostic Java SDK for [Tebex](https://tebex.io) - the merchant-of-record platform that lets server administrators run a store for their game servers.

This contains mappings for various Tebex APIs: **Checkout**, **Headless**, and **Plugin**, to allow integration in a wide variety of situations.

See [Tebex-Minecraft](https://github.com/tebexio/Tebex-Minecraft) for its use in a game server plugin. It is intended to be reusable by any JVM integration.

## 2. Repository Layout

## Project Modules

| Module | Description |
| --- | --- |
| `tbx` | The **published** SDK: store/package models, the plugin API client, the command queue and the platform hook interfaces, hand-written and containing **no** Minecraft (or any other game) types — plus the generated Headless and Checkout clients, physically merged into `tbx`'s own jar (see below). |
| `headless-api` | The [Headless API](https://docs.tebex.io/developers/headless-api/overview) client, generated from `apis/headless-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |
| `checkout-api` | The [Checkout API](https://docs.tebex.io/developers/checkout-api/overview) client, generated from `apis/checkout-api.yaml` by the OpenAPI generator. Do not edit its sources by hand. Internal only — never published on its own; exists solely as the generator's isolated output directory. |

Neither `headless-api` nor `checkout-api` is a dependency of `tbx` in the usual sense: `tbx/build.gradle.kts` compiles against
them with `compileOnly` and then merges their compiled classes directly into `tbx`'s own jar and sources jar, so `io.tebex:tbx`
is a single, self-contained artifact — a consumer needs nothing named `headless-api` or `checkout-api` on their classpath at all, just `tbx` and its ordinary third-party dependencies (okhttp, gson, ...).

## 3. Additional Concepts

[Any additional project-specific or in-progress efforts should go here]
## Java 8 Required
All three modules target **Java 8** bytecode, so that even the oldest game-server
platforms can consume them. No local JDK 8 install is needed — the
[foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) configured in
`settings.gradle.kts` downloads one on demand.

## Publishing to Maven Central

Only `io.tebex:tbx` is published — `headless-api` and `checkout-api` are never
published on their own (see [Modules](#modules) above); `tbx` embeds them. Publishing is configured with the
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
value in `headless-api/build.gradle.kts` and `checkout-api/build.gradle.kts`,
and `artifactVersion` in both services in `docker-compose.yml` — none of
these are published, but all feed into `tbx`'s embedded classes/User-Agent
string) and run:

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

== Features (do not edit below this line) ==

## Changelog Methodology

Use the files in the project's folder .changelog/ to acquire and track context of the project's current state:
- todo.md
- complete.md

If these folders or files do not exist, you may create them.

When modifying the project at any point, the first thing you should do is read and update these two files with what has been done.
Only remove items from the todo list if they have a corresponding entry in complete.md, as the todo list is used by developers and other agents to track the current state of the project.

Entries should be brief and to the point. Max character limit per-line is 280.

## Agents

For all engineering operations, utilize 3 agents:
1. Product - draft a plan for implementing the necessary feature or function. Be mindful of user expectations and API capabilities. Flag any gaps in the request before proceeding
2. Engineer - write code to complete the requests from Product and any returned requests from the Reviewer. Be particularly mindful of all Additional Concepts. All new code must be tested with an associated requirement test.
3. Reviewer - review the engineer's work with the requirements from product. Flag any discrepancies and return to the engineer until they pass review. Do not accept new code that does not have an associated requirement test.

## Requirements Testing and Traceability

Tebex SDKs utilize a requirements-based testing scheme. The idea is to make the software as close to "provably correct" as the stack allows.

The 5 pillars of this methodology are:
1. Requirements testing -> Specification-linked test cases: each test traces to a requirement, each requirement is satisfied by a passing test)
2. Structural coverage  -> Branch and condition coverage tracking with test suite
3. Independent V&V -> Automated adversarial test pipeline
4. Hardware-in-loop -> Any staging mirrors production exactly
5. Fault injection -> Utilize chaos engineering and failure simulation wherever possible

There may be limitations to implementing this methodology fully, depending on the platform being integrated. When finding these, document them as accepted constraints.

### Rules to Follow

Every test must trace to a requirement; every requirement must trace to a test.

A test without a requirement is a **GAP**. A requirement without a test is a **GAP**. A build cannot be considered successful with any **GAPS**.

Create a requirements registry in the implementing language via ID tags: (ex CODE_001 = "no fixmes in the codebase"). Tag every test with the requirement it covers with an annotation/decorator.

All tests are written from the requirement description itself, **not** by reading the implementation. Example: read CODE_001, enumerate all ways it could be violated, write tests for each violation.

For each **REQUIREMENT**, apply this boundary analysis checklist:
- Happy path (normal input, expected output)
- Lower boundary (minimum valid input)
- Upper boundary (maximum valid input)
- Just outside lower boundary (invalid - rejected)
- Just outside upper boundary (invalid - rejected)
- Null/undefined/empty input
- Adversarial inputs (SQL injection, XSS payloads, oversized values)
- Concurrent/race-condition scenarios
- State machine violations

Verify full MC/DC Coverage: Every independent condition in every branch must be shown to independently affect the outcome:
- All test cases must meet specific criteria for every decision
- Every decision takes all possible outcomes (True and False) at least once.
- Every condition in a decision takes all possible outcomes (True and False) at least once.
- Each condition has been shown to independently affect the decision's outcome.
- To test independence, holding all other conditions constant while changing just one condition must flip the overall decision result

For a condition such as:
```
const canWithdraw = user.isVerified && account.balance >= amount && !account.isFrozen;
```
You must write tests that prove each sub-condition independently controls the outcome.

For Independent V&V: any OpenAPI contracts used ARE requirements, they are the source of truth for all API operations and schemas and will satisfy this requirement.

For Production and mirror-staging: the project will have a Live Testing section which will describe how to test it against a live system. Typically there is a docker-compose.yml configuration to start and run.

You must demonstrate that the software handles all failure modes safely.

### Traceability Matrix
You will generate a traceability matrix from the test registry to deliver to your future self, this should scan all test files for their relevant ID tags and produce a coverage report

When applying tests to a new codebase, work in this order:
1. Requirements registry first: nothing is valid without these
2. Traceability gate: enforce coverage of requirements immediately
3. Critical modules have 100% coverage: core TVM system
4. Property-based tests on all data-handling code
5. Staging environment parity
6. Fault injection suite

The traceability matrix is NOT committed and should be added to the .gitignore, or removed if it is present.

## Live Testing

For any live testing, create a `docker-compose.yml` to start and run test environments. This should come with an associated DOCKERFILE which describes the environment being tested.

Check with the user what type of live environment is desired when creating a new live testing environment.