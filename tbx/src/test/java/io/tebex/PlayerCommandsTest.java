package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the two player-facing commands: {@code /tebex redeem},
 * which delivers what is waiting for the caller right now (TBX_025), and
 * {@code /tebex goals}, which summarises progress towards the store's community
 * goals (TBX_028).
 *
 * <p>Both are run against a stub API through the dispatcher, because what matters
 * is the whole path a player sees: the reply they get, and — for redeem — that
 * the purchase actually reaches the host's command hook.
 */
class PlayerCommandsTest {

    private static final String DUE_PLAYERS_JSON =
            "{\"meta\":{\"execute_offline\":false,\"next_check\":90,\"more\":false},"
            + "\"players\":[{\"id\":5,\"name\":\"Notch\",\"uuid\":\"uuid-1\"}]}";

    private static final String ONLINE_COMMANDS_JSON =
            "{\"commands\":[{\"id\":42,\"command\":\"give Notch diamond\","
            + "\"conditions\":{\"delay\":0,\"slots\":0}}]}";

    private static final String GOALS_JSON =
            "[{\"id\":1,\"name\":\"New Spawn\",\"target\":500,\"current\":125,\"status\":\"active\"},"
            + "{\"id\":2,\"name\":\"Server Upgrade\",\"target\":100,\"current\":100,\"status\":\"completed\"}]";

