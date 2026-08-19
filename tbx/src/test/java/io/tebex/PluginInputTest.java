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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private static final String CHECKOUT_JSON =
            "{\"url\":\"https://checkout.tebex.io/checkout/abc123\",\"expires\":\"2026-01-01 00:00:00\"}";

    private static final String LOOKUP_JSON =
            "{\"player\":{\"id\":\"77\",\"username\":\"SomePlayer\",\"meta\":\"\","
            + "\"plugin_username_id\":42},\"banCount\":2,\"chargebackRate\":3,"
            + "\"payments\":[{\"txn_id\":\"tbx-1\",\"time\":1700000000,\"price\":10.5,"
            + "\"currency\":\"USD\",\"status\":1}],\"purchaseTotals\":{\"USD\":10.5}}";

    private HttpServer server;
    private boolean debugModeBefore;

    /** The last body the SDK sent to each stubbed path. */
    private final Map<String, String> sentBodies = new ConcurrentHashMap<>();

    /** How many times the SDK called each stubbed path. */
    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

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
     * Stubs one plugin API path with a fixed answer, starting this test's server
     * and pointing the engine at it on the first call.
     *
     * <p>Records the request body and a call count per path, so a test can assert
     * both what the SDK sent and — for the commands that must refuse before they
     * call anything — that it sent nothing at all.
     *
     * @param path   the path to stub, matched by prefix as {@code HttpServer} does,
     *               so {@code /user} also answers {@code /user/SomePlayer}
     * @param status the status to answer with
     * @param body   the body to answer with, which must not be empty: a zero
     *               length answer means chunked encoding to {@code HttpServer}
     * @throws IOException if the server cannot be started
     */
    private void stub(String path, int status, String body) throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.start();
            txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
        }
        server.createContext(path, exchange -> {
            callCounts.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            sentBodies.put(path, readFully(exchange.getRequestBody()));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
    }

    /**
     * Stubs the {@code /information} endpoint every authenticating test needs.
     *
     * @param status the status to answer with
     * @param body   the body to answer with
     * @throws IOException if the server cannot be started
     */
    private void stubInformation(int status, String body) throws IOException {
        stub("/information", status, body);
    }

    /**
     * Returns the body the SDK sent to a stubbed path.
     *
     * @param path the stubbed path
     * @return the request body, or the empty string if the path was never called
     */
    private String sentBody(String path) {
        String body = sentBodies.get(path);
        return body == null ? "" : body;
    }

    /**
     * Returns how many times the SDK called a stubbed path.
     *
     * @param path the stubbed path
     * @return the call count
     */
    private int callCount(String path) {
        AtomicInteger count = callCounts.get(path);
        return count == null ? 0 : count.get();
    }

    /**
     * Reads a request body to a string.
     *
     * @param input the request body stream
     * @return the body as UTF-8 text
     * @throws IOException if the body cannot be read
     */
    private static String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        for (int read = input.read(chunk); read != -1; read = input.read(chunk)) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
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
     * A {@link PlayerActions} that records what was sent to a player and answers
     * a fixed verdict for whether they are online.
     */
    private static final class RecordingPlayerActions implements PlayerActions {
        final List<String> messages = new CopyOnWriteArrayList<>();
        private final boolean online;

        RecordingPlayerActions(boolean online) {
            this.online = online;
        }

        /** {@inheritDoc} */
        @Override
        public boolean IsOnline(String usernameOrUuid) {
            return online;
        }

        /** {@inheritDoc} */
        @Override
        public int GetNumInventorySlotsAvailable(String username) {
            return 36;
        }

        /** {@inheritDoc} */
        @Override
        public void SendMessage(String username, String message) {
            messages.add(username + ": " + message);
        }

        /** {@inheritDoc} */
        @Override
        public boolean HasPermission(String username, String uuid, String permission) {
            return true;
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
        // A command that works but is not listed is only half delivered.
        assertTrue(help.contains("/tebex checkout"), "help must list checkout; got: " + help);
        assertTrue(help.contains("/tebex sendlink"), "help must list sendlink; got: " + help);
        assertTrue(help.contains("/tebex ban"), "help must list ban; got: " + help);
        assertTrue(help.contains("/tebex lookup"), "help must list lookup; got: " + help);

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

    // ------------------------------------------------------------------
    // Operator commands over the plugin api
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_064")
    @DisplayName("TBX_064: 'tebex checkout' creates a link for the calling player")
    void checkoutCreatesALinkForTheCallingPlayer() throws Exception {
        stub("/checkout", 201, CHECKOUT_JSON);
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        String reply = joined(plugin.Input("/tebex checkout 123", "SomePlayer", "uuid-1"));

        assertTrue(reply.contains("https://checkout.tebex.io/checkout/abc123"),
                "the reply must carry the checkout url; got: " + reply);
        assertTrue(sentBody("/checkout").contains("\"package_id\":123"),
                "the package id must reach the api; sent: " + sentBody("/checkout"));
        assertTrue(sentBody("/checkout").contains("\"username\":\"SomePlayer\""),
                "the caller must be the customer when no name is given; sent: " + sentBody("/checkout"));
    }

    @Test
    @Requirement("TBX_064")
    @DisplayName("TBX_064: the console can create a link only by naming the customer")
    void checkoutFromConsoleNamesTheCustomer() throws Exception {
        stub("/checkout", 201, CHECKOUT_JSON);
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        // Nobody to infer: the store needs a customer for the basket.
        String withoutName = joined(plugin.Input("/tebex checkout 123", ""));
        assertTrue(withoutName.contains("must name the customer"), "got: " + withoutName);
        assertEquals(0, callCount("/checkout"), "no basket should be created for a refused command");

        String withName = joined(plugin.Input("/tebex checkout 123 SomePlayer", ""));
        assertTrue(withName.contains("https://checkout.tebex.io/checkout/abc123"), "got: " + withName);
        assertTrue(withName.contains("SomePlayer"), "the reply must say who the link is for; got: " + withName);
        assertTrue(sentBody("/checkout").contains("\"username\":\"SomePlayer\""),
                "the named customer must reach the api; sent: " + sentBody("/checkout"));
    }

    @Test
    @Requirement("TBX_064")
    @Requirement("TBX_060")
    @DisplayName("TBX_064/TBX_060: checkout reports bad input and a missing store instead of calling out")
    void checkoutRefusesBadInput() throws Exception {
        stub("/checkout", 201, CHECKOUT_JSON);
        Plugin plugin = newPlugin();

        // No key adopted yet.
        assertTrue(joined(plugin.Input("/tebex checkout 123", "SomePlayer", "uuid-1"))
                .contains("not connected"));

        plugin.applyCredentials("valid-key", null, null);
        assertTrue(joined(plugin.Input("/tebex checkout", "SomePlayer", "uuid-1")).contains("usage"),
                "checkout with no package id must report usage");
        assertTrue(joined(plugin.Input("/tebex checkout twelve", "SomePlayer", "uuid-1"))
                .contains("is not a package id"), "a non-numeric id must be reported, not parsed");
        assertEquals(0, callCount("/checkout"), "none of these should have reached the api");
    }

    @Test
    @Requirement("TBX_065")
    @DisplayName("TBX_065: 'tebex sendlink' sends the checkout link to the named player")
    void sendLinkDeliversTheLinkToThePlayer() throws Exception {
        stub("/checkout", 201, CHECKOUT_JSON);
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);
        RecordingPlayerActions players = new RecordingPlayerActions(true);
        plugin.HookPlayerActions(players);

        String reply = joined(plugin.Input("/tebex sendlink SomePlayer 123", ""));

        assertTrue(reply.contains("sent to SomePlayer"), "the operator must be told it was sent; got: " + reply);
        assertEquals(1, players.messages.size(), "the player must be messaged; got: " + players.messages);
        assertTrue(players.messages.get(0).contains("https://checkout.tebex.io/checkout/abc123"),
                "the message must carry the link; got: " + players.messages);
        assertTrue(players.messages.get(0).startsWith("SomePlayer:"),
                "the link must go to the named player; got: " + players.messages);
        assertTrue(sentBody("/checkout").contains("\"username\":\"SomePlayer\""),
                "the basket must belong to the recipient; sent: " + sentBody("/checkout"));
    }

    @Test
    @Requirement("TBX_065")
    @Requirement("TBX_060")
    @DisplayName("TBX_065/TBX_060: sendlink refuses before creating a link it could not deliver")
    void sendLinkRefusesBeforeCreatingAnUndeliverableLink() throws Exception {
        stub("/checkout", 201, CHECKOUT_JSON);
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        // No hook installed at all: there is no way to reach the player.
        String noHook = joined(plugin.Input("/tebex sendlink SomePlayer 123", ""));
        assertTrue(noHook.contains("no player actions hook"), "got: " + noHook);
        assertTrue(noHook.contains("/tebex checkout 123 SomePlayer"),
                "the refusal should point at the command that does work; got: " + noHook);

        // Hook installed, but the recipient is not connected.
        plugin.HookPlayerActions(new RecordingPlayerActions(false));
        String offline = joined(plugin.Input("/tebex sendlink SomePlayer 123", ""));
        assertTrue(offline.contains("not online"), "got: " + offline);

        assertTrue(joined(plugin.Input("/tebex sendlink SomePlayer", "")).contains("usage"),
                "sendlink without a package id must report usage");

        // A basket created for a link nobody receives is worse than a refusal.
        assertEquals(0, callCount("/checkout"), "no checkout should have been created");
    }

    @Test
    @Requirement("TBX_066")
    @DisplayName("TBX_066: 'tebex ban' bans the player and sends the whole reason")
    void banSendsThePlayerAndWholeReason() throws Exception {
        stub("/bans", 200, "{}");
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        String reply = joined(plugin.Input("/tebex ban Griefer chargeback fraud", ""));

        assertTrue(reply.contains("was banned"), "got: " + reply);
        assertTrue(sentBody("/bans").contains("\"user\":\"Griefer\""), "sent: " + sentBody("/bans"));
        // A reason is a sentence, not a token: the rest of the line belongs to it.
        assertTrue(sentBody("/bans").contains("\"reason\":\"chargeback fraud\""),
                "the whole reason must be sent; sent: " + sentBody("/bans"));
    }

    @Test
    @Requirement("TBX_066")
    @DisplayName("TBX_066: a ban the store declines is reported as an outcome, not a failure")
    void banReportsAStoreRefusal() throws Exception {
        stub("/bans", 500, "{}");
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        String reply = assertDoesNotThrow(() -> joined(plugin.Input("/tebex ban Griefer", "")));

        assertTrue(reply.contains("did not accept"), "the operator must learn the ban did not take; got: " + reply);
        assertTrue(joined(plugin.Input("/tebex ban", "")).contains("usage"),
                "ban with no player must report usage");
    }

    @Test
    @Requirement("TBX_067")
    @DisplayName("TBX_067: 'tebex lookup' shows the store's record of a player")
    void lookupShowsTheStoreRecord() throws Exception {
        stub("/user", 200, LOOKUP_JSON);
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        String reply = joined(plugin.Input("/tebex lookup SomePlayer", ""));

        assertTrue(reply.contains("SomePlayer"), "got: " + reply);
        assertTrue(reply.contains("Bans: 2"), "the ban count must be shown; got: " + reply);
        assertTrue(reply.contains("3%"), "the chargeback rate must be shown; got: " + reply);
        assertTrue(reply.contains("Payments: 1"), "the payment count must be shown; got: " + reply);
        assertTrue(reply.contains("USD"), "the spend total must be shown; got: " + reply);
    }

    @Test
    @Requirement("TBX_067")
    @DisplayName("TBX_067: a player the store has no record of is reported plainly")
    void lookupReportsAnUnknownPlayer() throws Exception {
        // 404 is one of the three ways the api says "no such customer"; the client
        // normalises all of them to null (TBX_058), and the command must not treat
        // that ordinary answer as an error.
        stub("/user", 404, "{}");
        Plugin plugin = newPlugin();
        plugin.applyCredentials("valid-key", null, null);

        String reply = assertDoesNotThrow(() -> joined(plugin.Input("/tebex lookup Nobody", "")));

        assertTrue(reply.contains("holds nothing for Nobody"), "got: " + reply);
        assertTrue(joined(plugin.Input("/tebex lookup", "")).contains("usage"),
                "lookup with no player must report usage");
    }
}
