// Build script for the generated Tebex Headless API Java SDK.
//
// The Java SOURCES under src/ are produced by the OpenAPI generator — never edit
// them by hand; run `docker compose run --rm sdk-generator` to regenerate them
// from ../apis/headless-api.yaml. This build script, and the
// .openapi-generator-ignore next to it, are the only hand-written files in the
// module (the generator is told to skip them).
//
// Other modules consume the SDK with:
//     implementation(project(":headless-api"))
//
// Java 8 toolchain so EVERY consumer can depend on it, transitively through :tbx:
// that includes the oldest Tebex-Minecraft platform plugins (bukkit, bungeecord),
// which compile against Java 8. This is why the SDK is generated with
// the okhttp-gson library rather than the Java-11-only `native` library.
plugins {
    `java-library`
}

group = "io.tebex"
version = "3.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

// Dependency set derived from the imports in the generated okhttp-gson sources
// (the generator's own pom.xml is skipped via .openapi-generator-ignore, so the
// build script owns these). Exposed with `api` because the generated public API
// surfaces these types (e.g. ApiClient#getHttpClient() returns OkHttpClient),
// so consumers need them on their compile classpath.
dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    api("com.google.code.gson:gson:2.10.1")
    api("io.gsonfire:gson-fire:1.9.0")
    // Provides javax.annotation.Nonnull/Nullable used throughout the generated
    // sources (useJakartaEe=false). javax.annotation.Generated comes from the
    // Java 8 JDK itself. jsr305 is Java 8 compatible.
    api("com.google.code.findbugs:jsr305:3.0.2")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
