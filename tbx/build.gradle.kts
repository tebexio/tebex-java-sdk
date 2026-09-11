plugins {
    // java-library rather than plain java: this module is consumed by every
    // platform plugin, and only java-library provides the `api` configuration
    // needed to re-export the generated Headless types as part of tbx's own
    // compile-time surface.
    id("java-library")
}

group = "io.tebex"
version = "0.0.1"

// tbx targets Java 8 (CODE_006). Every consuming platform module must be able to
// use this SDK, and the oldest of them (the Tebex-Minecraft bukkit and bungeecord
// plugins) compile against Java 8 —
// a Java 17 jar cannot be read by a Java 8 javac at all. The toolchain (rather
// than only source/targetCompatibility) is set to 8 so the JDK 8 class library is
// what main AND test sources compile against: a Java 9+ API cannot slip in and
// then fail at runtime on an older server.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // The generated Headless API SDK (see docker-compose.yml, service
    // `sdk-generator`). tbx consumes it for the Headless endpoints
    // (getWebstore/listCategories); the plugin API (/information) stays a
    // hand-written client in this module. This transitively brings okhttp/gson
    // onto tbx's classpath, which is fine: TBX_041 only forbids Minecraft
    // packages, and :headless-api carries none.
    //
    // `api`, not `implementation`: io.tebex.http.HeadlessApi exposes generated
    // types as public fields (`Headless`, `Baskets`, `client`), so a caller
    // writing TXE.HeadlessApi().Headless.getAllPackages() needs them on its
    // *compile* classpath. With `implementation` they reached only the runtime
    // classpath and that call did not compile in consuming modules. This was
    // decided deliberately (the "G3" decision in the Tebex-Minecraft changelog,
    // recorded there before this SDK was extracted into its own repository):
    // the generated Headless types ARE part of tbx's public surface.
    api(project(":headless-api"))

    // Gson is used directly by the hand-written plugin API client. HTTP for the
    // plugin API uses the JDK 8 HttpURLConnection, so that client stays free of any
    // third-party HTTP or Minecraft dependency and within the Java 8 API (CODE_006).
    //
    // Also `api`: PluginApi's public constructors take a Gson, so it is part of
    // this module's compile-time surface rather than an internal detail.
    api("com.google.code.gson:gson:2.10.1")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Maven Central coordinates/signing/license/scm are configured once for every
// module in the root build.gradle.kts; this is just the per-module POM identity.
extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    pom {
        name.set("Tebex Java SDK")
        description.set(
            "Platform-agnostic Java SDK for the Tebex merchant-of-record platform: " +
                "store/package models, the plugin API client, the command queue and " +
                "the platform hook interfaces."
        )
    }
}