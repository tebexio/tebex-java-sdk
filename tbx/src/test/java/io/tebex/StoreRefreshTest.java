package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.headless.invoker.ApiClient;
import io.tebex.http.HeadlessApi;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_061: the engine refreshes the store catalogue from
 * the Headless API and caches it, and a failed refresh keeps the previous
 * catalogue without stopping the tick loop.
 *
 * <p>Note what these do <em>not</em> assert: TASK_004's five-minute cadence, which
 * is covered in {@link EngineTimersTest} against the engine clock. These cover the
 * refresh behaviour, not its timing.
 */
class StoreRefreshTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"},\"public_token\":\"abcd-1234567890\"}";

    private static final String EMPTY_QUEUE_JSON =
            "{\"meta\":{\"execute_offline\":false,\"next_check\":600,\"more\":false},\"players\":[]}";

    private static final String CATEGORIES_JSON =
            "{\"data\":[{\"id\":1,\"name\":\"Ranks\",\"slug\":\"ranks\",\"order\":1},"
            + "{\"id\":2,\"name\":\"Kits\",\"slug\":\"kits\",\"order\":2}]}";

    private HttpServer server;
    private TXE txe;

    @AfterEach
    void tearDown() {
        if (txe != null) {
            txe.Stop();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Writes a response and closes the exchange.
     *
     * @param exchange the exchange to answer
     * @param status   the status code
     * @param body     the body
     * @throws IOException if writing fails
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }

    /**
     * Starts an engine whose plugin API and Headless client both point at one stub.
     *
     * @param categoriesStatus the status the categories endpoint answers with
     * @param categoriesBody   the body the categories endpoint answers with
     * @param categoryCalls    counts calls to the categories endpoint
     * @return the running engine
     * @throws IOException if the server cannot be started
     */
    private TXE startEngine(AtomicInteger categoriesStatus, AtomicReference<String> categoriesBody,
                            AtomicInteger categoryCalls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> respond(exchange, 200, INFORMATION_JSON));
        server.createContext("/queue", exchange -> respond(exchange, 200, EMPTY_QUEUE_JSON));
        server.createContext("/categories", exchange -> {
            categoryCalls.incrementAndGet();
            respond(exchange, categoriesStatus.get(), categoriesBody.get());
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl));
        txe.setHeadlessApi(new HeadlessApi(new ApiClient().setBasePath(baseUrl)));
        txe.Plugin().HookServerCommand(command -> { });
        txe.StartPlugin("valid-secret");
        return txe;
    }

    /**
     * Waits up to five seconds for a condition to hold.
     *
     * @param condition the condition to await
     */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @Requirement("TBX_061")
    @DisplayName("TBX_061: the engine refreshes the catalogue and caches it for callers")
    void catalogueIsFetchedAndCached() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        TXE engine = startEngine(new AtomicInteger(200),
                new AtomicReference<>(CATEGORIES_JSON), calls);
        Plugin plugin = engine.Plugin();

        // Before the first refresh the accessor must be empty, not null.
        awaitUntil(() -> !plugin.Categories().isEmpty());

        assertTrue(calls.get() > 0, "the engine should have called the Headless categories endpoint");
        assertEquals(2, plugin.Categories().size(), "the catalogue must be cached for callers");
        assertEquals("Ranks", plugin.Categories().get(0).getName());
        assertEquals("Kits", plugin.Categories().get(1).getName());
    }

    @Test
    @Requirement("TBX_061")
    @DisplayName("TBX_061: an unstarted engine reports an empty catalogue rather than null")
    void catalogueIsEmptyBeforeAnyRefresh() {
        assertTrue(new TXE().Plugin().Categories().isEmpty(),
                "callers must be able to read the catalogue before the first refresh");
    }

    @Test
    @Requirement("TBX_061")
    @DisplayName("TBX_061: a failed refresh keeps the previous catalogue and the loop keeps running")
    void failedRefreshKeepsPreviousCatalogue() throws IOException {
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> body = new AtomicReference<>(CATEGORIES_JSON);
        AtomicInteger calls = new AtomicInteger();
        List<String> warnings = captureWarnings();
        TXE engine = startEngine(status, body, calls);
        Plugin plugin = engine.Plugin();

        awaitUntil(() -> !plugin.Categories().isEmpty());
        assertEquals(2, plugin.Categories().size());

        // Break the endpoint, then force a refresh. Waiting on the warning rather
        // than on a call counter avoids a race: a refresh already in flight when
        // the status flips would make the counter move without a failure having
        // happened. The warning is emitted only by refreshStore's own
        // `catch (ApiException)`, so seeing it also proves the failure took that
        // path rather than escaping to the tick loop's catch-all.
        status.set(500);
        body.set("boom");
        engine.refreshStoreNow();
        awaitUntil(() -> warnings.stream().anyMatch(m -> m.contains("Could not refresh the store catalogue")));

        assertTrue(warnings.stream().anyMatch(m -> m.contains("Could not refresh the store catalogue")),
                "the failure must be reported by refreshStore itself; warnings: " + warnings);
        assertEquals(2, plugin.Categories().size(),
                "a failed refresh must keep the previous catalogue, not clear it");
        assertTrue(engine.IsRunning(), "a failed refresh must not stop the engine loop");
    }

    /**
     * Routes engine output to a fresh logger and returns the list collecting
     * warning-level messages.
     *
     * @return the captured warnings, appended to as the engine runs
     */
    private static List<String> captureWarnings() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("tebex-store-refresh-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        TXE.Log().SetLogger(logger);
        return warnings;
    }
}
