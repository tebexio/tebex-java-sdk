package io.tebex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.tebex.headless.invoker.ApiClient;
import io.tebex.headless.invoker.ApiException;
import io.tebex.headless.invoker.JSON;
import io.tebex.headless.model.AddBasketPackageRequest;
import io.tebex.headless.model.Basket;
import io.tebex.headless.model.BasketAuthResponseInner;
import io.tebex.headless.model.BasketResponse;
import io.tebex.headless.model.Category;
import io.tebex.headless.model.CreateBasketRequest;
import io.tebex.headless.model.ModelPackage;
import io.tebex.headless.model.RemoveBasketPackageRequest;
import io.tebex.headless.model.UpdatePackageQuantityRequest;
import io.tebex.headless.model.Webstore;
import io.tebex.requirements.Requirement;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the generated Headless API clients, called through the
 * instantiable {@link HeadlessApi} access point and exercised end-to-end against
 * an in-JVM {@link HttpServer} (no network required).
 *
 * <p>The real contract scopes the store to a {@code token} server variable and
 * exposes distinct operations (there is no {@code includePackages} query
 * parameter). Tests inject a client via {@code new ApiClient().setBasePath(...)}
 * so requests hit the local stub, whose routes mirror the generated operation
 * paths: {@code /} (getWebstore), {@code /categories} (getCategories),
 * {@code /packages} (getAllPackages).
 */
class HeadlessApiTest {

    private static final String WEBSTORE_JSON =
            "{\"data\":{\"id\":123,\"name\":\"Example Store\",\"currency\":\"USD\"}}";

    private static final String CATEGORIES_JSON =
            "{\"data\":[{\"id\":1,\"name\":\"Ranks\",\"slug\":\"ranks\",\"order\":1}]}";

    private static final String PACKAGES_JSON =
            "{\"data\":[{\"id\":10,\"name\":\"VIP\"}]}";

    /**
     * A new, empty basket exactly as the live API returns it: {@code links} is
     * an empty array although the contract types it as an object.
     */
    private static final String EMPTY_BASKET_JSON = "{"
            + "\"ident\":\"test-basket-ident\",\"complete\":false,\"id\":835611142,\"email\":\"\","
            + "\"country\":\"NL\",\"ip\":\"127.0.0.1\",\"username_id\":null,\"username\":null,"
            + "\"cancel_url\":\"https://example.com/cancel\",\"complete_url\":\"https://example.com/complete\","
            + "\"complete_auto_redirect\":false,\"base_price\":0,\"sales_tax\":0,\"total_price\":0,"
            + "\"currency\":\"EUR\",\"packages\":[],\"coupons\":[],\"giftcards\":[],"
            + "\"creator_code\":null,\"links\":[]}";

    private static final String BASKET_RESPONSE_JSON = "{\"data\":" + EMPTY_BASKET_JSON + "}";

    /**
     * The live API's reply to adding a package: the basket wrapped in
     * {@code data} (not bare, as the contract once claimed), with a package in
     * it and populated links.
     */
    private static final String FILLED_BASKET_RESPONSE_JSON = "{\"data\":{"
            + "\"ident\":\"test-basket-ident\",\"complete\":false,\"id\":835621323,\"email\":\"\","
            + "\"country\":\"NL\",\"ip\":\"127.0.0.1\",\"username_id\":null,\"username\":null,"
            + "\"cancel_url\":\"https://example.com/cancel\",\"complete_url\":\"https://example.com/complete\","
            + "\"complete_auto_redirect\":false,\"base_price\":1.99,\"sales_tax\":0,\"total_price\":1.99,"
            + "\"currency\":\"EUR\",\"packages\":[{\"id\":7693666,\"name\":\"Weekend Pass\",\"slug\":null,"
            + "\"description\":\"<p>48 hours of access.</p>\\n\",\"in_basket\":{\"quantity\":1,\"price\":1.99,"
            + "\"gift_username_id\":null,\"gift_username\":null},\"image\":null,\"is_recurring\":false}],"
            + "\"coupons\":[],\"giftcards\":[],\"creator_code\":null,"
            + "\"links\":{\"checkout\":\"https://pay.tebex.io/test-basket-ident\"}}}";

