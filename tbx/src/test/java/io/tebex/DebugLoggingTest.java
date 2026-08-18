package io.tebex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.tebex.http.PluginApi;
import io.tebex.requirements.Requirement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_027: debug mode enables logging of request and
 * response bodies.
 */
class DebugLoggingTest {

    private static final String INFORMATION_JSON =
            "{\"account\":{\"id\":1,\"domain\":\"https://example.tebex.io\",\"name\":\"Example Store\","
            + "\"currency\":{\"iso_4217\":\"USD\",\"symbol\":\"$\"},\"online_mode\":true,"
            + "\"game_type\":\"Minecraft: Java Edition\",\"log_events\":false}}";

    private static final String SECRET = "super-secret-key-value";

    private HttpServer server;
    private boolean debugModeBefore;
    private final List<String> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void captureLogs() {
        debugModeBefore = TXE.DEBUG_MODE;
        Logger logger = Logger.getLogger("tebex-debug-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        TXE.Log().SetLogger(logger);
    }

    @AfterEach
    void restore() {
        TXE.DEBUG_MODE = debugModeBefore;
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a stub {@code /information} endpoint.
     *
     * @return the stub base URL
     * @throws IOException if the server cannot be started
     */
    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/information", exchange -> {
            byte[] payload = INFORMATION_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * Joins captured records so an assertion can search all of them at once.
     *
     * @return every captured message
     */
    private String allRecords() {
        return String.join("\n", records);
    }

    @Test
    @Requirement("TBX_027")
    @DisplayName("TBX_027: with debug mode on, the request and response are logged")
    void debugModeLogsRequestAndResponse() throws IOException {
        String baseUrl = startServer();
        TXE.DEBUG_MODE = true;

        new PluginApi(baseUrl).getServerInformation(SECRET).join();

        String logged = allRecords();
        assertTrue(logged.contains("GET"), "the request method must be logged; got: " + logged);
        assertTrue(logged.contains("/information"), "the request path must be logged; got: " + logged);
        assertTrue(logged.contains("200"), "the response status must be logged; got: " + logged);
        assertTrue(logged.contains("Example Store"),
                "the response body must be logged; got: " + logged);
    }

    @Test
    @Requirement("TBX_027")
    @DisplayName("TBX_027: with debug mode off, nothing is logged")
    void debugModeOffLogsNothing() throws IOException {
        String baseUrl = startServer();
        TXE.DEBUG_MODE = false;

        new PluginApi(baseUrl).getServerInformation(SECRET).join();

        assertTrue(records.isEmpty(),
                "no request or response detail may be logged while debug mode is off: " + records);
    }

    @Test
    @Requirement("TBX_027")
    @Requirement("CODE_004")
    @DisplayName("TBX_027/CODE_004: the secret key is never written to the debug log")
    void secretKeyIsNeverLogged() throws IOException {
        String baseUrl = startServer();
        TXE.DEBUG_MODE = true;

        new PluginApi(baseUrl).getServerInformation(SECRET).join();

        // The secret travels in the X-Tebex-Secret header, and headers are
        // deliberately excluded from the debug output.
        assertFalse(allRecords().contains(SECRET),
                "the secret key must never appear in a log record: " + allRecords());
    }

    @Test
    @Requirement("TBX_027")
    @DisplayName("TBX_027: a request body is logged when one is sent")
    void requestBodyIsLogged() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/checkout", exchange -> {
            byte[] payload = "{\"url\":\"https://checkout.tebex.io/checkout/abc\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        TXE.DEBUG_MODE = true;

        new PluginApi("http://localhost:" + server.getAddress().getPort())
                .createCheckoutUrl(SECRET, 9, "Notch").join();

        String logged = allRecords();
        assertTrue(logged.contains("\"package_id\":9"),
                "the request body must be logged; got: " + logged);
        assertTrue(logged.contains("201"), "the response status must be logged; got: " + logged);
    }
}
