package io.tebex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.tebex.checkout.invoker.ApiClient;
import io.tebex.checkout.invoker.ApiException;
import io.tebex.checkout.model.Basket;
import io.tebex.checkout.model.CheckoutItem;
import io.tebex.checkout.model.CheckoutRequest;
import io.tebex.checkout.model.CheckoutRequestBasket;
import io.tebex.checkout.model.ModelPackage;
import io.tebex.checkout.model.Payment;
import io.tebex.checkout.model.RecurringPayment;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the generated Checkout API clients, called through the
 * instantiable {@link CheckoutApi} access point and exercised end-to-end against
 * an in-JVM {@link HttpServer} (no network required).
 *
 * <p>Routes mirror the generated operation paths: {@code /baskets/{ident}} and
 * {@code /checkout} both resolve to a basket, {@code /payments/{txnId}} to a
 * payment, and {@code /recurring-payments/{reference}} to a recurring payment.
 * {@code getPaymentById}'s generated path template literally includes a
 * {@code ?type=txn_id} query string (baked into the OpenAPI contract's path
 * key), which lands in the request's query component, not its path, so prefix
 * matching on {@link java.net.URI#getPath()} is unaffected.
 */
class CheckoutApiTest {

    private static final String BASKET_JSON =
            "{\"ident\":\"1a-example\",\"links\":{\"checkout\":\"https://checkout.tebex.io/1a-example\"}}";

    private static final String PAYMENT_JSON =
            "{\"transaction_id\":\"tbx-example\",\"status\":{\"id\":1,\"description\":\"Complete\"}}";

    private static final String RECURRING_PAYMENT_JSON =
            "{\"reference\":\"tbx-r-example\",\"status\":{\"id\":2,\"description\":\"Active\",\"active\":1}}";

    private HttpServer server;
    private volatile String capturedAuthorizationHeader;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a stub Checkout API routing by path prefix ({@code /payments/} →
     * payment, {@code /recurring-payments/} → recurring payment, anything else
     * — {@code /baskets/{ident}} and {@code /checkout} alike — → basket),
     * replying with {@code status}, and returns a {@link CheckoutApi} bound to
     * it.
     */
    private CheckoutApi startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            capturedAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.startsWith("/payments/")) {
                body = PAYMENT_JSON;
            } else if (path.startsWith("/recurring-payments/")) {
                body = RECURRING_PAYMENT_JSON;
            } else {
                body = BASKET_JSON;
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new CheckoutApi(new ApiClient().setBasePath(baseUrl));
    }

    @Test
    @Requirement("TBX_069")
    @DisplayName("TBX_069: a basket can be fetched by its identifier via the checkout api")
    void getBasketByIdReturnsBasket() throws IOException, ApiException {
        CheckoutApi checkout = startServer(200);

        Basket basket = checkout.Baskets.getBasketById("1a-example");

        assertEquals("1a-example", basket.getIdent());
        assertNotNull(basket.getLinks());
        assertEquals("https://checkout.tebex.io/1a-example", basket.getLinks().getCheckout());
    }

    @Test
    @Requirement("TBX_070")
    @DisplayName("TBX_070: a checkout request can be created in a single call, returning a basket with a checkout link")
    void checkoutCreatesBasketWithCheckoutLink() throws IOException, ApiException {
        CheckoutApi checkout = startServer(200);
        CheckoutRequest request = new CheckoutRequest()
                .basket(new CheckoutRequestBasket().email("customer@example.com"))
                .addItemsItem(new CheckoutItem()._package(new ModelPackage().name("VIP")).qty(1));

        Basket basket = checkout.Checkout.checkout(request);

        assertEquals("1a-example", basket.getIdent());
        assertEquals("https://checkout.tebex.io/1a-example", basket.getLinks().getCheckout());
    }

    @Test
    @Requirement("TBX_071")
    @DisplayName("TBX_071: a payment can be fetched by its transaction id via the checkout api")
    void getPaymentByIdReturnsPayment() throws IOException, ApiException {
        CheckoutApi checkout = startServer(200);

        Payment payment = checkout.Payments.getPaymentById("tbx-example");

        assertEquals("tbx-example", payment.getTransactionId());
        assertEquals("Complete", payment.getStatus().getDescription());
    }

    @Test
    @Requirement("TBX_072")
    @DisplayName("TBX_072: a recurring payment (subscription) can be fetched by its reference via the checkout api")
    void getRecurringPaymentReturnsSubscription() throws IOException, ApiException {
        CheckoutApi checkout = startServer(200);

        RecurringPayment subscription = checkout.RecurringPayments.getRecurringPayment("tbx-r-example");

        assertEquals("tbx-r-example", subscription.getReference());
        assertEquals(1, subscription.getStatus().getActive());
    }

    @Test
    @Requirement("TBX_073")
    @DisplayName("TBX_073: credentials bound with setCredentials are sent as an HTTP Basic Authorization header")
    void credentialsAreSentAsHttpBasicAuth() throws IOException, ApiException {
        CheckoutApi checkout = startServer(200);
        checkout.setCredentials("secret-key", "");

        checkout.Baskets.getBasketById("1a-example");

        String expected =
                "Basic " + Base64.getEncoder().encodeToString("secret-key:".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, capturedAuthorizationHeader);
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a rejected checkout api request (404) surfaces as a typed ApiException, not a crash")
    void rejectedRequestIsApiException() throws IOException {
        CheckoutApi checkout = startServer(404);

        ApiException thrown = assertThrows(ApiException.class, () -> checkout.Baskets.getBasketById("1a-example"));
        assertEquals(404, thrown.getCode());
    }
}