    private HttpServer server;

    /** The method and path of the last request the stub received. */
    private volatile String lastRequest;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a stub Headless API routing by path ({@code /} → webstore,
     * {@code /categories} → categories, {@code /packages} → packages), replying
     * with {@code status}, and returns a {@link HeadlessApi} bound to it.
     */
    private HeadlessApi startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastRequest = exchange.getRequestMethod() + " " + path;
            String body;
            if (path.equals("/categories")) {
                body = CATEGORIES_JSON;
            } else if (path.equals("/packages")) {
                body = PACKAGES_JSON;
            } else if (path.equals("/baskets")) {
                body = BASKET_RESPONSE_JSON;
            } else if (path.endsWith("/packages")) {
                body = FILLED_BASKET_RESPONSE_JSON;
            } else if (path.endsWith("/packages/remove")) {
                body = BASKET_RESPONSE_JSON;
            } else if (path.contains("/packages/")) {
                body = "{}";
            } else {
                body = WEBSTORE_JSON;
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
        return new HeadlessApi(new ApiClient().setBasePath(baseUrl));
    }

    /**
     * Starts the stub and points the Baskets endpoints at it too: they carry
     * their own server in the contract, which the client's base path does not
     * override.
     */
    private HeadlessApi startBasketsServer() throws IOException {
        HeadlessApi headless = startServer(200);
        headless.Baskets.setCustomBaseUrl("http://localhost:" + server.getAddress().getPort());
        return headless;
    }

    @Test
    @Requirement("TBX_042")
    @DisplayName("TBX_042: a webstore's public information can be fetched with the public token")
    void getWebstoreReturnsStoreInfo() throws IOException, ApiException {
        HeadlessApi headless = startServer(200);

        Webstore webstore = headless.Headless.getWebstore().getData();

        assertEquals("Example Store", webstore.getName());
        assertEquals("USD", webstore.getCurrency());
    }

    @Test
    @Requirement("TBX_015")
    @DisplayName("TBX_015: a store's categories can be retrieved with the public token via the headless api")
    void getCategoriesReturnsCategories() throws IOException, ApiException {
        HeadlessApi headless = startServer(200);

        List<Category> categories = headless.Headless.getCategories().getData();

        assertEquals(1, categories.size());
        assertEquals("Ranks", categories.get(0).getName());
        assertEquals("ranks", categories.get(0).getSlug());
    }

    @Test
    @Requirement("TBX_014")
    @DisplayName("TBX_014: a store's packages can be retrieved with the public token via the headless api")
    void getAllPackagesReturnsPackages() throws IOException, ApiException {
        HeadlessApi headless = startServer(200);

        List<ModelPackage> packages = headless.Headless.getAllPackages().getData();

        assertFalse(packages.isEmpty(), "the store's packages must be returned");
        assertEquals("VIP", packages.get(0).getName());
    }

    @Test
    @Requirement("TBX_042")
    @DisplayName("TBX_042: the public token is interpolated into the request URL (production server-variable path)")
    void tokenIsBoundToRequestUrl() {
        // Uses the production constructor (setServerVariables, serverIndex=0),
        // exercising the token-interpolation branch that setBasePath bypasses.
        HeadlessApi headless = new HeadlessApi("abc123");

        String url = headless.client.buildUrl(null, "/", null, null);

        assertTrue(url.contains("/accounts/abc123"),
                "the public token must be interpolated into the base URL, was: " + url);
    }

    @Test
    @Requirement("TBX_074")
    @DisplayName("TBX_074: a newly created basket with \"links\": [] is deserialized")
    void createBasketAcceptsEmptyLinksArray() throws IOException, ApiException {
        HeadlessApi headless = startServer(200);

        BasketResponse response = headless.Headless.createBasket(new CreateBasketRequest()
                .completeUrl("https://example.com/complete")
                .cancelUrl("https://example.com/cancel"));

        Basket basket = response.getData();
        assertEquals("test-basket-ident", basket.getIdent());
        assertEquals("EUR", basket.getCurrency());
        assertNull(basket.getLinks().getCheckout());
        assertNull(basket.getLinks().getPayment());
    }

