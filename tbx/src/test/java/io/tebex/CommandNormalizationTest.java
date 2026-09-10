package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_068: a deliverable reaches the host's command hook
 * in a form the host can actually parse, and one left with nothing to run never
 * reaches it at all but is still marked complete.
 *
 * <p>Regression cover for a real incident. A store queued a command whose line
 * was empty; the plugin dispatched it anyway, and the host's dispatcher — which
 * splits the line on spaces and reads element zero — threw
 * {@code ArrayIndexOutOfBoundsException} out of the tick that ran it. Because
 * the throw happened before the command was marked executed, it was never
 * deleted from the queue, so the next check fetched it again, and the next: one
 * bad command became an endless stream of identical stack traces. Both halves
 * are pinned here — never dispatched, and always acknowledged.
 *
 * <p>The near-misses are pinned alongside it, because they are the same defect
 * without the crash: padding and a leading slash both move the command's name
 * out of element zero, so the delivery is swallowed as an unknown command
 * instead of throwing.
 */
class CommandNormalizationTest {

    private static final String DUE_PLAYERS_JSON =
            "{\"meta\":{\"execute_offline\":true,\"next_check\":90,\"more\":false},\"players\":[]}";

    private static final String PLAYER_JSON = "\"player\":{\"id\":3,\"name\":\"Notch\",\"uuid\":\"abc\"}";

    // One queue holding every shape the store puts in this field: blank in two
    // ways, padded, slash-prefixed the way an operator types it, double-slashed
    // because the command's own name begins with one, and a lone slash that
    // names nothing at all.
    private static final String OFFLINE_COMMANDS_JSON =
            "{\"meta\":{\"limited\":false},\"commands\":["
            + command(1, "")
            + "," + command(2, "   ")
            + "," + command(3, "  give Notch diamond  ")
            + "," + command(4, "/give Notch emerald")
            + "," + command(5, "//set stone")
            + "," + command(6, "/")
            + "]}";

    // What the host must be handed, in queue order: the three runnable commands,
    // normalised.
    private static final List<String> EXPECTED =
            Arrays.asList("give Notch diamond", "give Notch emerald", "/set stone");

    private final TXE txe = new TXE();
    private final AtomicInteger offlineQueueFetches = new AtomicInteger();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Renders one queued command as the offline queue endpoint returns it.
     *
     * @param id   the command id
     * @param line the command line, as the store saved it
     * @return the command's json object
     */
    private static String command(int id, String line) {
        return "{\"id\":" + id + ",\"command\":\"" + line + "\","
                + "\"conditions\":{\"delay\":0,\"slots\":0}," + PLAYER_JSON + "}";
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
     * Starts a stub serving the given offline queue and points this test's engine
     * at it.
     *
     * @param offlineCommands the {@code /queue/offline-commands} payload
     * @throws IOException if the server cannot be started
     */
    private void stubQueue(String offlineCommands) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/queue", exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/queue/offline-commands")) {
                offlineQueueFetches.incrementAndGet();
                respond(exchange, 200, offlineCommands);
                return;
            }
            respond(exchange, 200, DUE_PLAYERS_JSON);
        });
        server.start();
        txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
    }

    /**
     * Returns a connected plugin whose command hook records what it is given.
     *
     * @param executed where dispatched command lines are recorded
     * @return the plugin under test
     */
    private Plugin connectedPlugin(List<String> executed) {
        Plugin plugin = txe.Plugin();
        plugin.HookServerCommand(executed::add);
        plugin.applyCredentials("valid-secret", null, null);
        return plugin;
    }

    /**
     * Drains the task queue, with room to spare so a test that expects nothing
     * would still see anything that was queued.
     */
    private void drain() {
        for (int i = 0; i < 10; i++) {
            txe.RunNextMainThreadTask();
        }
    }

    @Test
    @Requirement("TBX_068")
    @DisplayName("TBX_068: the host is handed a parseable command line, or nothing")
    void commandsAreNormalisedBeforeDispatch() throws Exception {
        stubQueue(OFFLINE_COMMANDS_JSON);
        List<String> executed = new CopyOnWriteArrayList<>();
        Plugin plugin = connectedPlugin(executed);

        plugin.CheckCommandsDue();
        drain();

        // Padding and a leading slash are removed, the second slash of "//set"
        // is kept because it is part of the command's registered name, and
        // neither blank line nor the lone slash is dispatched at all.
        assertEquals(EXPECTED, executed, "the hook must receive normalised lines, and only runnable ones");
    }

    @Test
    @Requirement("TBX_068")
    @DisplayName("TBX_068: a command with no command line is marked complete so it is not re-sent")
    void unrunnableCommandsAreAcknowledged() throws Exception {
        stubQueue(OFFLINE_COMMANDS_JSON);
        Plugin plugin = connectedPlugin(new CopyOnWriteArrayList<>());

        plugin.CheckCommandsDue();
        drain();

        // Skipping without recording is the failure that produced the incident:
        // only ids in executedCommands are deleted from the store's queue, so a
        // command that is merely ignored comes back on the next check forever.
        for (int id = 1; id <= 6; id++) {
            assertTrue(plugin.executedCommands.containsKey(id),
                    "command #" + id + " must be marked complete, whether it was delivered or dropped");
        }
    }

    @Test
    @Requirement("TBX_068")
    @DisplayName("TBX_068: an unrunnable command is not re-dispatched on later checks")
    void unrunnableCommandsDoNotRepeatOnEveryCheck() throws Exception {
        stubQueue(OFFLINE_COMMANDS_JSON);
        List<String> executed = new CopyOnWriteArrayList<>();
        Plugin plugin = connectedPlugin(executed);

        // The store keeps returning the same queue — which is exactly what
        // happens until the delete goes out — so the second and third checks see
        // all six commands again.
        for (int check = 0; check < 3; check++) {
            plugin.CheckCommandsDue();
            drain();
        }

        assertEquals(3, offlineQueueFetches.get(), "the test must actually re-present the same queue");
        assertEquals(EXPECTED, executed, "a command already marked complete must not be delivered again");
        assertFalse(executed.contains(""), "an empty command line must never be dispatched");
    }

    @Test
    @Requirement("TBX_068")
    @DisplayName("TBX_068: an unrunnable command is retired at check time, not after its delay")
    void delayedEmptyCommandIsDroppedBeforeItIsQueued() throws Exception {
        stubQueue("{\"meta\":{\"limited\":false},\"commands\":["
                + "{\"id\":9,\"command\":\"\",\"conditions\":{\"delay\":3600,\"slots\":0}," + PLAYER_JSON + "}]}");
        List<String> executed = new CopyOnWriteArrayList<>();
        Plugin plugin = connectedPlugin(executed);

        plugin.CheckCommandsDue();
        drain();

        // Retired at once rather than sitting in the queue for an hour first:
        // until it is acknowledged the store keeps re-sending it, so waiting out
        // the delay would keep the bug alive for the length of the delay.
        assertTrue(executed.isEmpty(), "nothing may be dispatched for an empty command");
        assertTrue(plugin.executedCommands.containsKey(9),
                "a delayed empty command must be retired at check time, not after its delay");
    }
}
