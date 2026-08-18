package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.headless.invoker.ApiClient;
import io.tebex.http.HeadlessApi;
import io.tebex.http.PluginApi;
import io.tebex.model.PluginEvent;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the engine's periodic work: that each job runs on its
 * own cadence (TASK_001–TASK_004), that a job which fails does not stop the
 * others or the loop, and that the engine clock can be moved forward so a
 * cadence measured in minutes can be verified in milliseconds (TASK_000).
 *
 * <p>Everything here is asserted against a real running engine and a stub API,
 * because the cadences are a property of the tick loop rather than of any one
 * method. The stub distinguishes the two {@code /events} posts the way the API
 * itself does: player events carry the secret key header, plugin logs go to the
 * log host without one (TBX_053).
 *
 * <p>The store fixture reports {@code log_events: false} so that the engine's own
 * warnings are not collected as plugin logs; that keeps the event counts in these
 * tests to exactly what each test queued. Collection itself is CFG_004's subject,
 * covered in {@link PluginLogTest}.
 */
class EngineTimersTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}}";

    private static final String CATEGORIES_JSON =
            "{\"data\":[{\"id\":1,\"name\":\"Ranks\",\"slug\":\"ranks\",\"order\":1}]}";

    /** The queue response's {@code next_check}, changed per test. */
    private final AtomicInteger nextCheckSeconds = new AtomicInteger(600);

    /** The status the stub answers queue checks with. */
    private final AtomicInteger queueStatus = new AtomicInteger(200);

    /** The status the stub answers event posts with. */
    private final AtomicInteger eventStatus = new AtomicInteger(204);

    private final AtomicInteger queueChecks = new AtomicInteger();
    private final AtomicInteger playerEventPosts = new AtomicInteger();
    private final AtomicInteger pluginLogPosts = new AtomicInteger();
    private final AtomicInteger categoryCalls = new AtomicInteger();

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
     * @param body     the body, may be {@code null}
     * @throws IOException if writing fails
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : payload.length);
        if (status != 204 && payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    /**
     * Starts a stub serving every endpoint the tick loop touches, and an engine
     * pointed at it.
     *
     * @return the running engine
     * @throws IOException if the server cannot be started
     */
    private TXE startEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> respond(exchange, 200, INFORMATION_JSON));
        server.createContext("/queue", exchange -> {
            queueChecks.incrementAndGet();
            int status = queueStatus.get();
            respond(exchange, status, status == 200
                    ? "{\"meta\":{\"execute_offline\":false,\"next_check\":" + nextCheckSeconds.get()
                            + ",\"more\":false},\"players\":[]}"
                    : "boom");
        });
        server.createContext("/events", exchange -> {
            // Player events are authenticated; plugin logs are posted to the log
            // host without a secret. That is the only difference visible here,
            // and it is the same distinction the API itself makes.
            if (exchange.getRequestHeaders().getFirst("X-Tebex-Secret") == null) {
                pluginLogPosts.incrementAndGet();
            } else {
                playerEventPosts.incrementAndGet();
            }
            respond(exchange, eventStatus.get(), eventStatus.get() == 204 ? null : "nope");
        });
        server.createContext("/categories", exchange -> {
            categoryCalls.incrementAndGet();
            respond(exchange, 200, CATEGORIES_JSON);
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        txe = new TXE();
        txe.setPluginApi(new PluginApi(baseUrl, baseUrl, baseUrl, new Gson()));
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
     * Gives the engine time for at least two ticks, so "this did not happen"
     * assertions are not merely winning a race with a one-second loop.
     */
    private static void settleTwoTicks() {
        try {
            Thread.sleep(2200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Requirement("TASK_000")
    @Requirement("TBX_009")
    @DisplayName("TASK_000: fast-forwarding the engine clock brings a scheduled check forward")
    void fastForwardBringsTheNextCheckForward() throws IOException {
        nextCheckSeconds.set(600);
        TXE engine = startEngine();

        awaitUntil(() -> queueChecks.get() >= 1);
        int afterFirst = queueChecks.get();
        settleTwoTicks();
        assertEquals(afterFirst, queueChecks.get(),
                "the engine must wait out next_check rather than polling every tick");

        // Ten minutes of waiting, without ten minutes of waiting.
        engine.fastForward(600);
        awaitUntil(() -> queueChecks.get() > afterFirst);

        assertTrue(queueChecks.get() > afterFirst,
                "the check must run once its interval has elapsed on the engine clock");
    }

    @Test
    @Requirement("TASK_001")
    @DisplayName("TASK_001: with no usable next_check the queue falls back to a two-minute cadence")
    void queueCheckFallsBackToTwoMinutes() throws IOException {
        // A next_check of zero would mean "check again immediately", which on a
        // one-second tick is a request per second for as long as the store keeps
        // saying it.
        nextCheckSeconds.set(0);
        TXE engine = startEngine();

        awaitUntil(() -> queueChecks.get() >= 1);
        int afterFirst = queueChecks.get();
        settleTwoTicks();
        assertEquals(afterFirst, queueChecks.get(), "a zero next_check must not become a per-tick poll");

        engine.fastForward(60);
        settleTwoTicks();
        assertEquals(afterFirst, queueChecks.get(), "the fallback cadence is two minutes, not one");

        engine.fastForward(60);
        awaitUntil(() -> queueChecks.get() > afterFirst);
        assertTrue(queueChecks.get() > afterFirst, "the check must run again after two minutes");
    }

    @Test
    @Requirement("TASK_001")
    @DisplayName("TASK_001: a failing queue check does not stop the loop from checking again")
    void queueCheckKeepsGoingAfterAFailure() throws IOException {
        queueStatus.set(500);
        TXE engine = startEngine();

        awaitUntil(() -> queueChecks.get() >= 1);
        // Settle before moving the clock: the failed check sets its backoff after
        // the exception surfaces, so fast-forwarding the instant the request is
        // counted could land before the backoff is even set.
        settleTwoTicks();
        int afterFailure = queueChecks.get();
        assertEquals(1, afterFailure, "a failure must back off rather than retry every tick");

        engine.fastForward(TXE.QUEUE_CHECK_SECONDS);
        awaitUntil(() -> queueChecks.get() > afterFailure);

        assertTrue(queueChecks.get() > afterFailure, "a failed check must be retried, not abandoned");
        assertTrue(engine.IsRunning(), "a failed check must not stop the engine");
    }

    @Test
    @Requirement("TASK_002")
    @Requirement("TBX_033")
    @DisplayName("TASK_002: player joins and leaves are sent on a sixty-second cadence")
    void playerEventsAreSentEverySixtySeconds() throws IOException {
        nextCheckSeconds.set(600);
        TXE engine = startEngine();
        Plugin plugin = engine.Plugin();

        plugin.Join("Notch", "uuid-1", "192.168.1.100");
        awaitUntil(() -> playerEventPosts.get() >= 1);
        assertEquals(1, playerEventPosts.get(), "the first batch goes out on the next tick");

        // A second event right behind it must wait for the cadence rather than
        // costing another request immediately.
        plugin.Leave("Notch", "uuid-1", "192.168.1.100");
        settleTwoTicks();
        assertEquals(1, playerEventPosts.get(), "events queued after a send must wait for the next interval");
        assertEquals(1, plugin.serverEvents.size(), "the waiting event must stay queued, not be dropped");

        engine.fastForward(TXE.PLAYER_EVENT_SECONDS);
        awaitUntil(() -> playerEventPosts.get() >= 2);
        assertEquals(2, playerEventPosts.get(), "the next batch goes out once the interval has elapsed");
    }

    @Test
    @Requirement("TASK_002")
    @DisplayName("TASK_002: a failing events endpoint does not stop the flush from running again")
    void playerEventFlushKeepsGoingAfterAFailure() throws IOException {
        eventStatus.set(500);
        TXE engine = startEngine();
        Plugin plugin = engine.Plugin();

        plugin.Join("Notch", "uuid-1", "192.168.1.100");
        awaitUntil(() -> playerEventPosts.get() >= 1);
        int afterFailure = playerEventPosts.get();
        // The requeue happens on the API callback thread (TBX_059).
        awaitUntil(() -> !plugin.serverEvents.isEmpty());

        engine.fastForward(TXE.PLAYER_EVENT_SECONDS);
        awaitUntil(() -> playerEventPosts.get() > afterFailure);

        assertTrue(playerEventPosts.get() > afterFailure, "a failed send must be retried on the next interval");
        assertTrue(engine.IsRunning(), "a failed send must not stop the engine");
    }

    @Test
    @Requirement("TASK_003")
    @DisplayName("TASK_003: plugin logs are sent on a two-minute cadence")
    void pluginLogsAreSentEveryTwoMinutes() throws IOException {
        nextCheckSeconds.set(600);
        TXE engine = startEngine();
        Plugin plugin = engine.Plugin();

        plugin.logEvents.add(new PluginEvent(PluginEvent.Level.ERROR, "something broke"));
        awaitUntil(() -> pluginLogPosts.get() >= 1);
        assertEquals(1, pluginLogPosts.get(), "the first report goes out on the next tick");

        plugin.logEvents.add(new PluginEvent(PluginEvent.Level.WARNING, "something else"));
        settleTwoTicks();
        assertEquals(1, pluginLogPosts.get(), "reports queued after a send must wait for the next interval");

        engine.fastForward(TXE.LOG_EVENT_SECONDS);
        awaitUntil(() -> pluginLogPosts.get() >= 2);
        assertEquals(2, pluginLogPosts.get(), "the next report goes out once the interval has elapsed");
    }

    @Test
    @Requirement("TASK_003")
    @DisplayName("TASK_003: a failing log host does not stop the flush from running again")
    void pluginLogFlushKeepsGoingAfterAFailure() throws IOException {
        eventStatus.set(500);
        TXE engine = startEngine();
        Plugin plugin = engine.Plugin();

        plugin.logEvents.add(new PluginEvent(PluginEvent.Level.ERROR, "something broke"));
        awaitUntil(() -> pluginLogPosts.get() >= 1);
        int afterFailure = pluginLogPosts.get();
        awaitUntil(() -> !plugin.logEvents.isEmpty());

        engine.fastForward(TXE.LOG_EVENT_SECONDS);
        awaitUntil(() -> pluginLogPosts.get() > afterFailure);

        assertTrue(pluginLogPosts.get() > afterFailure, "a failed report must be retried on the next interval");
        assertTrue(engine.IsRunning(), "a failed report must not stop the engine");
    }

    @Test
    @Requirement("TASK_004")
    @Requirement("TBX_061")
    @DisplayName("TASK_004: the store catalogue is refreshed every five minutes")
    void storeIsRefreshedEveryFiveMinutes() throws IOException {
        nextCheckSeconds.set(600);
        TXE engine = startEngine();

        awaitUntil(() -> categoryCalls.get() >= 1);
        assertEquals(1, categoryCalls.get(), "the catalogue is fetched once at startup");

        settleTwoTicks();
        assertEquals(1, categoryCalls.get(), "the catalogue must not be re-fetched on every tick");

        engine.fastForward(TXE.STORE_REFRESH_SECONDS);
        awaitUntil(() -> categoryCalls.get() >= 2);

        assertEquals(2, categoryCalls.get(), "the catalogue must be refreshed once the interval elapses");
        assertTrue(engine.IsRunning());
    }
}
