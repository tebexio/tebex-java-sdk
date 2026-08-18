package io.tebex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.tebex.exception.AuthenticationException;
import io.tebex.exception.TebexException;
import io.tebex.model.ServerInformation;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the {@code /information} plugin API call, exercised
 * end-to-end against an in-JVM {@link HttpServer} (no network required).
 */
class TebexApiTest {

    private static final String INFORMATION_JSON =
            "{"
            + "\"account\":{"
            + "\"id\":1,"
            + "\"domain\":\"https://example.tebex.io\","
            + "\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},"
            + "\"online_mode\":true,"
            + "\"game_type\":\"Minecraft (Offline/Geyser)\","
            + "\"log_events\":true"
            + "},"
            + "\"server\":{\"id\":2,\"name\":\"My Server\"}"
            + "}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a stub plugin API that replies to /information and returns its base URL. */
    private String startServer(int status, String body, AtomicReference<String> receivedSecret) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> {
            if (receivedSecret != null) {
                receivedSecret.set(exchange.getRequestHeaders().getFirst("X-Tebex-Secret"));
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    @Requirement("TBX_002")
    @DisplayName("TBX_002: a valid secret key successfully runs /information and is parsed")
    void validKeyRunsInformation() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        String baseUrl = startServer(200, INFORMATION_JSON, received);

        ServerInformation info = new PluginApi(baseUrl).getServerInformation("valid-secret").join();

        // The secret key was sent as the X-Tebex-Secret header.
        assertEquals("valid-secret", received.get(), "the secret key must be sent as X-Tebex-Secret");
        // The response body was parsed into the model, including snake_case fields.
        assertEquals("Example Store", info.getAccount().getName());
        assertEquals("https://example.tebex.io", info.getAccount().getDomain());
        assertEquals("USD", info.getAccount().getCurrency().getIso4217());
        assertEquals("$", info.getAccount().getCurrency().getSymbol());
        assertTrue(info.getAccount().isOnlineMode());
        assertEquals("Minecraft (Offline/Geyser)", info.getAccount().getGameType());
        assertEquals("My Server", info.getServer().getName());
        assertEquals(2, info.getServer().getId());
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a rejected key (403) surfaces as an AuthenticationException, not a crash")
    void rejectedKeyIsAuthenticationException() throws IOException {
        String baseUrl = startServer(403, "Forbidden", null);

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> new PluginApi(baseUrl).getServerInformation("bad-secret").join());
        assertInstanceOf(AuthenticationException.class, thrown.getCause());
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: an unexpected status (500) surfaces as a typed TebexException")
    void unexpectedStatusIsTebexException() {
        PluginApi api = new PluginApi("http://localhost");
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.parseInformation(500, ""));
        assertInstanceOf(TebexException.class, thrown.getCause());
    }

    @Test
    @Requirement("TBX_001")
    @DisplayName("TBX_001: a malformed 200 body surfaces as a typed TebexException")
    void malformedBodyIsTebexException() {
        PluginApi api = new PluginApi("http://localhost");
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.parseInformation(200, "not json"));
        assertInstanceOf(TebexException.class, thrown.getCause());
    }
}
