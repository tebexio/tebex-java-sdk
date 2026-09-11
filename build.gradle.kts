// Shared Maven Central publishing setup.
//
// Both :tbx and :headless-api are published, not just :tbx: a consumer that
// resolves io.tebex:tbx from Maven Central (rather than via the includeBuild
// submodule path described in the README) needs io.tebex:headless-api to
// resolve too, since tbx re-exports it with the `api` configuration.
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

subprojects {
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
                    name.set("GNU General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    distribution.set("https://www.gnu.org/licenses/gpl-3.0.html")
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