    @Test
    @Requirement("TBX_076")
    @DisplayName("TBX_076: adding a package returns the basket, which the api wraps in data")
    void addBasketPackageReturnsWrappedBasket() throws IOException, ApiException {
        HeadlessApi headless = startBasketsServer();

        Basket basket = headless.Baskets.addBasketPackage("test-basket-ident",
                new AddBasketPackageRequest().packageId("7693666").quantity(1).dynamic(false)).getData();

        assertEquals("POST /test-basket-ident/packages", lastRequest);
        assertEquals("test-basket-ident", basket.getIdent());
        assertEquals(1, basket.getPackages().size());
        assertEquals(1, basket.getPackages().get(0).getInBasket().getQuantity());
        assertEquals("https://pay.tebex.io/test-basket-ident", basket.getLinks().getCheckout());
        assertEquals(Boolean.FALSE, basket.getPackages().get(0).getIsRecurring());
    }

    @Test
    @Requirement("TBX_076")
    @DisplayName("TBX_076: removing a package returns the basket, which the api wraps in data")
    void removeBasketPackageReturnsWrappedBasket() throws IOException, ApiException {
        HeadlessApi headless = startBasketsServer();

        Basket basket = headless.Baskets.removeBasketPackage("test-basket-ident",
                new RemoveBasketPackageRequest().packageId("7693666")).getData();

        assertEquals("POST /test-basket-ident/packages/remove", lastRequest);
        assertEquals("test-basket-ident", basket.getIdent());
    }

    @Test
    @Requirement("TBX_076")
    @DisplayName("TBX_076: updating a package's quantity targets that package's url")
    void updatePackageQuantityTargetsPackage() throws IOException, ApiException {
        HeadlessApi headless = startBasketsServer();

        headless.Baskets.updatePackageQuantity("test-basket-ident", "7693666",
                new UpdatePackageQuantityRequest().quantity(2));

        assertEquals("PUT /test-basket-ident/packages/7693666", lastRequest);
    }

    @Test
    @Requirement("TBX_074")
    @DisplayName("TBX_074: populated basket links are still deserialized")
    void populatedLinksAreUnchanged() {
        new HeadlessApi("abc123"); // installs the correction
        String json = BASKET_RESPONSE_JSON.replace("\"links\":[]",
                "\"links\":{\"checkout\":\"https://checkout.tebex.io/checkout/example\"}");

        BasketResponse response = JSON.deserialize(json, BasketResponse.class);

        assertEquals("https://checkout.tebex.io/checkout/example", response.getData().getLinks().getCheckout());
    }

    @Test
    @Requirement("TBX_075")
    @DisplayName("TBX_075: fields the contract does not define are kept, not rejected")
    void unknownFieldsAreTolerated() {
        new HeadlessApi("abc123"); // installs the headless json corrections
        String json = BASKET_RESPONSE_JSON.replace("\"currency\":", "\"field_added_later\":\"value\",\"currency\":");

        BasketResponse response = JSON.deserialize(json, BasketResponse.class);

        assertEquals("EUR", response.getData().getCurrency());
        assertEquals("value", response.getData().getAdditionalProperty("field_added_later"));
    }

    @Test
    @Requirement("TBX_077")
    @DisplayName("TBX_077: a store with no login provider yields no login links rather than a parse error")
    void emptyAuthLinksAreDropped() {
        new HeadlessApi("abc123"); // installs the headless json corrections
        Type authLinks = new TypeToken<List<BasketAuthResponseInner>>() { }.getType();

        List<BasketAuthResponseInner> none = JSON.deserialize("[[]]", authLinks);
        List<BasketAuthResponseInner> one = JSON.deserialize(
                "[{\"name\":\"Steam\",\"url\":\"https://example.com/login\"}]", authLinks);

        assertTrue(none.isEmpty(), "an empty-array entry is not a login link");
        assertEquals(1, one.size());
        assertEquals("https://example.com/login", one.get(0).getUrl());
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a rejected request (404) surfaces as a typed ApiException, not a crash")
    void rejectedRequestIsApiException() throws IOException {
        HeadlessApi headless = startServer(404);

        ApiException thrown = assertThrows(ApiException.class,
                () -> headless.Headless.getWebstore());
        assertEquals(404, thrown.getCode());
    }
}
