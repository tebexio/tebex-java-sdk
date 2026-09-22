// Shared Maven Central publishing setup.
//
// Only :tbx is published. :headless-api is an internal implementation detail —
// the OpenAPI generator's own isolated output directory (see
// docker-compose.yml) — never published as io.tebex:headless-api; :tbx embeds
// its compiled classes directly into tbx's own jar (see tbx/build.gradle.kts)
// so io.tebex:tbx is a self-contained, all-in-one artifact.
//
// Credentials and the signing key are never stored in this repository. Supply
// them as environment variables (e.g. in CI):
//   ORG_GRADLE_PROJECT_mavenCentralUsername
//   ORG_GRADLE_PROJECT_mavenCentralPassword       (a Central Portal user token, not your account password)
//   ORG_GRADLE_PROJECT_signingInMemoryKey         (ASCII-armored PGP private key)
//   ORG_GRADLE_PROJECT_signingInMemoryKeyId
//   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
// or the equivalent mavenCentralUsername/mavenCentralPassword/signing.* keys in
// ~/.gradle/gradle.properties for a local, manual publish. See
// https://vanniktech.github.io/gradle-maven-publish-plugin/central/
//
// Publishing the `io.tebex` namespace also requires that namespace to already be
// verified on https://central.sonatype.com (proven via a DNS TXT record on
// tebex.io) — a one-time account-level step this build cannot perform.
plugins {
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

configure(listOf(project(":tbx"))) {
    apply(plugin = "com.vanniktech.maven.publish")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // Manual release, not publishAndReleaseToMavenCentral/automaticRelease:
        // the deployment lands in the Central Portal for a final human check
        // before it becomes permanent (a released version can never be deleted).
        publishToMavenCentral()
        signAllPublications()

        pom {
            url.set("https://github.com/tebexio/tebex-java-sdk")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("https://opensource.org/license/mit")
                }
            }

            developers {
                developer {
                    id.set("tebexio")
                    name.set("Tebex")
                    url.set("https://tebex.io")
                }
            }

            scm {
                url.set("https://github.com/tebexio/tebex-java-sdk")
                connection.set("scm:git:git://github.com/tebexio/tebex-java-sdk.git")
                developerConnection.set("scm:git:ssh://git@github.com/tebexio/tebex-java-sdk.git")
            }
        }
    }
}
