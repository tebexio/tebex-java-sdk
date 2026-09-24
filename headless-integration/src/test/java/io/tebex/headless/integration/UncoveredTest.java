package io.tebex.headless.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract operations this module does not call against the live API, and why.
 *
 * <p>Each is a test that always passes, so the gap is listed in every test
 * report rather than only in a comment. These deliberately do not connect to
 * the store, so they run (and pass) even when TEBEX_IT_PUBLIC_TOKEN is unset.
 */
class UncoveredTest {

    @Test
    @DisplayName("createDynamicPackage: TODO, not tested until the dynamic packages API is finished")
    void createDynamicPackage() {
        // TODO: test createDynamicPackage (populate a dynamic category for a
        // basket, then read it back with getCategoryIncludeDynamicPackages)
        // once the dynamic packages API is finished.
    }

    @Test
    @DisplayName("updateTier: deliberately not tested, it changes a real customer's subscription")
    void updateTier() {
        // updateTier moves a real customer's tier to a different package and
        // makes Tebex send a recurring payment webhook, so it cannot run
        // against a store without changing a live subscription.
    }
}
