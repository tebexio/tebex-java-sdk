package io.tebex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tebex.hooks.Configuration;
import io.tebex.hooks.PlayerActions;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the {@code /tebex} command dispatcher on {@link Plugin}.
 *
 * <p>Tests are derived from the requirement text and from the ways each command
 * can be misused — a missing argument, an unknown prefix, a rejected key, an
 * uninstalled hook — rather than from the dispatcher's structure.
 *
 * <p>{@code Plugin} now resolves its client from the engine that owns it, so each
 * test configures one {@code TXE} instance and touches no process-wide state
 * beyond {@code DEBUG_MODE}, which is saved and restored.
 */
class PluginInputTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}}";

    private HttpServer server;
    private boolean debugModeBefore;

    /**
     * The engine under test. Every test uses this one instance, and its
     * {@code Plugin} resolves the client from it rather than from the singleton,
     * so nothing here touches process-wide engine state.
     */
    private final TXE txe = new TXE();

    @BeforeEach
    void rememberDebugMode() {
        // DEBUG_MODE is process-wide static; restore it so these tests cannot
        // leak state into each other or into the rest of the suite.
        debugModeBefore = TXE.DEBUG_MODE;
    }

    @AfterEach
    void restore() {
        TXE.DEBUG_MODE = debugModeBefore;
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a stub {@code /information} endpoint and points this test's engine at
     * it.
     *
     * @param status the status to answer with
     * @param body   the body to answer with
     * @throws IOException if the server cannot be started
     */
    private void stubInformation(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
    }

    /** An in-memory {@link Configuration} that records what was written. */
    private static final class FakeConfig implements Configuration {
        final Map<String, String> values = new LinkedHashMap<>();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();

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
            saves.incrementAndGet();
        }

        /** {@inheritDoc} */
        @Override
        public void Load() {
            loads.incrementAndGet();
        }
    }

    /** A {@link PlayerActions} that answers every permission with a fixed verdict. */
    private static final class FixedPermissions implements PlayerActions {
        private final boolean allowed;

        FixedPermissions(boolean allowed) {
            this.allowed = allowed;
        }

        /** {@inheritDoc} */
        @Override
        public boolean IsOnline(String usernameOrUuid) {
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public int GetNumInventorySlotsAvailable(String username) {
            return 36;
        }

        /** {@inheritDoc} */
        @Override
        public void SendMessage(String username, String message) {
            // Not used by these tests.
        }

        /** {@inheritDoc} */
        @Override
        public boolean HasPermission(String username, String uuid, String permission) {
            return allowed;
        }
    }

    /**
     * Returns this test's plugin, which belongs to {@link #txe}.
     *
     * @return the plugin under test
     */
    private Plugin newPlugin() {
        return txe.Plugin();
    }

    /**
     * Joins output lines so an assertion can search the whole reply.
     *
     * @param lines the reply
     * @return the lines joined by newlines
     */
    private static String joined(String[] lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // Parsing and dispatch
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_030")
    @Requirement("TBX_031")
    @DisplayName("TBX_030/031: help lists the available commands, and bare 'tebex' shows it too")
    void helpListsCommands() throws Exception {
        Plugin plugin = newPlugin();

        String help = joined(plugin.Input("/tebex help", ""));
        assertTrue(help.contains("/tebex info"), "help must list the commands; got: " + help);
        assertTrue(help.contains("/tebex secret"), "help must list secret; got: " + help);
        assertTrue(help.contains("/tebex forcecheck"), "help must list forcecheck; got: " + help);

        // "tebex" with no subcommand previously returned a single empty line.
        assertEquals(help, joined(plugin.Input("tebex", "")),
                "bare 'tebex' should show the same help rather than an empty reply");
    }

    @Test
    @Requirement("TBX_060")
    @DisplayName("TBX_060: an unrecognised prefix is rejected rather than dispatched")
    void unknownPrefixIsRejected() throws Exception {
        Plugin plugin = newPlugin();

        // Previously only validated when there was exactly one token, so a
        // near-miss prefix with arguments silently ran the subcommand.
        assertTrue(joined(plugin.Input("/tebexx help", "")).contains("unrecognized command"),
                "a near-miss prefix must not dispatch the subcommand");
        assertTrue(joined(plugin.Input("/nottebex debug true", "")).contains("unrecognized command"));
        assertFalse(TXE.DEBUG_MODE, "a rejected input must not have executed its subcommand");
    }

    @Test
    @Requirement("TBX_060")
    @DisplayName("TBX_060: empty, blank and extra-whitespace input are handled without throwing")
    void malformedInputIsHandled() {
        Plugin plugin = newPlugin();

        assertDoesNotThrow(() -> {
            assertTrue(joined(plugin.Input("", "")).contains("cannot be empty"));
            assertTrue(joined(plugin.Input("   ", "")).contains("cannot be empty"));
            // Repeated spaces used to split into empty tokens.
            assertTrue(joined(plugin.Input("/tebex   help", "")).contains("/tebex info"));
        });
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a subcommand missing its argument reports usage instead of throwing")
    void missingArgumentDoesNotThrow() {
        Plugin plugin = newPlugin();

        // Both of these indexed args[0] unconditionally and threw
        // ArrayIndexOutOfBoundsException out of the SDK.
        assertDoesNotThrow(() -> {
            assertTrue(joined(plugin.Input("/tebex secret", "")).contains("usage"),
                    "secret with no key must report usage");
            assertTrue(joined(plugin.Input("/tebex debug", "")).contains("usage"),
                    "debug with no argument must report usage");
        });
    }

    // ------------------------------------------------------------------
    // Individual commands
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_026")
    @DisplayName("TBX_026: 'tebex debug' toggles debug mode on and off")
    void debugTogglesMode() throws Exception {
        Plugin plugin = newPlugin();
        TXE.DEBUG_MODE = false;

        assertTrue(joined(plugin.Input("/tebex debug true", "")).contains("enabled"));
        assertTrue(TXE.DEBUG_MODE, "debug true must enable debug mode");

        assertTrue(joined(plugin.Input("/tebex debug false", "")).contains("disabled"));
        assertFalse(TXE.DEBUG_MODE, "debug false must disable debug mode");

        // An unrecognised argument must not silently flip the flag.
        assertTrue(joined(plugin.Input("/tebex debug maybe", "")).contains("Invalid"));
        assertFalse(TXE.DEBUG_MODE);
    }

    @Test
    @Requirement("TBX_022")
    @Requirement("TBX_003")
    @DisplayName("TBX_022/TBX_003: a valid secret is adopted and persisted to configuration")
    void validSecretIsAdoptedAndPersisted() throws Exception {
        stubInformation(200, INFORMATION_JSON);
        Plugin plugin = newPlugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);

        String reply = joined(plugin.Input("/tebex secret valid-key", ""));

        assertTrue(reply.contains("Example Store"), "the reply must confirm the connected store; got: " + reply);
        assertEquals("valid-key", plugin.key, "the key must be adopted");
        assertEquals("Example Store", plugin.account.getName(), "store data must be loaded alongside the key");
        assertEquals("valid-key", config.values.get("secret-key"), "the key must be written to config");
        assertEquals(1, config.saves.get(), "the config must be saved");
    }

    @Test
    @Requirement("TBX_005")
    @DisplayName("TBX_005: a rejected secret is refused and the previous key is left unchanged")
    void invalidSecretLeavesPreviousKeyIntact() throws Exception {
        stubInformation(403, "Forbidden");
        Plugin plugin = newPlugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);
        plugin.applyCredentials("original-key", null, null);

        String reply = joined(plugin.Input("/tebex secret bad-key", ""));

        assertTrue(reply.contains("rejected"), "the reply must say the key was rejected; got: " + reply);
        assertEquals("original-key", plugin.key, "a rejected key must not replace the working one");
        assertFalse(config.values.containsKey("secret-key"), "a rejected key must not be persisted");
        assertEquals(0, config.saves.get(), "a rejected key must not trigger a save");
    }

    @Test
    @Requirement("TBX_022")
    @DisplayName("TBX_022: the reply is delivered synchronously, not after the method returns")
    void secretReplyIsNotLostToACallbackThread() throws Exception {
        stubInformation(200, INFORMATION_JSON);
        Plugin plugin = newPlugin();
        plugin.HookConfig(new FakeConfig());

        // The previous implementation added its success message inside
        // thenAccept, which ran after Input had already returned its array — so
        // the caller always saw an empty reply.
        String[] reply = plugin.Input("/tebex secret valid-key", "");

        assertNotEquals(0, reply.length, "the caller must receive the outcome, not an empty array");
    }

    @Test
    @Requirement("TBX_023")
    @DisplayName("TBX_023: 'tebex info' shows the connected store, and says so when not connected")
    void infoShowsStoreOrExplainsAbsence() throws Exception {
        Plugin plugin = newPlugin();

        // Before connecting, account/server are null — this used to NPE.
        String notConnected = joined(plugin.Input("/tebex info", ""));
        assertTrue(notConnected.contains("not connected"), "got: " + notConnected);

        stubInformation(200, INFORMATION_JSON);
        plugin.HookConfig(new FakeConfig());
        plugin.Input("/tebex secret valid-key", "");

        String info = joined(plugin.Input("/tebex info", ""));
        assertTrue(info.contains("Example Store"), "got: " + info);
        assertTrue(info.contains("My Server"), "got: " + info);
        assertTrue(info.contains("https://example.tebex.io"), "got: " + info);
        assertTrue(info.contains("$USD"), "the currency must be shown; got: " + info);
    }

    @Test
    @Requirement("TBX_029")
    @DisplayName("TBX_029: 'tebex reload' reloads the configuration")
    void reloadReloadsConfiguration() throws Exception {
        Plugin plugin = newPlugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);

        assertTrue(joined(plugin.Input("/tebex reload", "")).contains("reloaded"));
        assertEquals(1, config.loads.get(), "the configuration must actually be reloaded");
    }

    @Test
    @Requirement("TBX_024")
    @DisplayName("TBX_024: 'tebex forcecheck' reports honestly when the engine is not running")
    void forcecheckReportsWhenEngineIsStopped() throws Exception {
        Plugin plugin = newPlugin();

        // Nothing will pick the check up, so the command must say so rather than
        // claim success. (That a running engine actually re-checks is TBX_010,
        // covered against a live engine in TxeTest.)
        String reply = joined(plugin.Input("/tebex forcecheck", ""));

        assertTrue(reply.contains("not running"), "got: " + reply);
    }

    // ------------------------------------------------------------------
    // Permissions and missing hooks
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_060")
    @DisplayName("TBX_060: every command works with no hooks installed rather than throwing")
    void noHooksInstalledDoesNotThrow() {
        Plugin plugin = newPlugin();

        // playerActions, config and serverCommand are all null here. Previously
        // each of these paths dereferenced one of them.
        assertDoesNotThrow(() -> {
            plugin.Input("/tebex help", "SomePlayer", "uuid-1");
            plugin.Input("/tebex info", "SomePlayer", "uuid-1");
            plugin.Input("/tebex debug true", "SomePlayer", "uuid-1");
            assertTrue(joined(plugin.Input("/tebex reload", "SomePlayer", "uuid-1"))
                    .contains("no configuration hook"));
        });
    }

    @Test
    @Requirement("TBX_060")
    @DisplayName("TBX_060: the queue check refuses without a ServerCommand hook instead of failing per-task")
    void queueCheckRefusesWithoutServerCommandHook() {
        Plugin plugin = newPlugin();

        // No ServerCommand means nothing could be delivered; the check must back
        // off rather than queue tasks that throw inside every TebexTask.
        int backoff = assertDoesNotThrow(plugin::CheckCommandsDue);

        assertTrue(backoff > 0, "the engine must be told to back off, not to retry immediately");
    }

    @Test
    @Requirement("TBX_060")
    @DisplayName("TBX_060: a player without permission is refused, console is not gated")
    void permissionsAreEnforcedForPlayersOnly() throws Exception {
        Plugin plugin = newPlugin();
        plugin.HookPlayerActions(new FixedPermissions(false));
        TXE.DEBUG_MODE = false;

        String denied = joined(plugin.Input("/tebex debug true", "SomePlayer", "uuid-1"));
        assertTrue(denied.contains("do not have the necessary permissions"), "got: " + denied);
        assertFalse(TXE.DEBUG_MODE, "a denied command must not take effect");

        // Console has no player to check, so it is not gated.
        assertTrue(joined(plugin.Input("/tebex debug true", "")).contains("enabled"));
        assertTrue(TXE.DEBUG_MODE);
    }

    @Test
    @Requirement("TBX_027")
    @DisplayName("TBX_027: debug output is suppressed unless debug mode is enabled")
    void debugLoggingIsGatedOnDebugMode() {
        java.util.List<String> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger("tebex-debug-gate-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(java.util.logging.Level.ALL);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        TXE.Log().SetLogger(logger);

        TXE.DEBUG_MODE = false;
        TXE.Log().Debug("should not appear");
        assertTrue(records.isEmpty(), "debug output must be suppressed when debug mode is off: " + records);

        TXE.DEBUG_MODE = true;
        TXE.Log().Debug("should appear");
        assertTrue(records.stream().anyMatch(m -> m.contains("should appear")),
                "debug output must be emitted when debug mode is on: " + records);
        // Emitted above FINEST so it survives a default java.util.logging setup.
        assertTrue(records.stream().anyMatch(m -> m.contains("[DEBUG]")),
                "debug records should be identifiable: " + records);
    }

    @Test
    @Requirement("TBX_060")
    @DisplayName("TBX_060: an unrecognised subcommand is reported, not dispatched")
    void unknownSubcommandIsReported() throws Exception {
        Plugin plugin = newPlugin();

        String reply = joined(plugin.Input("/tebex nonsense", ""));

        assertTrue(reply.contains("unrecognized command"), "got: " + reply);
        assertEquals(1, plugin.Input("/tebex nonsense", "").length,
                "an unknown subcommand should produce exactly one line, got: "
                        + Arrays.toString(plugin.Input("/tebex nonsense", "")));
    }
}
