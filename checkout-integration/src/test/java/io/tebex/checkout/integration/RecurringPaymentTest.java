package io.tebex.checkout.integration;

import static io.tebex.checkout.integration.LiveCheckout.checkContract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tebex.checkout.invoker.ApiException;
import io.tebex.checkout.model.ModelPackage;
import io.tebex.checkout.model.RecurringPayment;
import io.tebex.checkout.model.UpdateRecurringPaymentRequest;
import io.tebex.checkout.model.UpdateSubscriptionRequest;
import io.tebex.checkout.model.UpdateSubscriptionRequestItemsInner;
import io.tebex.http.CheckoutApi;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Recurring payments: fetch, pause/reactivate, update, and cancel subscriptions named by the environment. */
class RecurringPaymentTest {

    private CheckoutApi checkout;

    @BeforeEach
    void connect() {
        checkout = LiveCheckout.client();
    }

    @Test
    @DisplayName("getRecurringPayment returns a subscription by its reference (needs TEBEX_IT_CHECKOUT_RECURRING_REF)")
    void getRecurringPayment() throws ApiException {
        String reference = LiveCheckout.required("TEBEX_IT_CHECKOUT_RECURRING_REF");

        RecurringPayment subscription = checkout.RecurringPayments.getRecurringPayment(reference);

        assertEquals(reference, subscription.getReference());
        assertNotNull(subscription.getStatus(), "subscription status");
        checkContract(subscription);
    }

    @Test
    @DisplayName("updateRecurringPayment pauses and then reactivates a subscription (needs TEBEX_IT_CHECKOUT_PAUSE_REF)")
    void pauseAndReactivate() throws ApiException {
        String reference = LiveCheckout.required("TEBEX_IT_CHECKOUT_PAUSE_REF");

        try {
            RecurringPayment paused = checkout.RecurringPayments.updateRecurringPayment(reference,
                    new UpdateRecurringPaymentRequest()
                            .status(UpdateRecurringPaymentRequest.StatusEnum.PAUSED)
                            .pausedUntil(isoDaysFromNow(30)));
            assertEquals(reference, paused.getReference());
            assertNotNull(paused.getPausedAt(), "a paused subscription has paused_at");
            checkContract(paused);
        } finally {
            // Reactivate even if the pause assertions failed, so the subscription
            // is never left paused by a test run.
            RecurringPayment active = checkout.RecurringPayments.updateRecurringPayment(reference,
                    new UpdateRecurringPaymentRequest().status(UpdateRecurringPaymentRequest.StatusEnum.ACTIVE));
            assertEquals(reference, active.getReference());
            assertNull(active.getPausedAt(), "a reactivated subscription has no paused_at");
            checkContract(active);
        }
    }

    @Test
    @DisplayName("updateSubscription replaces a subscription's product (needs TEBEX_IT_CHECKOUT_UPDATE_REF; may charge)")
    void updateSubscription() throws ApiException {
        String reference = LiveCheckout.required("TEBEX_IT_CHECKOUT_UPDATE_REF");

        RecurringPayment updated = checkout.RecurringPayments.updateSubscription(reference,
                new UpdateSubscriptionRequest().items(Collections.singletonList(
                        new UpdateSubscriptionRequestItemsInner()
                                .type(UpdateSubscriptionRequestItemsInner.TypeEnum.SUBSCRIPTION)
                                .qty(BigDecimal.ONE)
                                ._package(new ModelPackage()
                                        .name("Integration Test Subscription")
                                        .price(1.27f)
                                        .type(ModelPackage.TypeEnum.SUBSCRIPTION)
                                        .expiryPeriod(ModelPackage.ExpiryPeriodEnum.MONTH)
                                        .expiryLength(1)))));

        assertEquals(reference, updated.getReference());
        checkContract(updated);
    }

    @Test
    @DisplayName("cancelRecurringPayment cancels a subscription (needs TEBEX_IT_CHECKOUT_CANCEL_REF; irreversible)")
    void cancelRecurringPayment() throws ApiException {
        String reference = LiveCheckout.required("TEBEX_IT_CHECKOUT_CANCEL_REF");

        RecurringPayment cancelled = checkout.RecurringPayments.cancelRecurringPayment(reference);

        assertEquals(reference, cancelled.getReference());
        assertTrue(cancelled.getCancelledAt() != null || cancelled.getCancellationRequestedAt() != null,
                "a cancelled subscription has cancelled_at or cancellation_requested_at");
        checkContract(cancelled);
    }

    /** An ISO 8601 UTC timestamp the given number of days from now. */
    private static String isoDaysFromNow(int days) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days)));
    }
}
