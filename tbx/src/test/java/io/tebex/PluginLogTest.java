package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.tebex.exception.AuthenticationException;
import io.tebex.exception.TebexException;
import io.tebex.hooks.Configuration;
import io.tebex.http.PluginApi;
import io.tebex.model.PluginEvent;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the plugin-log pipeline: what the SDK logs becomes a
 * {@link PluginEvent} for Tebex (TBX_011, TBX_012), a timeout is reported as a
 * warning rather than an error (TBX_013), a failed API call is not reported at
 * all (TBX_008), and the whole thing can be switched off (CFG_004).
 *
 * <p>Most of these go through a private {@link Log} with its own sink rather than
 * the process-wide {@code TXE.Log()}: the level and suppression decisions are
 * {@code Log}'s, and a private instance keeps one test's events out of another's.
 * The two that are about the <em>engine</em> — that it binds the plugin's queue
 * while running, and that an authentication failure produces no report — use a
 * real engine against a stub.
 */
class PluginLogTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":true},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}}";

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
     * Returns a {@link Log} that writes nowhere visible and records the events it
     * derives into {@code collected}.
     *
     * @param collected the list to collect derived events into
     * @return the configured log
     */
    private static Log logInto(List<PluginEvent> collected) {
        Log log = new Log();
        Logger quiet = Logger.getLogger("tebex-plugin-log-test-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        log.SetLogger(quiet);
        log.SetSink(collected::add);
        return log;
    }

    /**
     * Starts a stub answering {@code /information} with the given status, plus
     * the endpoints a running engine's tick loop touches.
     *
     * <p>The {@code /events} context matters even where a test expects nothing to
     * be reported: without it the engine would post to the production log host.
     *
     * @param status the status to answer with
     * @param body   the body to answer with
     * @return the stub base URL
     * @throws IOException if the server cannot be started
     */
    private String startInformationStub(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.createContext("/queue", exchange -> {
            byte[] payload = ("{\"meta\":{\"execute_offline\":false,\"next_check\":600,\"more\":false},"
                    + "\"players\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.createContext("/events", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
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

    @Test
    @Requirement("TBX_011")
    @DisplayName("TBX_011: a warning passing through the harness becomes a plugin log")
    void warningsBecomePluginLogs() {
        List<PluginEvent> collected = new CopyOnWriteArrayList<>();
        Log log = logInto(collected);

        log.Warn("the queue is filling up");

        assertEquals(1, collected.size(), "a warning must produce exactly one event: " + collected);
        assertEquals(PluginEvent.Level.WARNING, collected.get(0).getEventLevel());
        assertEquals("the queue is filling up", collected.get(0).getEventMessage());
    }

    @Test
    @Requirement("TBX_011")
    @DisplayName("TBX_011: routine progress messages are not reported to Tebex")
    void informationalMessagesAreNotReported() {
        List<PluginEvent> collected = new CopyOnWriteArrayList<>();
        Log log = logInto(collected);

        log.Info("Connected to store 'Example Store'.");

        assertTrue(collected.isEmpty(),
                "info is local output; reporting it would cost a request per tick: " + collected);
    }

    @Test
    @Requirement("TBX_012")
    @DisplayName("TBX_012: an error passing through the harness becomes a plugin log with its trace")
    void errorsBecomePluginLogs() {
        List<PluginEvent> collected = new CopyOnWriteArrayList<>();
        Log log = logInto(collected);

        log.Error("a queued task failed", new IllegalStateException("delivery blew up"));

        assertEquals(1, collected.size(), "an error must produce exactly one event: " + collected);
        PluginEvent event = collected.get(0);
        assertEquals(PluginEvent.Level.ERROR, event.getEventLevel());
        assertEquals("a queued task failed", event.getEventMessage());
        assertTrue(event.getTrace().contains("delivery blew up"),
                "the trace must be attached so the report is actionable: " + event.getTrace());
    }

    @Test
    @Requirement("TBX_012")
    @DisplayName("TBX_012: an error with no throwable is still reported")
    void errorsWithoutAThrowableAreReported() {
        List<PluginEvent> collected = new CopyOnWriteArrayList<>();
        Log log = logInto(collected);

        // The SDK's own "no hook installed" errors pass null here.
        log.Error("Cannot deliver commands: no ServerCommand hook is installed.", null);

        assertEquals(1, collected.size(), "a message-only error must still be reported: " + collected);
        assertEquals(PluginEvent.Level.ERROR, collected.get(0).getEventLevel());
    }

    @Test
    @Requirement("TBX_013")
    @DisplayName("TBX_013: a timeout is reported as a warning, not an error")
    void timeoutsAreReportedAsWarnings() {
        List<PluginEvent> collected = new CopyOnWriteArrayList<>();
        Log log = logInto(collected);

        // Shaped exactly as the SDK produces one: the socket timeout arrives
        // wrapped in the client's own exception type.
        log.Error("Failed to connect to Tebex: Read timed out",
                new CompletionException(new TebexException("Failed to call /information",
                        new SocketTimeoutException("Read timed out"))));
        log.Error("waiting for the api took too long", new TimeoutException("timed out"));

        assertEquals(2, collected.size(), "both timeouts must be reported: " + collected);
        for (PluginEvent event : collected) {
            assertEquals(PluginEvent.Level.WARNING, event.getEventLevel(),
                    "a timeout is transient and must be reported as a warning: " + event);
        }
    }

    @Test
    @Requirement("TBX_008")
    @DisplayName("TBX_008: a failed api call does not create a plugin log")
    void failedApiCallsAreNotReported() {
        List<PluginEvent> collected = new CopyOnWriteArrayList<>();
        Log log = logInto(collected);

        log.Error("Failed to connect to Tebex: The provided secret key was rejected (HTTP 403).",
                new CompletionException(new AuthenticationException("rejected")));
        log.Error("something went wrong talking to the store",
                new TebexException("Unexpected status code from /queue (HTTP 500)."));

        assertTrue(collected.isEmpty(),
                "reporting an api failure runs over the same credentials that just failed: " + collected);
    }

    @Test
    @Requirement("TBX_008")
    @Requirement("TBX_012")
    @DisplayName("TBX_008: a rejected secret key is logged locally but reported to nobody")
    void rejectedKeyIsLoggedButNotReported() throws IOException {
        String baseUrl = startInformationStub(403, "Forbidden");
        List<String> errors = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("tebex-rejected-key-" + System.nanoTime());
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

        txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl, baseUrl, baseUrl, new com.google.gson.Gson()));
        txe.StartPlugin("bad-key");

        awaitUntil(() -> !errors.isEmpty());

        assertTrue(errors.stream().anyMatch(m -> m.contains("Failed to connect")),
                "the operator must still be told locally; errors: " + errors);
        assertTrue(txe.Plugin().logEvents.isEmpty(),
                "the failure must not be queued for the plugin-logs service: " + txe.Plugin().logEvents);
    }

    @Test
    @Requirement("TBX_012")
    @Requirement("TBX_037")
    @DisplayName("TBX_012: a running engine collects what it logs onto the plugin's queue")
    void runningEngineCollectsItsOwnLogs() throws IOException {
        String baseUrl = startInformationStub(200, INFORMATION_JSON);
        txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl, baseUrl, baseUrl, new com.google.gson.Gson()));
        txe.Plugin().HookServerCommand(command -> { });
        txe.StartPlugin("valid-secret");

        // Wait for authentication, so the store this event is attributed to is
        // known by the time it is recorded.
        awaitUntil(() -> txe.Plugin().account != null);
        TXE.Log().Warn("something the host should know about");

        awaitUntil(() -> !txe.Plugin().logEvents.isEmpty());
        assertFalse(txe.Plugin().logEvents.isEmpty(),
                "a running engine must route what it logs to the outbound queue");

        // ...and stop collecting once it is stopped.
        txe.Stop();
        int afterStop = txe.Plugin().logEvents.size();
        TXE.Log().Warn("logged after the engine stopped");
        assertEquals(afterStop, txe.Plugin().logEvents.size(),
                "a stopped engine must not keep queueing events nothing will send");
    }

    @Test
    @Requirement("CFG_004")
    @DisplayName("CFG_004: log collection can be switched off in configuration")
    void logCollectionCanBeDisabledInConfig() {
        TXE engine = new TXE();
        Plugin plugin = engine.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);

        // Default: no key at all means collection is on.
        assertTrue(plugin.collectsLogEvents(), "collection must default to on");
        plugin.recordLogEvent(new PluginEvent(PluginEvent.Level.ERROR, "collected"));
        assertEquals(1, plugin.logEvents.size(), "an event must be collected by default");

        config.values.put(Plugin.CONFIG_LOG_EVENTS, "false");
        assertFalse(plugin.collectsLogEvents(), "the configuration must be able to switch collection off");
        plugin.recordLogEvent(new PluginEvent(PluginEvent.Level.ERROR, "dropped"));
        assertEquals(1, plugin.logEvents.size(), "nothing may be queued while collection is off");

        config.values.put(Plugin.CONFIG_LOG_EVENTS, "true");
        assertTrue(plugin.collectsLogEvents(), "switching it back on must take effect without a restart");
        plugin.recordLogEvent(new PluginEvent(PluginEvent.Level.ERROR, "collected again"));
        assertEquals(2, plugin.logEvents.size());
    }

    @Test
    @Requirement("CFG_004")
    @DisplayName("CFG_004: a store with event logging disabled is respected too")
    void storeCanDisableLogCollection() throws IOException {
        String storeWithLoggingOff = INFORMATION_JSON.replace("\"log_events\":true", "\"log_events\":false");
        String baseUrl = startInformationStub(200, storeWithLoggingOff);
        txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl, baseUrl, baseUrl, new com.google.gson.Gson()));
        txe.Plugin().HookServerCommand(command -> { });
        txe.StartPlugin("valid-secret");

        awaitUntil(() -> txe.Plugin().account != null);

        assertFalse(txe.Plugin().collectsLogEvents(),
                "a store that has turned event logging off must not have events collected for it");
        TXE.Log().Warn("something the host should know about");
        assertTrue(txe.Plugin().logEvents.isEmpty(),
                "nothing may be queued for a store that does not want it: " + txe.Plugin().logEvents);
    }
}
