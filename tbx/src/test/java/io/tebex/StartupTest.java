package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.hooks.Configuration;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for what happens as the engine starts: a key held in the
 * integration's configuration is authenticated and loads the store (TBX_004),
 * and a key belonging to another game's store is refused (TBX_006).
 *
 * <p>Both are about credentials being adopted or not, so each test asserts on
 * {@code Plugin}'s state rather than only on the log: a message saying the key
 * was refused would mean nothing if the key had been adopted anyway.
 */
class StartupTest {

    private static final String JAVA_STORE_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"},\"public_token\":\"abcd-1234567890\"}";

    private static final String BEDROCK_STORE_JSON =
            JAVA_STORE_JSON.replace("Minecraft: Java Edition", "Minecraft: Bedrock Edition");

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

    /** An in-memory {@link Configuration} holding whatever a test puts in it. */
    private static final class FakeConfig implements Configuration {
        final Map<String, String> values = new LinkedHashMap<>();

        /** {@inheritDoc} */
        @Override
        public String Set(String key, String value) {
            return values.put(key, value);
        }

        /** {@inheritDoc} */
        @Override
        public String Get(String key) {
            return values.get(key);
        }

        /** {@inheritDoc} */
        @Override
        public void Save() {
        }

        /** {@inheritDoc} */
        @Override
        public void Load() {
        }
    }

    /**
     * Writes a response and closes the exchange.
     *
     * @param exchange the exchange to answer
     * @param body     the body
     * @throws IOException if writing fails
     */
    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }

    /**
     * Starts a stub store and an engine pointed at it, without starting the
     * engine.
     *
     * @param informationJson the {@code /information} payload the store answers
     * @return the configured engine
     * @throws IOException if the server cannot be started
     */
    private TXE engineAgainst(String informationJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> respond(exchange, informationJson));
        server.createContext("/queue", exchange -> respond(exchange,
                "{\"meta\":{\"execute_offline\":false,\"next_check\":600,\"more\":false},\"players\":[]}"));
        server.createContext("/events", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl, baseUrl, baseUrl, new Gson()));
        txe.Plugin().HookServerCommand(command -> { });
        return txe;
    }

    /**
     * Routes engine output to a fresh logger and returns the list collecting
     * error-level messages.
     *
     * @return the captured errors, appended to as the engine runs
     */
    private static List<String> captureErrors() {
        List<String> errors = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("tebex-startup-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    errors.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        TXE.Log().SetLogger(logger);
        return errors;
    }

    /**
     * Waits up to five seconds for a condition to hold.
     *
     * @param condition the condition to await
     */
    private static void awaitUntil(BooleanSupplier condition) {
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

    /**
     * Gives the engine a moment to do anything it was going to, so a "this did
     * not happen" assertion is not merely winning a race.
     */
    private static void settle() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Requirement("TBX_004")
    @DisplayName("TBX_004: a key held in configuration is authenticated and loads the store")
    void configuredKeyIsAuthenticatedAtStartup() throws IOException {
        TXE engine = engineAgainst(JAVA_STORE_JSON);
        FakeConfig config = new FakeConfig();
        config.values.put(Plugin.CONFIG_SECRET_KEY, "configured-secret");
        engine.Plugin().HookConfig(config);

        engine.StartPlugin();

        awaitUntil(() -> engine.Plugin().account != null);
        assertEquals("configured-secret", engine.Plugin().key, "the configured key must be adopted");
        assertEquals("Example Store", engine.Plugin().account.getName(),
                "the store information must be loaded alongside it");
        assertEquals("My Server", engine.Plugin().server.getName());
    }

    @Test
    @Requirement("TBX_004")
    @DisplayName("TBX_004: starting with no configured key reports it instead of connecting")
    void missingConfiguredKeyIsReported() throws IOException {
        TXE engine = engineAgainst(JAVA_STORE_JSON);
        List<String> errors = captureErrors();
        engine.Plugin().HookConfig(new FakeConfig()); // hook installed, but empty

        engine.StartPlugin();
        settle();

        assertFalse(engine.IsRunning(), "there is nothing to connect with, so nothing may start");
        assertTrue(errors.stream().anyMatch(m -> m.contains("No secret key is configured")),
                "the operator must be told what to do; errors: " + errors);
    }

    @Test
    @Requirement("TBX_006")
    @DisplayName("TBX_006: a key for another game's store is refused at startup")
    void mismatchedGameTypeIsRefusedAtStartup() throws IOException {
        TXE engine = engineAgainst(BEDROCK_STORE_JSON);
        List<String> errors = captureErrors();
        engine.Plugin().ExpectGameType("Minecraft: Java Edition");

        engine.StartPlugin("valid-secret");

        awaitUntil(() -> !errors.isEmpty());
        settle();

        assertTrue(errors.stream().anyMatch(m -> m.contains("Bedrock")),
                "the mismatch must be reported with what was found; errors: " + errors);
        assertNull(engine.Plugin().account, "a store of the wrong game type must not be adopted");
        assertEquals("", engine.Plugin().key, "the key must not be adopted either");
        assertFalse(engine.IsRunning(), "the engine must not run against a store it cannot serve");
    }

    @Test
    @Requirement("TBX_006")
    @DisplayName("TBX_006: a key for the expected game type is accepted")
    void matchingGameTypeIsAccepted() throws IOException {
        TXE engine = engineAgainst(JAVA_STORE_JSON);
        engine.Plugin().ExpectGameType("minecraft: java edition"); // matched case-insensitively

        engine.StartPlugin("valid-secret");

        awaitUntil(() -> engine.Plugin().account != null);
        assertEquals("valid-secret", engine.Plugin().key, "a matching store must be adopted");
    }

    @Test
    @Requirement("TBX_006")
    @Requirement("TBX_005")
    @DisplayName("TBX_006: /tebex secret refuses a key for another game's store")
    void mismatchedGameTypeIsRefusedByTheSecretCommand() throws Exception {
        TXE engine = engineAgainst(BEDROCK_STORE_JSON);
        Plugin plugin = engine.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);
        plugin.ExpectGameType("Minecraft: Java Edition");
        plugin.applyCredentials("original-key", null, null);

        String reply = String.join("\n", plugin.Input("/tebex secret bedrock-key", ""));

        assertTrue(reply.contains("Bedrock"), "the reply must say what the key resolved to; got: " + reply);
        assertEquals("original-key", plugin.key, "the working key must be left in place");
        assertFalse(config.values.containsKey(Plugin.CONFIG_SECRET_KEY),
                "a refused key must not be persisted");
    }

    @Test
    @Requirement("TBX_006")
    @DisplayName("TBX_006: with no expected game type declared, any store is accepted")
    void noExpectedGameTypeAcceptsAnyStore() throws IOException {
        TXE engine = engineAgainst(BEDROCK_STORE_JSON);

        engine.StartPlugin("valid-secret");

        awaitUntil(() -> engine.Plugin().account != null);
        assertEquals("Minecraft: Bedrock Edition", engine.Plugin().account.getGameType(),
                "an integration that declares nothing must not be gated");
    }
}
