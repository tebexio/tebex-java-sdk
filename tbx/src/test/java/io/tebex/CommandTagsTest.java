package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_063: the player tags a store writes into a command
 * are resolved from the queue payload before the command reaches the host.
 *
 * <p>Regression cover for commands that ran against a player's name where the
 * store's log said they ran against an id. A Bedrock player connected through
 * Geyser has no Minecraft UUID, so an integration reading only that field found
 * nothing and dropped straight to the name — even though Tebex sends the XUID
 * for that same player, in the same payload as the command, and identifies them
 * by it everywhere else.
 *
 * <p>Every case runs a real deliverable through the queue check and out of the
 * command hook, because what a store cares about is the line the server ran.
 */
class CommandTagsTest {

    private static final String DUE_PLAYERS_JSON =
            "{\"meta\":{\"execute_offline\":true,\"next_check\":90,\"more\":false},\"players\":[]}";

    /** A Java Edition account: the API sends the UUID with no dashes. */
    private static final String JAVA_PLAYER =
            "{\"id\":3,\"name\":\"Notch\",\"uuid\":\"069a79f444e94726a5befca90e38aaf5\"}";

    /** A Geyser player: Tebex knows them by XUID, and there is no UUID to find. */
    private static final String BEDROCK_PLAYER =
            "{\"id\":4,\"name\":\"BedrockFan\",\"uuid\":null,\"xuid\":\"2535465768090909\"}";

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
     * Queues one command for one player and returns the line the command hook was
     * actually asked to run.
     *
     * @param command    the command as the store wrote it, tags intact
     * @param playerJson the player object the queue returns with it, or
     *                   {@code null} for a command belonging to no player
     * @return the delivered command line
     * @throws Exception if the stub cannot be served or the check fails
     */
    private String deliver(String command, String playerJson) throws Exception {
        if (server != null) {
            server.stop(0);
        }

        String commandsJson = "{\"meta\":{\"limited\":false},\"commands\":[{\"id\":1,\"command\":\""
                + command.replace("\"", "\\\"") + "\",\"conditions\":{\"delay\":0,\"slots\":0}"
                + (playerJson == null ? "" : ",\"player\":" + playerJson) + "}]}";

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/queue", exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/queue/offline-commands")) {
                respond(exchange, 200, commandsJson);
                return;
            }
            respond(exchange, 200, DUE_PLAYERS_JSON);
        });
        server.start();

        TXE txe = new TXE();
        txe.setPluginApi(new PluginApi("http://localhost:" + server.getAddress().getPort()));
        List<String> executed = new CopyOnWriteArrayList<>();
        Plugin plugin = txe.Plugin();
        plugin.HookServerCommand(executed::add);
        plugin.applyCredentials("valid-secret", null, null);

        plugin.CheckCommandsDue();
        txe.RunNextMainThreadTask();

        assertEquals(1, executed.size(), "the command must have been delivered exactly once");
        return executed.get(0);
    }

    @Test
    @Requirement("TBX_063")
    @DisplayName("TBX_063: a java player's id tags become their uuid")
    void javaPlayerUsesTheUuid() throws Exception {
        // Dashed, which is the form every store's commands are written against:
        // the integrations that resolved this tag before the SDK did read it off
        // the host's player object.
        assertEquals("lp user 069a79f4-44e9-4726-a5be-fca90e38aaf5 parent set vip",
                deliver("lp user {uuid} parent set vip", JAVA_PLAYER));
        assertEquals("give 069a79f4-44e9-4726-a5be-fca90e38aaf5 diamond",
                deliver("give {id} diamond", JAVA_PLAYER));
    }

    @Test
    @Requirement("TBX_063")
    @DisplayName("TBX_063: a bedrock player with no uuid gets their xuid, not their name")
    void bedrockPlayerFallsBackToTheXuid() throws Exception {
        assertEquals("lp user 2535465768090909 parent set vip",
                deliver("lp user {uuid} parent set vip", BEDROCK_PLAYER));
        assertEquals("give 2535465768090909 diamond", deliver("give {id} diamond", BEDROCK_PLAYER));

        // An empty string is as common as a JSON null for an id the store does
        // not hold, and must not count as a uuid either.
        assertEquals("give 2535465768090909 diamond", deliver("give {id} diamond",
                "{\"id\":4,\"name\":\"BedrockFan\",\"uuid\":\"\",\"xuid\":\"2535465768090909\"}"));

        // The xuid is not a uuid, so nothing may be reformatted into one.
        assertEquals("say 2535465768090909", deliver("say {uuid}", BEDROCK_PLAYER));
    }

    @Test
    @Requirement("TBX_063")
    @DisplayName("TBX_063: with neither uuid nor xuid the username is still used")
    void withoutAnyIdTheUsernameIsUsed() throws Exception {
        assertEquals("give Steve diamond",
                deliver("give {uuid} diamond", "{\"id\":5,\"name\":\"Steve\"}"));
    }

    @Test
    @Requirement("TBX_063")
    @DisplayName("TBX_063: name tags stay the username even when an id exists")
    void nameTagsUseTheUsername() throws Exception {
        assertEquals("say welcome BedrockFan", deliver("say welcome {name}", BEDROCK_PLAYER));
        assertEquals("kit vip BedrockFan", deliver("kit vip {username}", BEDROCK_PLAYER));
        // Store owners type these by hand, so the case they chose is not a reason
        // to leave a tag unresolved.
        assertEquals("say BedrockFan 2535465768090909", deliver("say {NAME} {UUID}", BEDROCK_PLAYER));
    }

    @Test
    @Requirement("TBX_063")
    @DisplayName("TBX_063: every occurrence is resolved and nothing else is touched")
    void onlyPlayerTagsAreResolved() throws Exception {
        assertEquals("give Notch diamond; say Notch bought it",
                deliver("give {name} diamond; say {name} bought it", JAVA_PLAYER));
        // Tags the host resolves for itself must survive untouched.
        assertEquals("broadcast {price} paid by Notch",
                deliver("broadcast {price} paid by {username}", JAVA_PLAYER));
    }

    @Test
    @Requirement("TBX_063")
    @Requirement("TBX_060")
    @DisplayName("TBX_063: a value the payload does not carry leaves the tag visible")
    void unresolvableTagsAreLeftInPlace() throws Exception {
        // "give  diamond" would look like a command that worked; "give {uuid}
        // diamond" is visibly wrong in the console and in the store's log.
        assertEquals("give {uuid} diamond", deliver("give {uuid} diamond", "{\"id\":6}"));

        // A command that belongs to no player at all must still be delivered.
        assertEquals("say server restarting", deliver("say server restarting", null));
    }
}
