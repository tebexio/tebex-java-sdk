package io.tebex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.tebex.hooks.ServerCommand;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the {@link TXE} engine: main-thread task execution
 * (TBX_040) and exception recovery (TBX_001), plus a start-to-finish
 * {@code /information} authentication check (TBX_002).
 *
 * <p>Migrated to the reworked engine API: {@code io.tebex.log.TebexLogger} is
 * gone, so log capture now goes through a {@code java.util.logging.Handler}
 * installed on a {@link Logger} handed to {@link Log#SetLogger(Logger)}. Records
 * are bucketed by {@link Level} because {@code Log.Warn} maps to
 * {@code Level.WARNING} — the old capturing logger folded warnings into its info
 * list, which would have let a warning satisfy an info assertion.
 */
class TxeTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}}";

    /** An empty due-players queue with the next check pushed well out of the way. */
    private static final String EMPTY_QUEUE_JSON =
            "{\"meta\":{\"execute_offline\":false,\"next_check\":600,\"more\":false},\"players\":[]}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a stub plugin API answering {@code /information}.
     *
     * @param status the status to return
     * @param body   the body to return
     * @return the stub server's base URL
     * @throws IOException if the server cannot be started
     */
    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        // The engine's tick loop calls /queue after authenticating; without this
        // context the request 404s and the loop logs an error.
        server.createContext("/queue", exchange -> {
            byte[] payload = EMPTY_QUEUE_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * Points the given engine at a stub server and starts it.
     *
     * <p>Configures only the engine under test. It previously had to redirect the
     * process-wide singleton as well, because {@code Plugin} resolved its client
     * through {@code TXE.get()}; now that {@code Plugin} holds a reference to its
     * owning engine, an instance is genuinely isolated.
     *
     * @param txe     the engine under test
     * @param baseUrl the stub server base URL
     * @param key     the secret key to start with
     */
    private static void startEngineAgainst(TXE txe, String baseUrl, String key) {
        txe.setPluginApi(new PluginApi(baseUrl));
        // A real integration installs this during startup; without it the engine
        // correctly refuses to run the queue check and logs an error, which would
        // trip the "no errors" assertions below.
        txe.Plugin().HookServerCommand(command -> { });
        txe.StartPlugin(key);
    }

    /**
     * Gives the engine's background thread a moment to log anything it was going
     * to, so an assertion that nothing was logged is not merely winning a race.
     */
    private static void settle() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Collects everything the engine logs, bucketed by level.
     *
     * <p>{@code java.util.logging} drops records below the logger's level and the
     * default is {@code INFO}, so both the logger and this handler are opened up
     * to {@link Level#ALL} — otherwise {@code Log.Debug}'s {@code FINEST} records
     * would vanish and a test asserting on them would pass vacuously. Parent
     * handlers are detached so the suite does not print to the console.
     */
    private static final class CapturingHandler extends Handler {

        final List<String> infos = new CopyOnWriteArrayList<>();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        /** {@inheritDoc} */
        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            int level = record.getLevel().intValue();
            if (level >= Level.SEVERE.intValue()) {
                errors.add(message);
            } else if (level >= Level.WARNING.intValue()) {
                warnings.add(message);
            } else {
                infos.add(message);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void flush() {
            // Nothing is buffered.
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            // Nothing to release.
        }
    }

    /**
     * Installs a capturing handler on a fresh logger and points the engine at it.
     *
     * <p>Uses a uniquely named logger per call so tests cannot observe each
     * other's records. Note that {@code TXE.log} is {@code static}, so this is
     * process-wide: unlike {@code setPluginApi}, logging is not isolated per
     * engine instance.
     *
     * @param name a discriminator for the logger name
     * @return the handler collecting the engine's output
     */
    private static CapturingHandler captureLogs(String name) {
        Logger logger = Logger.getLogger("tebex-test-" + name + "-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        for (Handler existing : logger.getHandlers()) {
            logger.removeHandler(existing);
        }
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        TXE.Log().SetLogger(logger);
        return handler;
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

    /**
     * Returns an epoch-second due time offset from now.
     *
     * <p>{@code TebexTask} took a {@link java.util.Date} before the rework and now
     * takes epoch seconds.
     *
     * @param offsetSeconds seconds to add to the current time
     * @return the due time in epoch seconds
     */
    private static long dueIn(long offsetSeconds) {
        return Instant.now().getEpochSecond() + offsetSeconds;
    }

    @Test
    @Requirement("TBX_040")
    @DisplayName("TBX_040: a due main-thread task is executed when the host runs the queue")
    void runsDueMainThreadTask() {
        TXE txe = new TXE();
        AtomicBoolean ran = new AtomicBoolean(false);
        TebexTask task = new TebexTask(dueIn(0), () -> ran.set(true));
        txe.queueMainThreadTask(task);

        txe.RunNextMainThreadTask();

        assertTrue(ran.get(), "the queued main-thread task should have run");
        assertTrue(task.didRun(), "the task should be marked as run");
        assertEquals("OK", task.getResult(), "a successful task records an OK result");
    }

    @Test
    @Requirement("TBX_040")
    @DisplayName("TBX_040: a task whose delay has not elapsed is not run yet")
    void doesNotRunTaskBeforeItIsDue() {
        TXE txe = new TXE();
        AtomicBoolean ran = new AtomicBoolean(false);
        txe.queueMainThreadTask(new TebexTask(dueIn(60), () -> ran.set(true)));

        txe.RunNextMainThreadTask();

        assertFalse(ran.get(), "a task that is not yet due must not run");
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a task that throws is captured and does not break the queue")
    void failingTaskIsRecoveredNotThrown() {
        TXE txe = new TXE();
        CapturingHandler logs = captureLogs("failing-task");
        TebexTask boom = new TebexTask(dueIn(0), () -> {
            throw new IllegalStateException("kaboom");
        });
        txe.queueMainThreadTask(boom);

        assertDoesNotThrow(txe::RunNextMainThreadTask, "a failing task must not propagate out of the queue");
        assertTrue(boom.didRun());
        // Captured...
        assertTrue(boom.getResult().startsWith("ERROR"), "the failure is captured in the result");
        // ...and logged (TBX_001 requires captured + logged + recovered). TXE
        // reports this through Log.Warn, so it lands in the warning bucket.
        assertTrue(logs.warnings.stream().anyMatch(m -> m.contains("task failed") && m.contains("kaboom")),
                "the failure must be logged, not silently dropped; warnings: " + logs.warnings);
    }

    @Test
    @Requirement("TBX_002")
    @DisplayName("TBX_002: StartPlugin authenticates via /information and reports the connected store")
    void startAuthenticatesAndReportsStore() throws IOException {
        String baseUrl = startServer(200, INFORMATION_JSON);
        CapturingHandler logs = captureLogs("start-ok");
        TXE txe = new TXE();

        assertDoesNotThrow(() -> startEngineAgainst(txe, baseUrl, "valid-secret"));
        awaitUntil(() -> !logs.infos.isEmpty());
        // Let at least one tick of the engine loop run, then stop it, so the
        // no-errors assertion below covers the loop and not just authentication.
        settle();
        txe.Stop();

        assertTrue(logs.infos.stream().anyMatch(m -> m.contains("Example Store")),
                "StartPlugin should log the connected store name; logged: " + logs.infos);
        // This is the guard that catches the engine loop failing quietly — for
        // example the tick reaching the real API instead of the stub.
        assertTrue(logs.errors.isEmpty(), "a successful start should log no errors: " + logs.errors);
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: StartPlugin recovers from an API failure by logging, not crashing")
    void startRecoversFromApiFailure() throws IOException {
        String baseUrl = startServer(500, "boom");
        CapturingHandler logs = captureLogs("start-fail");
        TXE txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl));

        assertDoesNotThrow(() -> txe.StartPlugin("any-secret"));
        awaitUntil(() -> !logs.errors.isEmpty());
        txe.Stop();

        assertTrue(logs.errors.stream().anyMatch(m -> m.contains("Failed to connect")),
                "a failed start should be logged as an error; errors: " + logs.errors);
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: stopping the engine is a clean shutdown, not a logged failure")
    void stopIsACleanShutdown() throws IOException {
        String baseUrl = startServer(200, INFORMATION_JSON);
        CapturingHandler logs = captureLogs("stop-clean");
        TXE txe = new TXE();

        startEngineAgainst(txe, baseUrl, "valid-secret");
        awaitUntil(() -> !logs.infos.isEmpty());
        // Stop() interrupts the engine thread. The interrupt lands in the tick
        // sleep, which must read as shutdown rather than falling into the failure
        // handler and reporting "Failed to connect to Tebex".
        txe.Stop();
        // Note: Stop() nulls the thread reference before returning, so IsRunning()
        // is false immediately and cannot be awaited on to prove the thread
        // exited. Settle instead, so a shutdown that *did* log a failure is
        // observed rather than raced past.
        settle();

        assertFalse(txe.IsRunning(), "the engine must stop when asked");
        assertTrue(logs.errors.isEmpty(),
                "a normal shutdown must log nothing at error level: " + logs.errors);
    }

    @Test
    @Requirement("TBX_010")
    @DisplayName("TBX_010: forcecheck bypasses next_check and the engine adopts the api's new value")
    void forcecheckBypassesTheBackoff() throws IOException {
        AtomicInteger queueChecks = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> {
            byte[] payload = INFORMATION_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.createContext("/queue", exchange -> {
            queueChecks.incrementAndGet();
            byte[] payload = EMPTY_QUEUE_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        CapturingHandler logs = captureLogs("forcecheck");
        TXE txe = new TXE();
        startEngineAgainst(txe, baseUrl, "valid-secret");

        // The stub answers next_check=600, so after the first check the engine
        // would not look again for ten minutes.
        awaitUntil(() -> queueChecks.get() >= 1);
        int afterFirstCheck = queueChecks.get();
        settle();
        assertEquals(afterFirstCheck, queueChecks.get(),
                "the engine must honour next_check and not poll every tick");

        // Forcecheck must get a check to happen anyway.
        assertTrue(txe.ForceCheckNow(), "a running engine should accept a forced check");
        awaitUntil(() -> queueChecks.get() > afterFirstCheck);
        txe.Stop();

        assertTrue(queueChecks.get() > afterFirstCheck,
                "forcecheck must bypass next_check; checks seen: " + queueChecks.get());
        assertTrue(logs.errors.isEmpty(), "forcing a check should not log errors: " + logs.errors);
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: logging before SetLogger does not throw out of the SDK")
    void loggingWithoutAnInstalledLoggerIsSafe() {
        // The engine logs from its own thread before an integration has had a
        // chance to install a logger; that path must not be an NPE.
        assertDoesNotThrow(() -> {
            TXE.Log().Info("info before SetLogger");
            TXE.Log().Warn("warn before SetLogger");
            TXE.Log().Debug("debug before SetLogger");
            TXE.Log().Error("error before SetLogger", new IllegalStateException("x"));
        });
    }
}
