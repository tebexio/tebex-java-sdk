// Live integration tests for the generated Checkout API client.
//
// Unlike :tbx's unit tests (which stub the API in-JVM), these call the real
// https://checkout.tebex.io/api with a real store's credentials, so they catch
// the class of bug a stub cannot: the contract (apis/checkout-api.yaml)
// disagreeing with what the API actually returns. They exercise the client
// exactly as a consumer does — through io.tebex.http.CheckoutApi.
//
// Opt-in: every test is skipped unless TEBEX_IT_CHECKOUT_PUBLIC_TOKEN and
// TEBEX_IT_CHECKOUT_PRIVATE_KEY are set, so `./gradlew build` and CI stay
// offline. The store must have the Checkout API enabled. See LiveCheckout for
// the other (optional) variables, several of which name a real payment or
// subscription to refund, pause, update or cancel. Run with:
//   TEBEX_IT_CHECKOUT_PUBLIC_TOKEN=... TEBEX_IT_CHECKOUT_PRIVATE_KEY=... ./gradlew :checkout-integration:test
//
// Never published: this module has no main sources and is not wired into the
// root build's publishing configuration.
plugins {
    id("java")
}

// Same Java 8 toolchain as :tbx, so the tests run the client on the oldest JVM
// consumers use and no second JDK has to be provisioned.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // :tbx for the CheckoutApi facade; :checkout-api for the generated types,
    // which :tbx only compiles against (they are merged into its jar rather
    // than exposed as a dependency).
    testImplementation(project(":tbx"))
    testImplementation(project(":checkout-api"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val contractWarnings = layout.buildDirectory.file("contract-warnings.tsv")

// Prints the properties recorded during the test run as warnings, one per
// schema and property however many responses it appeared in. A finalizer
// rather than a doLast, so it still reports when a test failed for another
// reason.
val reportContractDrift = tasks.register("reportContractDrift") {
    val warnings = contractWarnings
    doLast {
        val file = warnings.get().asFile
        if (!file.exists()) {
            return@doLast
        }
        val grouped = file.readLines()
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .groupBy { it[0] to it[1] }
        if (grouped.isEmpty()) {
            return@doLast
        }
        logger.warn("WARNING: the API returned ${grouped.size} propert${if (grouped.size == 1) "y" else "ies"} " +
            "not defined in apis/checkout-api.yaml. The client keeps them in additionalProperties, " +
            "but each should be added to the contract:")
        for ((key, rows) in grouped) {
            val (schema, property) = key
            val more = if (rows.size > 1) " and ${rows.size - 1} more" else ""
            logger.warn("  - add property `$property` to $schema (${rows[0][2]}) - seen at ${rows[0][3]}$more")
        }
    }
}

tasks.test {
    useJUnitPlatform()

    // The result depends on the live API, not only on the inputs Gradle can
    // see, so never skip a run as up to date.
    outputs.upToDateWhen { false }

    // LiveCheckout names the contract schema an undeclared property belongs to.
    systemProperty("tebex.contract", rootProject.file("apis/checkout-api.yaml").absolutePath)
    inputs.property("liveCredentialsPresent", System.getenv("TEBEX_IT_CHECKOUT_PRIVATE_KEY") != null)

    testLogging {
        // SKIPPED is printed by the listener below, together with its reason.
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Prints each skipped test with why it was skipped (the unmet assumption,
    // e.g. a missing environment variable), which the SKIPPED line alone does
    // not say.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
        override fun beforeTest(test: TestDescriptor) {}
        override fun afterTest(test: TestDescriptor, result: TestResult) {
            if (result.resultType != TestResult.ResultType.SKIPPED) {
                return
            }
            logger.lifecycle("${test.className?.substringAfterLast('.')} > ${test.displayName} SKIPPED")
            val reason = result.assumptionFailure?.details?.message?.removePrefix("Assumption failed: ")
            if (reason != null) {
                logger.lifecycle("    $reason")
            }
        }
    })

    // LiveCheckout.checkContract appends each property the API returned but the
    // contract does not define here, rather than failing the test: API
    // additions are not a build blocker, but they are reported below.
    systemProperty("tebex.contractWarnings", contractWarnings.get().asFile.absolutePath)
    doFirst { contractWarnings.get().asFile.delete() }
    finalizedBy(reportContractDrift)
}
