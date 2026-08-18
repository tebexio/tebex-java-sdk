package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.hooks.ServerCommand;
import io.tebex.http.PluginApi;
import io.tebex.model.PluginEvent;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_059: events taken off a queue to be sent must go back
 * on it when the send fails, rather than being silently lost.
 *
 * <p>Exercised through the running engine, because the drain-and-send happens on
 * the engine tick. The stub server rejects the event endpoints so the failure
 * path is the one under test.
 */
class EventRequeueTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}}";

    private static final String EMPTY_QUEUE_JSON =
            "{\"meta\":{\"execute_offline\":false,\"next_check\":600,\"more\":false},\"players\":[]}";

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
     * Starts an engine against a stub that authenticates and serves an empty queue,
     * but rejects every {@code /events} post.
     *
     * @param eventPosts counts the rejected event posts
     * @return the running engine
     * @throws IOException if the server cannot be started
     */
    private TXE startEngineRejectingEvents(AtomicInteger eventPosts) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> respond(exchange, 200, INFORMATION_JSON));
        server.createContext("/queue", exchange -> respond(exchange, 200, EMPTY_QUEUE_JSON));
        server.createContext("/events", exchange -> {
            eventPosts.incrementAndGet();
            respond(exchange, 500, "nope");
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        txe = new TXE();
        // The log and analytics hosts are pointed at the stub too, so the
        // plugin-log post is captured by the same /events context.
        txe.setPluginApi(new PluginApi(baseUrl, baseUrl, baseUrl, new com.google.gson.Gson()));
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
    @Requirement("TBX_059")
    @Requirement("TBX_033")
    @Requirement("TBX_034")
    @DisplayName("TBX_059: join/leave events that fail to send are put back on the queue")
    void failedPlayerEventsAreRequeued() throws IOException {
        AtomicInteger eventPosts = new AtomicInteger();
        TXE engine = startEngineRejectingEvents(eventPosts);
        Plugin plugin = engine.Plugin();

        plugin.Join("Notch", "uuid-1", "192.168.1.100");
        plugin.Leave("Alex", "uuid-2", "192.168.1.101");
        assertEquals(2, plugin.serverEvents.size(), "both events should be queued to start with");

        // Wait for at least one drain-and-fail cycle.
        awaitUntil(() -> eventPosts.get() > 0);
        // ...and for the requeue, which happens on the API callback thread.
        awaitUntil(() -> plugin.serverEvents.size() == 2);

        assertTrue(eventPosts.get() > 0, "the engine should have attempted to send the events");
        assertEquals(2, plugin.serverEvents.size(),
                "a failed send must return the events to the queue, not discard them");
    }

    @Test
    @Requirement("TBX_059")
    @DisplayName("TBX_059: plugin log events that fail to send are put back on the queue")
    void failedPluginLogsAreRequeued() throws IOException {
        AtomicInteger eventPosts = new AtomicInteger();
        TXE engine = startEngineRejectingEvents(eventPosts);
        Plugin plugin = engine.Plugin();

        plugin.logEvents.add(new PluginEvent(PluginEvent.Level.ERROR, "something broke"));
        assertEquals(1, plugin.logEvents.size());

        awaitUntil(() -> eventPosts.get() > 0);
        awaitUntil(() -> plugin.logEvents.size() == 1);

        assertEquals(1, plugin.logEvents.size(),
                "a failed log send must return the events to the queue, not discard them");
    }

    @Test
    @Requirement("TBX_059")
    @DisplayName("TBX_059: the requeue is capped so a failing endpoint cannot grow the queue without limit")
    void requeueIsBounded() throws IOException {
        AtomicInteger eventPosts = new AtomicInteger();
        TXE engine = startEngineRejectingEvents(eventPosts);
        Plugin plugin = engine.Plugin();

        // Well past the 1000-event cap.
        for (int i = 0; i < 1500; i++) {
            plugin.Join("player" + i, "uuid-" + i, "192.168.1.1");
        }

        awaitUntil(() -> eventPosts.get() > 0);
        // Give the requeue a moment to land on the callback thread.
        awaitUntil(() -> plugin.serverEvents.size() > 0 && plugin.serverEvents.size() <= 1000);

        assertTrue(plugin.serverEvents.size() <= 1000,
                "the queue must be capped, was " + plugin.serverEvents.size());
        assertTrue(plugin.serverEvents.size() > 0,
                "the cap must not discard everything, was " + plugin.serverEvents.size());
    }
}
