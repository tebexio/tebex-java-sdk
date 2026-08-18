package io.tebex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.tebex.headless.invoker.ApiClient;
import io.tebex.headless.invoker.ApiException;
import io.tebex.headless.model.Category;
import io.tebex.headless.model.ModelPackage;
import io.tebex.headless.model.Webstore;
import io.tebex.requirements.Requirement;
import java.io.IOException;
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

    private HttpServer server;

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
            String body;
            if (path.equals("/categories")) {
                body = CATEGORIES_JSON;
            } else if (path.equals("/packages")) {
                body = PACKAGES_JSON;
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
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a rejected request (404) surfaces as a typed ApiException, not a crash")
    void rejectedRequestIsApiException() throws IOException {
        HeadlessApi headless = startServer(404);

        ApiException thrown = assertThrows(ApiException.class,
                () -> headless.Headless.getWebstore());
        assertEquals(404, thrown.getCode());
    }
}
