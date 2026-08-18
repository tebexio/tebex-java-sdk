package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import io.tebex.hooks.Configuration;
import io.tebex.http.PluginApi;
import io.tebex.model.ServerInformation;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the settings an operator controls through the
 * integration's configuration: the buy command's name (CFG_001) and whether it
 * exists at all (CFG_002), debug logging (CFG_003), and proxy mode (CFG_005).
 *
 * <p>The SDK reads these through the {@link Configuration} hook and never touches
 * a file, so the tests install an in-memory hook and change values in it — which
 * is also how they prove a change takes effect without a restart.
 */
class ConfigurationTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":false,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}}";

    private static final String CHECKOUT_JSON =
            "{\"url\":\"https://checkout.tebex.io/checkout/abc-123\",\"expires\":\"2026-01-01T00:00:00+00:00\"}";

    private final TXE txe = new TXE();
    private final AtomicInteger checkouts = new AtomicInteger();

    private HttpServer server;
    private boolean debugModeBefore;

    @BeforeEach
    void rememberDebugMode() {
        debugModeBefore = TXE.DEBUG_MODE;
    }

    @AfterEach
    void restore() {
        TXE.DEBUG_MODE = debugModeBefore;
        if (server != null) {
            server.stop(0);
        }
    }

    /** An in-memory {@link Configuration} holding whatever a test puts in it. */
    private static final class FakeConfig implements Configuration {
        final Map<String, String> values = new LinkedHashMap<>();
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
        }

        /** {@inheritDoc} */
        @Override
        public void Load() {
            loads.incrementAndGet();
        }
    }

    /**
     * Starts a stub answering {@code POST /checkout} and points this test's
     * engine at it.
     *
     * @throws IOException if the server cannot be started
     */
    private void stubCheckout() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/checkout", exchange -> {
            checkouts.incrementAndGet();
            byte[] payload = CHECKOUT_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
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
    @Requirement("CFG_001")
    @DisplayName("CFG_001: the buy command's name comes from configuration")
    void buyCommandNameIsConfigurable() throws Exception {
        stubCheckout();
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);
        plugin.applyCredentials("valid-secret", null, null);

        // Default first, so the test proves the rename rather than the absence of
        // a default.
        assertEquals("buy", plugin.buyCommandName());
        assertTrue(joined(plugin.Input("/buy 12", "Notch")).contains("checkout.tebex.io"),
                "the default buy command must create a checkout link");

        config.values.put(Plugin.CONFIG_BUY_COMMAND_NAME, "store");

        assertEquals("store", plugin.buyCommandName());
        assertTrue(joined(plugin.Input("/store 12", "Notch")).contains("checkout.tebex.io"),
                "the renamed command must work");
        assertTrue(joined(plugin.Input("/buy 12", "Notch")).contains("unrecognized command"),
                "the old name must stop working once it has been changed");
        assertEquals(2, checkouts.get(), "only the two recognised invocations may reach the api");
    }

    @Test
    @Requirement("CFG_001")
    @DisplayName("CFG_001: a configured name written with a leading slash still works")
    void buyCommandNameToleratesALeadingSlash() throws Exception {
        stubCheckout();
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);
        plugin.applyCredentials("valid-secret", null, null);

        // An operator editing a config file writes what they type in chat.
        config.values.put(Plugin.CONFIG_BUY_COMMAND_NAME, "/shop");

        assertEquals("shop", plugin.buyCommandName());
        assertTrue(joined(plugin.Input("/shop 12", "Notch")).contains("checkout.tebex.io"));
    }

    @Test
    @Requirement("CFG_002")
    @DisplayName("CFG_002: the buy command can be disabled in configuration")
    void buyCommandCanBeDisabled() throws Exception {
        stubCheckout();
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);
        plugin.applyCredentials("valid-secret", null, null);

        config.values.put(Plugin.CONFIG_BUY_COMMAND_ENABLED, "false");

        assertFalse(plugin.isBuyCommandEnabled());
        assertTrue(joined(plugin.Input("/buy 12", "Notch")).contains("unrecognized command"),
                "a disabled command must be indistinguishable from one that does not exist");
        assertEquals(0, checkouts.get(), "a disabled command must not reach the api");
        assertFalse(joined(plugin.Input("/tebex help", "Notch")).contains("/buy"),
                "help must not advertise a command that is switched off");

        config.values.put(Plugin.CONFIG_BUY_COMMAND_ENABLED, "true");
        assertTrue(joined(plugin.Input("/buy 12", "Notch")).contains("checkout.tebex.io"),
                "re-enabling must take effect without a restart");
    }

    @Test
    @Requirement("CFG_002")
    @DisplayName("CFG_002: a nonsense enabled value leaves the command on rather than silently off")
    void unparseableFlagFallsBackToTheDefault() {
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);

        config.values.put(Plugin.CONFIG_BUY_COMMAND_ENABLED, "yes please");

        // Boolean.parseBoolean would read this as false and quietly remove the
        // command; a typo must not do that.
        assertTrue(plugin.isBuyCommandEnabled(), "an unreadable value must fall back to the default");
    }

    @Test
    @Requirement("CFG_003")
    @DisplayName("CFG_003: debug mode is taken from configuration on reload")
    void debugModeComesFromConfiguration() throws Exception {
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);
        TXE.DEBUG_MODE = false;

        config.values.put(Plugin.CONFIG_DEBUG, "true");
        plugin.Input("/tebex reload", "");
        assertTrue(TXE.DEBUG_MODE, "a reload must adopt the configured debug setting");

        config.values.put(Plugin.CONFIG_DEBUG, "false");
        plugin.Input("/tebex reload", "");
        assertFalse(TXE.DEBUG_MODE, "turning it off in configuration must take effect too");
    }

    @Test
    @Requirement("CFG_003")
    @Requirement("TBX_026")
    @DisplayName("CFG_003: a configuration silent about debug does not undo /tebex debug")
    void configurationWithoutDebugLeavesTheRuntimeSettingAlone() throws Exception {
        Plugin plugin = txe.Plugin();
        plugin.HookConfig(new FakeConfig());
        TXE.DEBUG_MODE = false;

        plugin.Input("/tebex debug true", "");
        plugin.Input("/tebex reload", "");

        assertTrue(TXE.DEBUG_MODE,
                "an operator debugging a live problem must not lose it to an unrelated reload");
    }

    @Test
    @Requirement("CFG_005")
    @DisplayName("CFG_005: proxy mode forces online mode on regardless of what the store reports")
    void proxyModeForcesOnlineMode() {
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);

        // The fixture store reports online_mode false, which is what a backend
        // server behind a proxy sees.
        ServerInformation information = new Gson().fromJson(INFORMATION_JSON, ServerInformation.class);
        plugin.applyCredentials("valid-secret", information.getAccount(), information.getServer());
        assertFalse(plugin.IsOnlineMode(), "without proxy mode the store's own setting stands");

        config.values.put(Plugin.CONFIG_PROXY_MODE, "true");

        assertTrue(plugin.IsOnlineMode(),
                "behind a proxy the connections are authenticated even though this server cannot see it");
    }

    @Test
    @Requirement("CFG_005")
    @DisplayName("CFG_005: with no store connected yet, proxy mode still answers")
    void proxyModeAnswersBeforeConnecting() {
        Plugin plugin = txe.Plugin();
        FakeConfig config = new FakeConfig();
        plugin.HookConfig(config);

        assertFalse(plugin.IsOnlineMode(), "nothing is known yet, so the answer is no rather than an NPE");

        config.values.put(Plugin.CONFIG_PROXY_MODE, "true");
        assertTrue(plugin.IsOnlineMode());
    }
}