    private final TXE txe = new TXE();
    private final List<String> executed = new CopyOnWriteArrayList<>();
    private final AtomicInteger queueCalls = new AtomicInteger();

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
     * Starts a stub serving the queue and community-goal endpoints and points
     * this test's engine at it.
     *
     * @param duePlayers the {@code /queue} payload
     * @param goals      the {@code /community_goals} payload
     * @throws IOException if the server cannot be started
     */
    private void stubStore(String duePlayers, AtomicReference<String> goals) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/queue", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/queue/online-commands/")) {
                respond(exchange, 200, ONLINE_COMMANDS_JSON);
                return;
            }
            queueCalls.incrementAndGet();
            respond(exchange, 200, duePlayers);
        });
        server.createContext("/community_goals", exchange -> respond(exchange, 200, goals.get()));
        server.start();
        txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
    }

    /**
     * Returns a plugin holding credentials and a command hook that records what
     * it was asked to run.
     *
     * @return the plugin under test
     */
    private Plugin connectedPlugin() {
        Plugin plugin = txe.Plugin();
        plugin.HookServerCommand(executed::add);
        plugin.applyCredentials("valid-secret", null, null);
        return plugin;
    }

    /**
     * Joins reply lines so an assertion can search the whole reply.
     *
     * @param lines the reply
     * @return the lines joined by newlines
     */
    private static String joined(String[] lines) {
        return String.join("\n", lines);
    }

    @Test
    @Requirement("TBX_025")
    @DisplayName("TBX_025: redeem fetches the caller's commands and delivers them")
    void redeemDeliversTheCallersCommands() throws Exception {
        stubStore(DUE_PLAYERS_JSON, new AtomicReference<>(GOALS_JSON));
        Plugin plugin = connectedPlugin();

        String reply = joined(plugin.Input("/tebex redeem", "Notch", "uuid-1"));

        assertTrue(reply.contains("Queued 1 command"), "the player must be told what happened; got: " + reply);

        // Queued rather than executed inline: delivery happens on the host's main
        // thread, which is the whole reason the queue exists (TBX_040).
        assertTrue(executed.isEmpty(), "nothing may run on the caller's thread");
        txe.RunNextMainThreadTask();
        assertEquals(1, executed.size(), "the command must be delivered on the main thread");
        assertEquals("give Notch diamond", executed.get(0));
    }

    @Test
    @Requirement("TBX_025")
    @Requirement("TBX_036")
    @DisplayName("TBX_025: redeeming twice does not deliver the same command twice")
    void redeemDoesNotDuplicateDeliveries() throws Exception {
        stubStore(DUE_PLAYERS_JSON, new AtomicReference<>(GOALS_JSON));
        Plugin plugin = connectedPlugin();

        plugin.Input("/tebex redeem", "Notch", "uuid-1");
        txe.RunNextMainThreadTask();
        // The command is now marked executed but not yet deleted from Tebex, so
        // the store still returns it.
        String second = joined(plugin.Input("/tebex redeem", "Notch", "uuid-1"));
        txe.RunNextMainThreadTask();

        assertEquals(1, executed.size(), "a delivered command must not be delivered again: " + executed);
        assertTrue(second.contains("no commands waiting"),
                "the second attempt must say there is nothing left; got: " + second);
    }

    @Test
    @Requirement("TBX_025")
    @DisplayName("TBX_025: a player with nothing waiting is told so")
    void redeemWithNothingWaiting() throws Exception {
        stubStore("{\"meta\":{\"execute_offline\":false,\"next_check\":90,\"more\":false},\"players\":[]}",
                new AtomicReference<>(GOALS_JSON));
        Plugin plugin = connectedPlugin();

        String reply = joined(plugin.Input("/tebex redeem", "Notch", "uuid-1"));

        assertTrue(reply.contains("no commands waiting"), "got: " + reply);
        assertTrue(executed.isEmpty());
    }

    @Test
    @Requirement("TBX_025")
    @Requirement("TBX_060")
    @DisplayName("TBX_025: redeem refuses from console and without a command hook")
    void redeemRefusesWhenItCannotWork() throws Exception {
        stubStore(DUE_PLAYERS_JSON, new AtomicReference<>(GOALS_JSON));
        Plugin plugin = connectedPlugin();

        // Console has no purchases of its own to collect.
        assertTrue(joined(plugin.Input("/tebex redeem", "")).contains("must be run by a player"));

        plugin.HookServerCommand(null);
        String noHook = joined(plugin.Input("/tebex redeem", "Notch", "uuid-1"));
        assertTrue(noHook.contains("no server command hook"),
                "without a way to run commands the command must say so; got: " + noHook);
        assertEquals(0, queueCalls.get(), "a refusal must not cost an api call");
    }

    @Test
    @Requirement("TBX_028")
    @DisplayName("TBX_028: goals summarises each goal's progress")
    void goalsSummarisesProgress() throws Exception {
        stubStore(DUE_PLAYERS_JSON, new AtomicReference<>(GOALS_JSON));
        Plugin plugin = connectedPlugin();

        String reply = joined(plugin.Input("/tebex goals", ""));

        assertTrue(reply.contains("New Spawn"), "each goal must be listed; got: " + reply);
        assertTrue(reply.contains("125/500"), "the raw progress must be shown; got: " + reply);
        assertTrue(reply.contains("25%"), "the percentage must be shown; got: " + reply);
        assertTrue(reply.contains("Server Upgrade"), "got: " + reply);
        assertTrue(reply.contains("100%"), "a completed goal must read as complete; got: " + reply);
    }

    @Test
    @Requirement("TBX_028")
    @DisplayName("TBX_028: a store with no community goals says so rather than showing an empty list")
    void goalsWithNoGoals() throws Exception {
        stubStore(DUE_PLAYERS_JSON, new AtomicReference<>("[]"));
        Plugin plugin = connectedPlugin();

        assertTrue(joined(plugin.Input("/tebex goals", "")).contains("no community goals"));
    }

    @Test
    @Requirement("TBX_028")
    @Requirement("TBX_060")
    @DisplayName("TBX_028: goals before a store is connected reports that, rather than failing")
    void goalsBeforeConnecting() throws Exception {
        stubStore(DUE_PLAYERS_JSON, new AtomicReference<>(GOALS_JSON));
        Plugin plugin = txe.Plugin(); // no credentials applied

        assertTrue(joined(plugin.Input("/tebex goals", "")).contains("not connected to a store"));
    }
}
