package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_062: deliverables reach the host's command hook one
 * at a time, in the order the store queued them.
 *
 * <p>Regression cover for a real out-of-order delivery. A test purchase queues
 * two commands — the package's own command, then the removal Tebex issues
 * immediately afterwards — and an integration that dispatched them without
 * waiting for each to finish (a proxy handing them to an async command manager
 * and dropping the returned future) could see the removal complete first,
 * undoing work the purchase had not yet done. The SDK's side of that contract is
 * what these tests pin: one command per drain, in queue order, run to completion
 * on the calling thread before the next one is handed over.
 */
class CommandOrderingTest {

    private static final String DUE_PLAYERS_JSON =
            "{\"meta\":{\"execute_offline\":true,\"next_check\":90,\"more\":false},\"players\":[]}";

    // The shape a test purchase produces: the package command, then its removal.
    private static final String OFFLINE_COMMANDS_JSON =
            "{\"meta\":{\"limited\":false},\"commands\":["
            + "{\"id\":1,\"command\":\"tebexnotify purchase Notch vip\",\"conditions\":{\"delay\":0,\"slots\":0},"
            + "\"player\":{\"id\":3,\"name\":\"Notch\",\"uuid\":\"abc\"}},"
            + "{\"id\":2,\"command\":\"tebexnotify remove Notch vip\",\"conditions\":{\"delay\":0,\"slots\":0},"
            + "\"player\":{\"id\":3,\"name\":\"Notch\",\"uuid\":\"abc\"}}]}";

    private final TXE txe = new TXE();

    private HttpServer server;

    @AfterEach
    void stopServer() {
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
     * Starts a stub serving a due-players check that asks for the offline queue,
     * and an offline queue holding a purchase followed by its removal.
     *
     * @throws IOException if the server cannot be started
     */
    private void stubQueue() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/queue", exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/queue/offline-commands")) {
                respond(exchange, 200, OFFLINE_COMMANDS_JSON);
                return;
            }
            respond(exchange, 200, DUE_PLAYERS_JSON);
        });
        server.start();
        txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
    }

    /**
     * Returns a plugin holding credentials and the given command hook.
     *
     * @param hook the hook to install
     * @return the plugin under test
     */
    private Plugin connectedPlugin(io.tebex.hooks.ServerCommand hook) {
        Plugin plugin = txe.Plugin();
        plugin.HookServerCommand(hook);
        plugin.applyCredentials("valid-secret", null, null);
        return plugin;
    }

    @Test
    @Requirement("TBX_062")
    @DisplayName("TBX_062: commands are delivered in the order the store queued them")
    void commandsAreDeliveredInQueueOrder() throws Exception {
        stubQueue();
        List<String> executed = new CopyOnWriteArrayList<>();
        Plugin plugin = connectedPlugin(executed::add);

        plugin.CheckCommandsDue();
        txe.RunNextMainThreadTask();
        txe.RunNextMainThreadTask();

        // Not merely "both arrived": the removal must not overtake the purchase
        // it is undoing.
        assertEquals(Arrays.asList("tebexnotify purchase Notch vip", "tebexnotify remove Notch vip"),
                executed, "deliverables must reach the hook in the order the queue returned them");
    }

    @Test
    @Requirement("TBX_062")
    @DisplayName("TBX_062: a command is applied in full before the next one is dispatched")
    void oneCommandCompletesBeforeTheNextIsDispatched() throws Exception {
        stubQueue();
        List<String> trace = new CopyOnWriteArrayList<>();
        AtomicBoolean inFlight = new AtomicBoolean();
        Thread caller = Thread.currentThread();

        Plugin plugin = connectedPlugin(command -> {
            // A second command arriving while this one is still running is the
            // failure being guarded against: it means the SDK handed the host
            // work it had not finished with.
            if (!inFlight.compareAndSet(false, true)) {
                trace.add("overlapped " + command);
            }
            trace.add("start " + command);
            // The hook is where a host does its slow work — a database write, a
            // permissions update — so the window between start and end is real.
            trace.add("end " + command);
            inFlight.set(false);

            // Fire-and-forget dispatch shows up here: the hook would run on some
            // executor's thread rather than the one draining the queue, and
            // Execute would have returned long before the command applied.
            assertEquals(caller, Thread.currentThread(),
                    "the hook must run on the thread draining the queue, not asynchronously");
        });

        plugin.CheckCommandsDue();

        txe.RunNextMainThreadTask();
        assertEquals(Arrays.asList("start tebexnotify purchase Notch vip", "end tebexnotify purchase Notch vip"),
                trace, "the first command must be finished by the time the drain returns");

        txe.RunNextMainThreadTask();
        assertEquals(Arrays.asList(
                        "start tebexnotify purchase Notch vip",
                        "end tebexnotify purchase Notch vip",
                        "start tebexnotify remove Notch vip",
                        "end tebexnotify remove Notch vip"),
                trace, "the second command must start only after the first has finished");
        assertFalse(inFlight.get(), "no command may be left in flight");
    }

    @Test
    @Requirement("TBX_062")
    @DisplayName("TBX_062: the queue hands out due tasks first-in, first-out")
    void dueTasksRunFirstInFirstOut() {
        TXE engine = new TXE();
        List<String> ran = new CopyOnWriteArrayList<>();
        long now = Instant.now().getEpochSecond();

        engine.queueMainThreadTask(new TebexTask(now, "command:1", () -> ran.add("purchase")));
        engine.queueMainThreadTask(new TebexTask(now, "command:2", () -> ran.add("remove")));
        engine.queueMainThreadTask(new TebexTask(now, "command:3", () -> ran.add("notify")));

        for (int i = 0; i < 3; i++) {
            engine.RunNextMainThreadTask();
        }

        assertEquals(Arrays.asList("purchase", "remove", "notify"), ran,
                "tasks due at the same moment must run in the order they were queued");
    }
}
