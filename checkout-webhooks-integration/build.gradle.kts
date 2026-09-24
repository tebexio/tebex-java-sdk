// Simulated Tebex Checkout webhook deliveries.
//
// Tebex POSTs webhooks to the store's own endpoint, so there is no API to call
// for them. Instead these tests play Tebex's part: they sign a TebexWebhook
// payload the way Tebex does (X-Signature), POST it to a local endpoint, and
// the endpoint verifies the signature and parses the envelope and its subject
// (PaymentSubject or RecurringPaymentSubject) with the generated Checkout
// models, the way a consumer's webhook handler would.
//
// Fully offline, so it runs as part of `./gradlew build`:
//   ./gradlew :checkout-webhooks-integration:test
//
// Never published: this module has no main sources and is not wired into the
// root build's publishing configuration.
plugins {
    id("java")
}

// Same Java 8 toolchain as :checkout-api, so the models are exercised on the
// oldest JVM consumers use.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // The generated webhook models, plus OkHttp and Gson, which :checkout-api
    // exposes as `api` dependencies.
    testImplementation(project(":checkout-api"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
