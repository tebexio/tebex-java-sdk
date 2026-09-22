plugins {
    // java-library rather than plain java: this module is consumed by every
    // platform plugin, and only java-library provides the `api` configuration
    // needed to re-export the generated Headless types as part of tbx's own
    // compile-time surface.
    id("java-library")
}

group = "io.tebex"
version = "1.0.0"

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

// :headless-api and :checkout-api are not published to Maven Central on their
// own — io.tebex:tbx is the only public artifact, and it physically embeds
// both modules' compiled classes (see tasks.jar below). Those references need
// the modules already configured, since sourceSets.main is read from them
// below at configuration time.
evaluationDependsOn(":headless-api")
evaluationDependsOn(":checkout-api")

dependencies {
    // :headless-api and :checkout-api (see docker-compose.yml, services
    // `headless-api-generator`/`checkout-api-generator`) are compiled against
    // here and merged straight into tbx's own jar/sourcesJar (tasks.jar /
    // tasks.sourcesJar below) — `compileOnly`, not `api`, so the dependency
    // never reaches tbx's published POM. Neither io.tebex:headless-api nor
    // io.tebex:checkout-api is itself published, so a real dependency edge to
    // them would leave anyone resolving io.tebex:tbx from Maven Central with
    // an unresolvable dependency; embedding the classes makes tbx a genuine
    // all-in-one jar.
    compileOnly(project(":headless-api"))
    testImplementation(project(":headless-api"))
    compileOnly(project(":checkout-api"))
    testImplementation(project(":checkout-api"))

    // :headless-api and :checkout-api's own runtime dependencies stay regular,
    // separately resolvable Maven dependencies here rather than being
    // embedded — embedding third-party libraries would risk classpath
    // conflicts for consumers who already use okhttp/gson themselves.
    //
    // `api`, not `implementation`: io.tebex.http.HeadlessApi exposes generated
    // types built on these (e.g. an OkHttpClient) as public fields (`Headless`,
    // `Baskets`, `client`), so a caller writing
    // TXE.HeadlessApi().Headless.getAllPackages() needs them on its *compile*
    // classpath. With `implementation` they reached only the runtime classpath
    // and that call did not compile in consuming modules. This was decided
    // deliberately (the "G3" decision in the Tebex-Minecraft changelog,
    // recorded there before this SDK was extracted into its own repository):
    // the generated Headless types ARE part of tbx's public surface. The
    // generated Checkout types share the same dependency set, so no
    // additional coordinates are needed here.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    api("io.gsonfire:gson-fire:1.9.0")
    api("com.google.code.findbugs:jsr305:3.0.2")

    // Gson is used directly by the hand-written plugin API client too. HTTP for
    // the plugin API uses the JDK 8 HttpURLConnection, so that client stays free
    // of any third-party HTTP or Minecraft dependency and within the Java 8 API
    // (CODE_006).
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

// Physically merges :headless-api's and :checkout-api's compiled
// classes/sources into tbx's own jar/sourcesJar, so io.tebex:tbx is a genuine
// all-in-one artifact: a consumer resolving it from Maven Central needs
// nothing else on the classpath besides the regular third-party dependencies
// declared above. No relocation is needed — :headless-api and :checkout-api
// live entirely under the io.tebex.headless and io.tebex.checkout packages
// respectively, which tbx's own hand-written sources never use for their own
// classes.
tasks.jar {
    from(project(":headless-api").sourceSets.main.get().output)
    from(project(":checkout-api").sourceSets.main.get().output)
}

// `sourcesJar` is created lazily by the maven-publish plugin (via
// `withSourcesJar()`, wired up in the root build.gradle.kts) only once java-library
// is applied here, which happens after this script starts running — so it can't be
// looked up by name yet. `withType(...).configureEach` reacts whenever it does
// get registered, regardless of ordering.
tasks.withType<Jar>().configureEach {
    if (name == "sourcesJar") {
        from(project(":headless-api").sourceSets.main.get().allJava)
        from(project(":checkout-api").sourceSets.main.get().allJava)
    }
}

// Maven Central coordinates/signing/license/scm are configured once for every
// module in the root build.gradle.kts; this is just the per-module POM identity.
extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    pom {
        name.set("Tebex Java SDK")
        description.set(
            "Platform-agnostic, all-in-one Java SDK for the Tebex merchant-of-record " +
                "platform: store/package models, the plugin API client, the generated " +
                "Headless API and Checkout API clients, the command queue and the " +
                "platform hook interfaces."
        )
    }
}
