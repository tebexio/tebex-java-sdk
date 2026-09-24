package io.tebex.checkout.webhooks;

import static io.tebex.checkout.webhooks.Contract.assertMatchesContract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.tebex.checkout.model.PaymentSubject;
import io.tebex.checkout.model.PaymentSubjectProductsInner;
import io.tebex.checkout.model.RecurringPaymentSubject;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Signed TebexWebhook deliveries carrying each kind of subject. */
class CheckoutWebhookTest {

    private static final String SECRET = "test-webhook-secret";

    private final SimulatedTebex tebex = new SimulatedTebex(SECRET);
    private WebhookEndpoint endpoint;

    @BeforeEach
    void start() throws IOException {
        endpoint = new WebhookEndpoint(SECRET);
    }

    @AfterEach
    void stop() {
        endpoint.close();
    }

    @Test
    @DisplayName("payment.completed delivers a PaymentSubject")
    void paymentCompleted() throws IOException {
        WebhookEndpoint.Received received = deliver("payment.completed.json");

        assertEquals("payment.completed", received.webhook.getType());
        assertEquals("2024-07-02T19:52:07+00:00", received.webhook.getDate());

        PaymentSubject payment = assertInstanceOf(PaymentSubject.class, received.subject);
        assertEquals("tbx-40514024a64636-f9a1ea", payment.getTransactionId());
        assertEquals(Integer.valueOf(1), payment.getStatus().getId());
        assertEquals("Complete", payment.getStatus().getDescription());
        assertEquals("oneoff", payment.getPaymentSequence());
        assertEquals(OffsetDateTime.of(2024, 7, 2, 19, 52, 5, 0, ZoneOffset.UTC), payment.getCreatedAt());
        assertEquals(25.99f, payment.getPricePaid().getAmount());
        assertEquals("USD", payment.getPricePaid().getCurrency());
        assertEquals(1.05f, payment.getFees().getGateway().getAmount());
        assertEquals("tebex-integrations@overwolf.com", payment.getCustomer().getEmail());
        assertNull(payment.getRecurringPaymentReference(), "a one-off payment has no recurring reference");
        assertEquals("order-1234", assertInstanceOf(Map.class, payment.getCustom()).get("tracking_id"));

        assertEquals(1, payment.getProducts().size());
        PaymentSubjectProductsInner product = payment.getProducts().get(0);
        assertEquals(Integer.valueOf(127), product.getId());
        assertEquals("100 Gold", product.getName());
        assertEquals(Integer.valueOf(1), product.getQuantity());

        assertMatchesContract(received.webhook);
        assertMatchesContract(payment);
    }

    @Test
    @DisplayName("recurring-payment.started delivers a RecurringPaymentSubject")
    void recurringPaymentStarted() throws IOException {
        WebhookEndpoint.Received received = deliver("recurring-payment.started.json");

        assertEquals("recurring-payment.started", received.webhook.getType());

        RecurringPaymentSubject subscription = assertInstanceOf(RecurringPaymentSubject.class, received.subject);
        assertEquals("tbx-r-714093927", subscription.getReference());
        assertEquals(Integer.valueOf(2), subscription.getStatus().getId());
        assertEquals("Active", subscription.getStatus().getDescription());
        assertEquals(OffsetDateTime.of(2024, 1, 11, 19, 15, 22, 0, ZoneOffset.UTC), subscription.getNextPaymentAt());
        assertNull(subscription.getPausedAt());
        assertNull(subscription.getCancelledAt());
        assertEquals(Integer.valueOf(0), subscription.getFailCount());
        assertEquals(22f, subscription.getPrice().getAmount());

        PaymentSubject initial = subscription.getInitialPayment();
        assertNotNull(initial, "initial_payment");
        assertEquals("tbx-50914026b71512-a3c0de", initial.getTransactionId());
        assertEquals("first", initial.getPaymentSequence());
        assertEquals(subscription.getReference(), initial.getRecurringPaymentReference());
        assertEquals("VIP Monthly", initial.getProducts().get(0).getName());

        PaymentSubject last = subscription.getLastPayment();
        assertNotNull(last, "last_payment");
        assertEquals(initial.getTransactionId(), last.getTransactionId(),
                "a subscription that just started has one payment, so initial and last match");

        assertMatchesContract(received.webhook);
        assertMatchesContract(subscription);
    }

    @Test
    @DisplayName("a delivery signed with the wrong secret is rejected")
    void rejectsForgedSignature() throws IOException {
        String payload = SimulatedTebex.fixture("payment.completed.json");

        SimulatedTebex.Delivery delivery = tebex.deliver(endpoint.url(), payload,
                SimulatedTebex.sign(payload, "not-the-secret"));

        assertEquals(403, delivery.status);
        assertNull(endpoint.last(), "a forged webhook must not be handled");
    }

    /** Delivers a fixture, asserts the endpoint accepted it, and returns what it parsed. */
    private WebhookEndpoint.Received deliver(String fixture) throws IOException {
        SimulatedTebex.Delivery delivery = tebex.deliver(endpoint.url(), SimulatedTebex.fixture(fixture));

        assertEquals(200, delivery.status, "endpoint rejected " + fixture + ": " + delivery.body);
        WebhookEndpoint.Received received = endpoint.last();
        assertNotNull(received, "the endpoint accepted " + fixture + " but recorded nothing");
        assertEquals("{\"id\":\"" + received.webhook.getId() + "\"}", delivery.body,
                "the endpoint must echo the webhook id");
        return received;
    }
}
