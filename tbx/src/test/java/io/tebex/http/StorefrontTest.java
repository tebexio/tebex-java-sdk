package io.tebex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.headless.invoker.ApiClient;
import io.tebex.headless.invoker.ApiException;
import io.tebex.headless.model.Basket;
import io.tebex.headless.model.ModelPackage;
import io.tebex.model.CheckoutUrl;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the storefront half of the SDK: which packages are on
 * sale (TBX_016), that a checkout created through the plugin API yields a basket
 * the Headless API recognises (TBX_017), and that creator (TBX_018) and discount
 * (TBX_019) codes can be applied to that basket.
 *
 * <p>One in-JVM stub plays both hosts. That is what makes TBX_017 testable at
 * all: the requirement is precisely that an ident produced by one API is
 * accepted by the other, so both have to be in the same test.
 */
class StorefrontTest {

    private static final String CHECKOUT_JSON =
            "{\"url\":\"https://checkout.tebex.io/checkout/abc-123\",\"expires\":\"2026-01-01T00:00:00+00:00\"}";

    private static final String BASKET_JSON =
            "{\"data\":{\"id\":7,\"ident\":\"abc-123\",\"complete\":false,\"username\":\"Notch\","
            + "\"base_price\":10.0,\"total_price\":10.0,\"currency\":\"USD\"}}";

    private static final String PACKAGES_JSON =
            "{\"data\":["
            + "{\"id\":10,\"name\":\"VIP\",\"base_price\":10.0,\"total_price\":7.5,\"discount\":2.5},"
            + "{\"id\":11,\"name\":\"MVP\",\"base_price\":20.0,\"total_price\":20.0,\"discount\":0.0},"
            + "{\"id\":12,\"name\":\"Legend\",\"base_price\":30.0,\"total_price\":30.0}"
            + "]}";

    /** The paths the stub was asked for, in order. */
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private String baseUrl;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Writes a JSON response and closes the exchange.
     *
     * @param exchange the exchange to answer
     * @param status   the status code
     * @param body     the body
     * @throws IOException if writing fails
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }

    /**
     * Starts one stub serving both the plugin API checkout endpoint and the
     * Headless basket, coupon, creator-code and package endpoints.
     *
     * @param couponAccepted    what the coupon endpoint reports
     * @param creatorAccepted   what the creator-code endpoint reports
     * @throws IOException if the server cannot be started
     */
    private void startStore(boolean couponAccepted, boolean creatorAccepted) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(exchange.getRequestMethod() + " " + path);

            if (path.equals("/checkout")) {
                respond(exchange, 201, CHECKOUT_JSON);
            } else if (path.equals("/baskets/abc-123/coupons")) {
                respond(exchange, 200, "{\"success\":" + couponAccepted + ",\"message\":\""
                        + (couponAccepted ? "Coupon applied" : "Unknown coupon") + "\"}");
            } else if (path.equals("/baskets/abc-123/creator-codes")) {
                respond(exchange, 200, "{\"success\":" + creatorAccepted + ",\"message\":\""
                        + (creatorAccepted ? "Creator code applied" : "Unknown creator code") + "\"}");
            } else if (path.equals("/baskets/abc-123")) {
                respond(exchange, 200, BASKET_JSON);
            } else if (path.equals("/packages")) {
                respond(exchange, 200, PACKAGES_JSON);
            } else {
                respond(exchange, 404, "{\"error\":\"no such path: " + path + "\"}");
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * Returns a Headless client pointed at the stub.
     *
     * @return the client under test
     */
    private HeadlessApi headless() {
        return new HeadlessApi(new ApiClient().setBasePath(baseUrl));
    }

    @Test
    @Requirement("TBX_016")
    @DisplayName("TBX_016: the packages a store has on sale can be retrieved with the public token")
    void salePackagesAreListed() throws IOException, ApiException {
        startStore(true, true);

        List<ModelPackage> onSale = headless().PackagesOnSale();

        assertEquals(1, onSale.size(), "only the discounted package is on sale: " + onSale);
        assertEquals("VIP", onSale.get(0).getName());
        assertEquals(7.5f, onSale.get(0).getTotalPrice(), 0.001f,
                "the sale price is what a customer would pay");
    }

    @Test
    @Requirement("TBX_016")
    @DisplayName("TBX_016: a store with nothing discounted reports an empty sale list, not an error")
    void noSalesIsAnEmptyList() throws IOException, ApiException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 200, "{\"data\":[]}"));
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        assertTrue(headless().PackagesOnSale().isEmpty());
    }

    @Test
    @Requirement("TBX_017")
    @DisplayName("TBX_017: a checkout's basket ident is accepted by the headless api")
    void checkoutIdentWorksAgainstHeadless() throws IOException, ApiException {
        startStore(true, true);

        CheckoutUrl checkout = new PluginApi(baseUrl)
                .createCheckoutUrl("valid-secret", 10, "Notch").join();
        String ident = checkout.getBasketIdent();

        assertEquals("abc-123", ident, "the ident is the last segment of the checkout url");

        // The point of the requirement: that ident is a basket the *other* API
        // knows about.
        Basket basket = headless().Headless.getBasket(ident).getData();

        assertEquals("abc-123", basket.getIdent());
        assertEquals("Notch", basket.getUsername());
        assertTrue(requested.contains("GET /baskets/abc-123"),
                "the ident must have been used as the basket ident: " + requested);
    }

    @Test
    @Requirement("TBX_019")
    @DisplayName("TBX_019: a valid discount code is applied to the basket")
    void discountCodeIsApplied() throws IOException, ApiException {
        startStore(true, true);

        assertTrue(headless().ApplyCoupon("abc-123", "SUMMER25"),
                "a code the store accepts must report success");
        assertTrue(requested.contains("POST /baskets/abc-123/coupons"),
                "the code must be applied to the basket: " + requested);
    }

    @Test
    @Requirement("TBX_019")
    @DisplayName("TBX_019: a discount code the store rejects is a false result, not a failure")
    void rejectedDiscountCodeIsNotAFailure() throws IOException, ApiException {
        startStore(false, true);

        assertFalse(headless().ApplyCoupon("abc-123", "NOPE"),
                "a mistyped code is a customer error, not an exception the caller must catch");
    }

    @Test
    @Requirement("TBX_018")
    @DisplayName("TBX_018: a valid creator code is applied to the basket")
    void creatorCodeIsApplied() throws IOException, ApiException {
        startStore(true, true);

        assertTrue(headless().ApplyCreatorCode("abc-123", "STREAMER"),
                "a code the store accepts must report success");
        assertTrue(requested.contains("POST /baskets/abc-123/creator-codes"),
                "the code must be applied to the basket: " + requested);
    }

    @Test
    @Requirement("TBX_018")
    @DisplayName("TBX_018: a creator code the store rejects is a false result, not a failure")
    void rejectedCreatorCodeIsNotAFailure() throws IOException, ApiException {
        startStore(true, false);

        assertFalse(headless().ApplyCreatorCode("abc-123", "NOBODY"));
    }
}
