package io.tebex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.tebex.exception.AuthenticationException;
import io.tebex.exception.TebexException;
import io.tebex.model.Category;
import io.tebex.model.CheckoutUrl;
import io.tebex.model.CommunityGoal;
import io.tebex.model.Coupon;
import io.tebex.model.CreateCouponRequest;
import io.tebex.model.CreateCouponRequest.DiscountMethod;
import io.tebex.model.DuePlayersResponse;
import io.tebex.model.OfflineCommandsResponse;
import io.tebex.model.PaginatedResponse;
import io.tebex.model.PlayerLookupInfo;
import io.tebex.model.PluginEvent;
import io.tebex.model.QueuedCommand;
import io.tebex.model.QueuedPlayer;
import io.tebex.model.ServerEvent;
import io.tebex.model.StartupTelemetry;
import io.tebex.model.StorePackage;
import io.tebex.requirements.Requirement;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the plugin API endpoints ported from the old SDK,
 * exercised against an in-JVM {@link HttpServer} so no network is required.
 *
 * <p>Tests are derived from the requirement text rather than from
 * {@link PluginApi}: each one enumerates a way the stated behaviour could be
 * violated (wrong success status accepted, error envelope ignored, player not
 * bound, request issued when it should not be) and asserts it is not.
 */
class PluginApiEndpointTest {

    private HttpServer server;

    /** The secret every test authenticates with. */
    private static final String SECRET = "test-secret";

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a stub server whose single handler answers every path, and returns a
     * client with all three hosts pointed at it.
     *
     * @param handler the handler to serve requests with
     * @return a client bound to the stub server
     * @throws IOException if the server cannot be started
     */
    private PluginApi clientFor(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        return new PluginApi(base, base, base, new com.google.gson.Gson());
    }

    /**
     * Starts a stub server that always answers with the given status and body.
     *
     * @param status the status to return
     * @param body   the body to return
     * @return a client bound to the stub server
     * @throws IOException if the server cannot be started
     */
    private PluginApi clientReturning(int status, String body) throws IOException {
        return clientFor(exchange -> respond(exchange, status, body));
    }

    /**
     * Writes a response and closes the exchange.
     *
     * @param exchange the exchange to answer
     * @param status   the status code
     * @param body     the response body
     * @throws IOException if writing fails
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        // 204 must not carry a body; sendResponseHeaders rejects a length here.
        exchange.sendResponseHeaders(status, status == 204 ? -1 : payload.length);
        if (status != 204 && payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    /**
     * Reads a request body as UTF-8 text.
     *
     * @param exchange the exchange to read from
     * @return the request body
     * @throws IOException if reading fails
     */
    private static String readRequest(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Transport-level invariants
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_043")
    @DisplayName("TBX_043: every authenticated endpoint sends the secret as X-Tebex-Secret")
    void secretHeaderIsSentOnEveryEndpoint() throws IOException {
        List<String> seenSecrets = Collections.synchronizedList(new ArrayList<String>());
        PluginApi api = clientFor(exchange -> {
            seenSecrets.add(exchange.getRequestHeaders().getFirst("X-Tebex-Secret"));
            respond(exchange, 200, "{}");
        });

        // A representative call per HTTP verb the client uses.
        api.getDuePlayers(SECRET).join();
        api.getCommunityGoal(SECRET, 1).join();
        api.createBan(SECRET, "uuid", "127.0.0.1", "reason").join();

        assertEquals(3, seenSecrets.size(), "each call must reach the server");
        for (String secret : seenSecrets) {
            assertEquals(SECRET, secret, "the secret key must be sent on every authenticated call");
        }
    }

    @Test
    @Requirement("TBX_043")
    @Requirement("TBX_007")
    @DisplayName("TBX_007/TBX_043: the due-players queue is never fetched without a valid secret key")
    void missingSecretMakesNoRequest() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        PluginApi api = clientFor(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{}");
        });

        for (String badSecret : new String[] {null, "", "   "}) {
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> api.getDuePlayers(badSecret).join());
            assertInstanceOf(AuthenticationException.class, thrown.getCause(),
                    "a missing secret must be an authentication failure");
        }
        assertEquals(0, requests.get(), "the queue must not be fetched without a secret key");
    }

    @Test
    @Requirement("TBX_044")
    @DisplayName("TBX_044: 403 is an AuthenticationException while other errors are TebexException")
    void errorStatusesAreTyped() throws IOException {
        PluginApi forbidden = clientReturning(403, "Forbidden");
        CompletionException authFailure = assertThrows(CompletionException.class,
                () -> forbidden.getDuePlayers(SECRET).join());
        assertInstanceOf(AuthenticationException.class, authFailure.getCause());
        stopServer();

        PluginApi serverError = clientReturning(500, "boom");
        CompletionException generalFailure = assertThrows(CompletionException.class,
                () -> serverError.getDuePlayers(SECRET).join());
        assertInstanceOf(TebexException.class, generalFailure.getCause());
        assertFalse(generalFailure.getCause() instanceof AuthenticationException,
                "a 500 is not an authentication problem");
    }

    @Test
    @Requirement("TBX_044")
    @DisplayName("TBX_044: 404 is an authentication failure on queue endpoints too, matching /information")
    void notFoundIsAuthenticationFailureEverywhere() throws IOException {
        PluginApi api = clientReturning(404, "");

        // A 404 means the key does not resolve to a store, which is the same
        // actionable problem as a 403 — and /information already maps it that way.
        for (java.util.function.Supplier<?> call : java.util.Arrays.<java.util.function.Supplier<?>>asList(
                () -> api.getDuePlayers(SECRET).join(),
                () -> api.getOfflineCommands(SECRET).join(),
                () -> api.getCommunityGoals(SECRET).join(),
                () -> api.getPackages(SECRET).join(),
                () -> api.deleteCommands(SECRET, Collections.singletonList(1)).join())) {
            CompletionException thrown = assertThrows(CompletionException.class, call::get);
            assertInstanceOf(AuthenticationException.class, thrown.getCause(),
                    "a 404 must be distinguishable from a generic server error");
        }
    }

    @Test
    @Requirement("TBX_045")
    @DisplayName("TBX_045: a malformed body is a TebexException, not a raw parser error")
    void malformedBodyIsTebexException() throws IOException {
        PluginApi api = clientReturning(200, "{ this is not json");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.getDuePlayers(SECRET).join());
        assertInstanceOf(TebexException.class, thrown.getCause());
    }

    @Test
    @Requirement("TBX_045")
    @DisplayName("TBX_045: an empty body where an object is required is a TebexException")
    void emptyBodyIsTebexException() throws IOException {
        PluginApi api = clientReturning(200, "");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.getListing(SECRET).join());
        assertInstanceOf(TebexException.class, thrown.getCause());
    }

    // ------------------------------------------------------------------
    // Command queue
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_009")
    @DisplayName("TBX_009: the due-players response exposes the api's next_check backoff")
    void duePlayersExposesNextCheck() throws IOException {
        PluginApi api = clientReturning(200,
                "{\"meta\":{\"execute_offline\":true,\"next_check\":90,\"more\":false},"
                        + "\"players\":[{\"id\":7,\"name\":\"Notch\",\"uuid\":\"abc\"}]}");

        DuePlayersResponse due = api.getDuePlayers(SECRET).join();

        assertEquals(90, due.getNextCheck(), "the api's backoff must be exposed, not hidden");
        assertTrue(due.isExecuteOffline());
        assertFalse(due.hasMore());
        assertEquals(1, due.getPlayers().size());
        assertEquals("Notch", due.getPlayers().get(0).getName());
        assertEquals(7, due.getPlayers().get(0).getId());
    }

    @Test
    @Requirement("TBX_009")
    @DisplayName("TBX_009: a due-players response with no meta block reports a zero backoff")
    void duePlayersWithoutMetaIsSafe() throws IOException {
        PluginApi api = clientReturning(200, "{\"players\":[]}");

        DuePlayersResponse due = api.getDuePlayers(SECRET).join();

        assertEquals(0, due.getNextCheck());
        assertTrue(due.getPlayers().isEmpty(), "an absent list must read as empty, not null");
    }

    @Test
    @Requirement("TBX_046")
    @DisplayName("TBX_046: offline commands expose delay and slots, and null package/payment read as 0")
    void offlineCommandsExposeConditions() throws IOException {
        PluginApi api = clientReturning(200,
                "{\"meta\":{\"limited\":true},\"commands\":["
                        + "{\"id\":11,\"command\":\"give %name% dirt\",\"payment\":null,\"package\":null,"
                        + "\"conditions\":{\"delay\":30,\"slots\":2},"
                        + "\"player\":{\"id\":3,\"name\":\"Alex\",\"uuid\":\"def\"}},"
                        + "{\"id\":12,\"command\":\"say hi\",\"payment\":55,\"package\":66,\"conditions\":{},"
                        + "\"player\":{\"id\":3,\"name\":\"Alex\",\"uuid\":\"def\"}}]}");

        OfflineCommandsResponse offline = api.getOfflineCommands(SECRET).join();

        assertTrue(offline.isLimited(), "the limited flag drives whether another check follows");
        assertEquals(2, offline.getCommands().size());

        QueuedCommand withConditions = offline.getCommands().get(0);
        assertEquals(30, withConditions.getDelay());
        assertEquals(2, withConditions.getRequiredSlots());
        // JSON null must collapse to 0, matching what the platform modules expect.
        assertEquals(0, withConditions.getPaymentId());
        assertEquals(0, withConditions.getPackageId());
        // The command must not have been placeholder-substituted by the client.
        assertEquals("give %name% dirt", withConditions.getCommand());
        assertEquals("Alex", withConditions.getPlayer().getName());
        assertFalse(withConditions.isOnline(), "offline commands must not be marked online");

        QueuedCommand withoutConditions = offline.getCommands().get(1);
        assertEquals(0, withoutConditions.getDelay(), "an empty conditions block means no delay");
        assertEquals(0, withoutConditions.getRequiredSlots());
        assertEquals(55, withoutConditions.getPaymentId());
        assertEquals(66, withoutConditions.getPackageId());
    }

    @Test
    @Requirement("TBX_047")
    @DisplayName("TBX_047: online commands are fetched by player id and bound to that player")
    void onlineCommandsAreBoundToPlayer() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        PluginApi api = clientFor(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200,
                    "{\"commands\":[{\"id\":21,\"command\":\"heal\",\"payment\":1,\"package\":2,"
                            + "\"conditions\":{\"delay\":0,\"slots\":0}}]}");
        });

        QueuedPlayer player = playerWithId42();
        List<QueuedCommand> commands = api.getOnlineCommands(SECRET, player).join();

        assertEquals("/queue/online-commands/42", path.get(),
                "the player's queue id must be in the path");
        assertEquals(1, commands.size());
        QueuedCommand command = commands.get(0);
        // The online payload carries no player; the client must supply it.
        assertNotNull(command.getPlayer(), "an online command must be bound to its player");
        assertEquals(42, command.getPlayer().getId());
        assertTrue(command.isOnline(), "commands from the online queue must be marked online");
    }

    /**
     * Builds a player with queue id 42 by parsing JSON, so the test does not
     * depend on a setter the model deliberately does not expose.
     *
     * @return a player whose queue id is 42
     */
    private static QueuedPlayer playerWithId42() {
        return new com.google.gson.Gson().fromJson(
                "{\"id\":42,\"name\":\"Steve\",\"uuid\":\"ghi\"}", QueuedPlayer.class);
    }

    @Test
    @Requirement("TBX_048")
    @DisplayName("TBX_048: deleting commands expects 204 and sends the ids")
    void deleteCommandsExpects204() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        PluginApi api = clientFor(exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readRequest(exchange));
            respond(exchange, 204, null);
        });

        assertTrue(api.deleteCommands(SECRET, Arrays.asList(1, 2, 3)).join());
        assertEquals("DELETE", method.get());
        assertEquals("{\"ids\":[1,2,3]}", body.get());
    }

    @Test
    @Requirement("TBX_048")
    @DisplayName("TBX_048: a 200 from delete is rejected, because the contract is 204")
    void deleteCommandsRejectsWrongSuccessStatus() throws IOException {
        PluginApi api = clientReturning(200, "{}");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.deleteCommands(SECRET, Collections.singletonList(1)).join());
        assertInstanceOf(TebexException.class, thrown.getCause());
    }

    @Test
    @Requirement("TBX_048")
    @DisplayName("TBX_048: deleting an empty id list makes no request at all")
    void deleteCommandsSkipsEmptyList() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        PluginApi api = clientFor(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 204, null);
        });

        assertTrue(api.deleteCommands(SECRET, Collections.<Integer>emptyList()).join());
        assertTrue(api.deleteCommands(SECRET, null).join());
        assertEquals(0, requests.get(), "an empty acknowledgement must not burn a request");
    }

    // ------------------------------------------------------------------
    // Storefront
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_049")
    @DisplayName("TBX_049: the listing resolves categories, packages, subcategories and sale pricing")
    void listingIsFullyResolved() throws IOException {
        PluginApi api = clientReturning(200,
                "{\"categories\":[{\"id\":1,\"order\":2,\"name\":\"Ranks\",\"gui_item\":\"stone\","
                        + "\"only_subcategories\":false,"
                        + "\"packages\":[{\"id\":9,\"order\":1,\"name\":\"VIP\",\"price\":10.0,"
                        + "\"image\":\"i.png\",\"gui_item\":\"gold\","
                        + "\"sale\":{\"active\":true,\"discount\":2.5}}],"
                        + "\"subcategories\":[{\"id\":5,\"order\":1,\"name\":\"Sub\",\"gui_item\":\"dirt\","
                        + "\"packages\":[],\"subcategories\":[]}]}]}");

        List<Category> categories = api.getListing(SECRET).join();

        assertEquals(1, categories.size());
        Category category = categories.get(0);
        assertEquals("Ranks", category.getName());
        assertEquals(2, category.getOrder());
        assertEquals(1, category.getPackages().size());
        assertEquals("VIP", category.getPackages().get(0).getName());
        assertEquals(10.0, category.getPackages().get(0).getPrice(), 0.0001);
        assertEquals(7.5, category.getPackages().get(0).getEffectivePrice(), 0.0001,
                "an active sale must reduce the effective price");
        assertEquals(1, category.getSubcategories().size());
        assertEquals("Sub", category.getSubcategories().get(0).getName());
    }

    @Test
    @Requirement("TBX_049")
    @DisplayName("TBX_049: a listing refusal surfaces the api's error_message")
    void listingSurfacesErrorMessage() throws IOException {
        PluginApi api = clientReturning(403, "{\"error_message\":\"Your store is not set up\"}");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.getListing(SECRET).join());
        assertEquals("Your store is not set up", thrown.getCause().getMessage(),
                "the api's explanation must not be replaced by a bare status code");
    }

    @Test
    @Requirement("TBX_050")
    @DisplayName("TBX_050: packages can be fetched as a list and individually")
    void packagesAreParsed() throws IOException {
        String onePackage = "{\"id\":9,\"name\":\"VIP\",\"image\":\"\",\"price\":10.0,"
                + "\"expiry_length\":1,\"expiry_period\":\"month\",\"type\":\"subscription\","
                + "\"category\":{\"id\":1,\"name\":\"Ranks\"},"
                + "\"global_limit\":0,\"global_limit_period\":\"\",\"user_limit\":2,"
                + "\"user_limit_period\":\"day\",\"servers\":[{\"id\":3,\"name\":\"Survival\"}],"
                + "\"required_packages\":[4,5],\"require_any\":true,\"create_giftcard\":false,"
                + "\"show_until\":true,\"gui_item\":\"\",\"disabled\":false,\"disable_quantity\":true,"
                + "\"custom_price\":false,\"choose_server\":true,\"limit_expires\":false,"
                + "\"inherit_commands\":true,\"variable_giftcard\":false}";

        PluginApi api = clientReturning(200, "[" + onePackage + "]");
        List<StorePackage> packages = api.getPackages(SECRET).join();
        assertEquals(1, packages.size());
        StorePackage pkg = packages.get(0);
        assertEquals("VIP", pkg.getName());
        assertEquals("subscription", pkg.getType());
        assertEquals("Ranks", pkg.getCategory().getName());
        assertEquals(Arrays.asList(4, 5), pkg.getRequiredPackages());
        assertEquals(1, pkg.getServers().size());
        assertTrue(pkg.isShowUntil(), "show_until must map to the model, not the misspelled field");
        assertTrue(pkg.isChooseServer());
        assertNull(pkg.getGuiItem(), "a blank gui_item must normalise to null");
        stopServer();

        PluginApi single = clientReturning(200, onePackage);
        assertEquals(9, single.getPackage(SECRET, 9).join().getId());
    }

    @Test
    @Requirement("TBX_051")
    @DisplayName("TBX_051: a checkout url is created on 201 and yields the basket ident")
    void checkoutUrlIsCreatedOn201() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        PluginApi api = clientFor(exchange -> {
            body.set(readRequest(exchange));
            respond(exchange, 201,
                    "{\"url\":\"https://checkout.tebex.io/checkout/abc123\","
                            + "\"expires\":\"2026-01-01T00:00:00Z\"}");
        });

        CheckoutUrl checkout = api.createCheckoutUrl(SECRET, 9, "Notch").join();

        assertEquals("{\"package_id\":9,\"username\":\"Notch\"}", body.get());
        assertEquals("https://checkout.tebex.io/checkout/abc123", checkout.getUrl());
        assertEquals("abc123", checkout.getBasketIdent(),
                "the basket ident is what makes this usable with the headless api");
    }

    @Test
    @Requirement("TBX_051")
    @DisplayName("TBX_051: a 200 from checkout is rejected, because the contract is 201")
    void checkoutRejectsWrongSuccessStatus() throws IOException {
        PluginApi api = clientReturning(200, "{\"url\":\"https://x/y/z\"}");

        assertThrows(CompletionException.class,
                () -> api.createCheckoutUrl(SECRET, 9, "Notch").join());
    }

    @Test
    @Requirement("TBX_051")
    @DisplayName("TBX_051: a 400 from checkout surfaces the api's error_message")
    void checkoutSurfacesErrorMessage() throws IOException {
        PluginApi api = clientReturning(400,
                "{\"error_message\":\"Package is disabled\"}");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.createCheckoutUrl(SECRET, 9, "Notch").join());
        assertEquals("Package is disabled", thrown.getCause().getMessage());
    }

    @Test
    @Requirement("TBX_021")
    @DisplayName("TBX_021: community goals are retrieved as a list and individually")
    void communityGoalsAreRetrieved() throws IOException {
        String goal = "{\"id\":4,\"created_at\":\"2026-01-01 00:00:00\","
                + "\"updated_at\":\"2026-01-02 00:00:00\",\"account\":1,\"name\":\"New Spawn\","
                + "\"description\":\"Fund it\",\"image\":\"\",\"target\":100.0,\"current\":25.0,"
                + "\"repeatable\":1,\"last_achieved\":null,\"times_achieved\":0,"
                + "\"status\":\"active\",\"sale\":false}";

        PluginApi api = clientReturning(200, "[" + goal + "]");
        List<CommunityGoal> goals = api.getCommunityGoals(SECRET).join();

        assertEquals(1, goals.size());
        CommunityGoal parsed = goals.get(0);
        assertEquals("New Spawn", parsed.getName());
        assertEquals(CommunityGoal.Status.ACTIVE, parsed.getStatus());
        // repeatable arrives as 0/1, not a JSON boolean.
        assertTrue(parsed.isRepeatable());
        assertNull(parsed.getImage(), "an empty image must normalise to null");
        assertEquals(0.25, parsed.getProgress(), 0.0001);
        assertNull(parsed.getLastAchieved());
        stopServer();

        PluginApi single = clientReturning(200, goal);
        assertEquals(4, single.getCommunityGoal(SECRET, 4).join().getId());
    }

    @Test
    @Requirement("TBX_021")
    @DisplayName("TBX_021: a zero-target goal reports zero progress rather than NaN")
    void zeroTargetGoalDoesNotDivideByZero() throws IOException {
        PluginApi api = clientReturning(200,
                "{\"id\":1,\"target\":0.0,\"current\":5.0,\"repeatable\":0,\"status\":\"completed\"}");

        CommunityGoal goal = api.getCommunityGoal(SECRET, 1).join();

        assertEquals(0.0, goal.getProgress(), 0.0001);
        assertFalse(Double.isNaN(goal.getProgress()), "a malformed goal must not yield NaN");
        assertFalse(goal.isRepeatable());
    }

    // ------------------------------------------------------------------
    // Events, logs, telemetry
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_052")
    @DisplayName("TBX_052: join/leave events are batched and a 413 halves the batch")
    void joinEventsBatchAndBackOff() throws IOException {
        List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<Integer>());
        List<String> deliveredUsernames = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger calls = new AtomicInteger();
        PluginApi api = clientFor(exchange -> {
            String body = readRequest(exchange);
            int count = body.isEmpty() ? 0 : body.split("\\},\\{").length;
            batchSizes.add(count);
            boolean reject = calls.getAndIncrement() == 0;
            if (!reject) {
                // Record what actually got through, so a dropped tail cannot pass.
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\"username\":\"(player\\d+)\"").matcher(body);
                while (matcher.find()) {
                    deliveredUsernames.add(matcher.group(1));
                }
            }
            // Reject the first full batch as too large, accept everything after.
            respond(exchange, reject ? 413 : 204, null);
        });

        List<ServerEvent> events = new ArrayList<>();
        List<String> expectedUsernames = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(new ServerEvent("uuid" + i, "player" + i, "127.0.0.1", ServerEvent.Type.JOIN));
            expectedUsernames.add("player" + i);
        }

        assertTrue(api.sendJoinEvents(SECRET, events).join(), "the retry must ultimately succeed");

        // 100 rejected, then 50 + 50 accepted: the halved size must not drop the tail.
        assertEquals(3, batchSizes.size(), "one rejected attempt plus two accepted batches");
        assertEquals(100, (int) batchSizes.get(0), "the first attempt sends a full batch");
        assertEquals(50, (int) batchSizes.get(1), "a 413 must halve the batch size");
        assertEquals(50, (int) batchSizes.get(2), "the remainder is sent at the reduced size");
        assertEquals(expectedUsernames, deliveredUsernames,
                "every event must be delivered exactly once, in order, after a 413 retry");
    }

    @Test
    @Requirement("TBX_052")
    @DisplayName("TBX_052: a batch still too large at size 1 gives up instead of looping forever")
    void persistent413TerminatesWithFailure() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        PluginApi api = clientFor(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 413, null);
        });

        List<ServerEvent> events = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            events.add(new ServerEvent("u" + i, "p" + i, "127.0.0.1", ServerEvent.Type.LEAVE));
        }

        assertFalse(api.sendJoinEvents(SECRET, events).join(),
                "a permanently oversized batch must report failure, not hang");
        // 100 -> 50 -> 25 -> 12 -> 6 -> 3 -> 1 -> 0 : bounded, and every attempt
        // is a real request, so the halving must terminate.
        assertTrue(requests.get() <= 10,
                "the halving must terminate quickly, not retry indefinitely");
    }

    @Test
    @Requirement("TBX_052")
    @DisplayName("TBX_052: sending no events succeeds without a request")
    void noEventsMakesNoRequest() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        PluginApi api = clientFor(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 204, null);
        });

        assertTrue(api.sendJoinEvents(SECRET, Collections.<ServerEvent>emptyList()).join());
        assertEquals(0, requests.get());
    }

    @Test
    @Requirement("TBX_052")
    @DisplayName("TBX_052: a server event anonymises the player's IP before it can be sent")
    void serverEventAnonymisesIp() {
        ServerEvent event = new ServerEvent("uuid", "Notch", "192.168.1.100", ServerEvent.Type.JOIN);

        assertEquals("192.168.1.x", event.getIp(), "the final octet must be masked");
        assertEquals("server.join", event.getEventType());
        assertEquals("192.168.1.x",
                new ServerEvent("u", "n", "192.168.1.100", ServerEvent.Type.LEAVE).getIp());
        // An address with no dot is passed through rather than mangled.
        assertEquals("::1", new ServerEvent("u", "n", "::1", ServerEvent.Type.JOIN).getIp());
    }

    @Test
    @Requirement("TBX_053")
    @Requirement("TBX_037")
    @DisplayName("TBX_037/TBX_053: the plugin logs api accepts a valid log, sent to the log host with no secret")
    void pluginLogsAreSentUnauthenticated() throws IOException {
        AtomicReference<String> secret = new AtomicReference<>("unset");
        AtomicReference<String> path = new AtomicReference<>();
        PluginApi api = clientFor(exchange -> {
            secret.set(exchange.getRequestHeaders().getFirst("X-Tebex-Secret"));
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 204, null);
        });

        List<PluginEvent> events = new ArrayList<>();
        events.add(new PluginEvent(PluginEvent.Level.WARNING, "something odd"));

        assertTrue(api.sendPluginEvents(events).join());
        assertNull(secret.get(), "the log intake is unauthenticated");
        assertEquals("/events", path.get());
        assertEquals(1, events.size(), "the client must not clear the caller's buffer");
    }

    @Test
    @Requirement("TBX_053")
    @DisplayName("TBX_053: a failed log send reports false rather than throwing")
    void failedLogSendReportsFalse() throws IOException {
        PluginApi api = clientReturning(500, "nope");

        List<PluginEvent> events = new ArrayList<>();
        events.add(new PluginEvent(PluginEvent.Level.ERROR, "boom"));

        assertFalse(api.sendPluginEvents(events).join(),
                "a log failure must not fail the future; logs are best-effort");
    }

    @Test
    @Requirement("TBX_054")
    @DisplayName("TBX_054: telemetry reports the success flag from the response body")
    void telemetryReadsSuccessFlag() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        PluginApi api = clientFor(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(readRequest(exchange));
            respond(exchange, 200, "{\"success\":true}");
        });

        StartupTelemetry telemetry = new StartupTelemetry("BUKKIT", "1.21.1", true, "3.0.0");
        assertTrue(api.sendTelemetry(SECRET, telemetry).join());
        assertEquals("/analytics/startup", path.get());
        assertTrue(body.get().contains("\"platform\":\"BUKKIT\""));
        assertTrue(body.get().contains("\"version\":\"3.0.0\""));
        stopServer();

        // A 200 whose body says success=false must be reported as false.
        PluginApi refused = clientReturning(200, "{\"success\":false}");
        assertFalse(refused.sendTelemetry(SECRET, telemetry).join());
    }

    // ------------------------------------------------------------------
    // Coupons
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_055")
    @DisplayName("TBX_055: coupons are listed with pagination and parsed in full")
    void couponsAreListedWithPagination() throws IOException {
        PluginApi api = clientReturning(200,
                "{\"pagination\":{\"totalResults\":3,\"currentPage\":1,\"lastPage\":2,"
                        + "\"previous\":null,\"next\":\"http://next\"},"
                        + "\"data\":[{\"id\":1,\"code\":\"SAVE\","
                        + "\"effective\":{\"type\":\"package\",\"packages\":[9],\"categories\":[]},"
                        + "\"discount\":{\"type\":\"percentage\",\"percentage\":25.0,\"value\":0},"
                        + "\"expire\":{\"redeem_unlimited\":\"false\",\"expire_never\":\"true\","
                        + "\"limit\":0,\"date\":\"2026-01-01\"},"
                        + "\"basket_type\":\"single\",\"start_date\":\"2026-01-01\","
                        + "\"user_limit\":1,\"minimum\":0,\"username\":\"\",\"note\":\"n\"}]}");

        PaginatedResponse<Coupon> page = api.getCoupons(SECRET).join();

        assertEquals(3, page.getPagination().getTotalResults());
        assertTrue(page.getPagination().hasNext());
        assertEquals(1, page.getData().size());
        Coupon coupon = page.getData().get(0);
        assertEquals("SAVE", coupon.getCode());
        assertEquals(Coupon.Effective.Type.PACKAGE, coupon.getEffective().getType());
        assertEquals(Collections.singletonList(9), coupon.getEffective().getPackages());
        assertEquals(Coupon.DiscountType.PERCENTAGE, coupon.getDiscount().getType());
        assertEquals(Coupon.BasketType.SINGLE, coupon.getBasketType());
        // The expiry flags arrive as quoted booleans; they must still read as booleans.
        assertTrue(coupon.getExpire().isExpireNever());
        assertFalse(coupon.getExpire().isRedeemUnlimited());
    }

    @Test
    @Requirement("TBX_055")
    @DisplayName("TBX_055: a single coupon is unwrapped from its data envelope")
    void singleCouponIsUnwrapped() throws IOException {
        PluginApi api = clientReturning(200, "{\"data\":{\"id\":77,\"code\":\"X\"}}");

        assertEquals(77, api.getCoupon(SECRET, 77).join().getId());
    }

    @Test
    @Requirement("TBX_055")
    @DisplayName("TBX_055: a coupon response with no data envelope is a TebexException")
    void missingDataEnvelopeIsTebexException() throws IOException {
        PluginApi api = clientReturning(200, "{\"id\":77}");

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> api.getCoupon(SECRET, 77).join());
        assertInstanceOf(TebexException.class, thrown.getCause());
    }

    @Test
    @Requirement("TBX_055")
    @DisplayName("TBX_055: a coupon can be created and deleted")
    void couponCanBeCreatedAndDeleted() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        PluginApi create = clientFor(exchange -> {
            body.set(readRequest(exchange));
            respond(exchange, 200, "{\"data\":{\"id\":5,\"code\":\"NEW\"}}");
        });

        CreateCouponRequest request = new CreateCouponRequest()
                .code("NEW")
                .effectiveOnPackages(Collections.singletonList(9))
                .percentageDiscount(10)
                .discountMethod(DiscountMethod.BASKET_TOTAL)
                .basketType(Coupon.BasketType.BOTH)
                .startDate("2026-01-01")
                .canExpire(false);

        assertEquals(5, create.createCoupon(SECRET, request).join().getId());
        assertTrue(body.get().contains("\"effective_on\":\"package\""));
        assertTrue(body.get().contains("\"discount_type\":\"percentage\""));
        // expire_never is the inverse of canExpire.
        assertTrue(body.get().contains("\"expire_never\":true"));
        assertTrue(body.get().contains("\"discount_application_method\":1"));
        stopServer();

        PluginApi delete = clientReturning(204, null);
        assertTrue(delete.deleteCoupon(SECRET, 5).join());
    }

    @Test
    @Requirement("TBX_056")
    @DisplayName("TBX_056: an unfulfillable coupon request is rejected before any request is sent")
    void invalidCouponRequestIsRejectedLocally() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        PluginApi api = clientFor(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"data\":{}}");
        });

        // Expiry enabled with no expiry date — the case the old SDK threw a bare
        // RuntimeException for, from inside the send path.
        CreateCouponRequest missingExpiryDate = new CreateCouponRequest()
                .code("C").effectiveOnCart().percentageDiscount(5).canExpire(true);
        assertThrows(CompletionException.class,
                () -> api.createCoupon(SECRET, missingExpiryDate).join());

        // Each remaining way the request cannot be fulfilled.
        assertThrows(CompletionException.class, () -> api.createCoupon(SECRET,
                new CreateCouponRequest().effectiveOnCart().percentageDiscount(5)).join());
        assertThrows(CompletionException.class, () -> api.createCoupon(SECRET,
                new CreateCouponRequest().code("C").percentageDiscount(5)).join());
        assertThrows(CompletionException.class, () -> api.createCoupon(SECRET,
                new CreateCouponRequest().code("C").effectiveOnCart()).join());
        assertThrows(CompletionException.class, () -> api.createCoupon(SECRET,
                new CreateCouponRequest().code("C")
                        .effectiveOnPackages(Collections.<Integer>emptyList())
                        .percentageDiscount(5)).join());
        assertThrows(CompletionException.class, () -> api.createCoupon(SECRET, null).join());

        assertEquals(0, requests.get(), "no invalid coupon may reach the network");
    }

    // ------------------------------------------------------------------
    // Player administration
    // ------------------------------------------------------------------

    @Test
    @Requirement("TBX_057")
    @DisplayName("TBX_057: a refused ban reports false rather than failing the future")
    void refusedBanReportsFalse() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        PluginApi accepted = clientFor(exchange -> {
            body.set(readRequest(exchange));
            respond(exchange, 200, "{}");
        });
        assertTrue(accepted.createBan(SECRET, "uuid-1", "192.168.1.1", "griefing").join());
        assertTrue(body.get().contains("\"user\":\"uuid-1\""));
        assertTrue(body.get().contains("\"reason\":\"griefing\""));
        stopServer();

        PluginApi refused = clientReturning(422, "{\"error_message\":\"bad identifier\"}");
        assertFalse(refused.createBan(SECRET, "nope", "0.0.0.0", "r").join(),
                "a refused ban is an outcome to report, not an SDK fault");
    }

    @Test
    @Requirement("TBX_058")
    @DisplayName("TBX_058: a player lookup returns the record when the store has one")
    void playerLookupReturnsRecord() throws IOException {
        PluginApi api = clientFor(exchange -> respond(exchange, 200,
                "{\"player\":{\"id\":\"1\",\"username\":\"Notch\",\"meta\":\"m\","
                        + "\"plugin_username_id\":42},"
                        + "\"banCount\":1,\"chargebackRate\":0,"
                        + "\"payments\":[{\"txn_id\":\"t1\",\"time\":1700000000,\"price\":9.99,"
                        + "\"currency\":\"USD\",\"status\":1}],"
                        + "\"purchaseTotals\":{\"USD\":9.99}}"));

        PlayerLookupInfo info = api.getPlayerLookupInfo(SECRET, "Notch").join();

        assertNotNull(info);
        assertEquals("Notch", info.getPlayer().getUsername());
        assertEquals(42, info.getPlayer().getPluginUsernameId());
        assertEquals(1, info.getBanCount());
        assertEquals(1, info.getPayments().size());
        assertEquals("t1", info.getPayments().get(0).getTxnId());
        assertEquals(9.99, info.getPurchaseTotals().get("USD"), 0.0001);
    }

    @Test
    @Requirement("TBX_058")
    @DisplayName("TBX_058: every 'no such customer' signal normalises to null")
    void playerLookupReturnsNullWhenUnknown() throws IOException {
        // 404, 400, and a 200 whose body is an empty array all mean "no record".
        PluginApi notFound = clientReturning(404, "");
        assertNull(notFound.getPlayerLookupInfo(SECRET, "ghost").join());
        stopServer();

        PluginApi badRequest = clientReturning(400, "");
        assertNull(badRequest.getPlayerLookupInfo(SECRET, "ghost").join());
        stopServer();

        PluginApi emptyArray = clientReturning(200, "[]");
        assertNull(emptyArray.getPlayerLookupInfo(SECRET, "ghost").join());
    }

    @Test
    @Requirement("TBX_058")
    @DisplayName("TBX_058: an empty purchaseTotals array does not break the lookup")
    void playerLookupToleratesEmptyPurchaseTotalsArray() throws IOException {
        // The API sends [] rather than {} when there are no totals, which cannot
        // bind to a map.
        PluginApi api = clientReturning(200,
                "{\"player\":{\"id\":\"1\",\"username\":\"Notch\",\"meta\":\"\","
                        + "\"plugin_username_id\":1},"
                        + "\"banCount\":0,\"chargebackRate\":0,\"payments\":[],"
                        + "\"purchaseTotals\":[]}");

        PlayerLookupInfo info = api.getPlayerLookupInfo(SECRET, "Notch").join();

        assertNotNull(info);
        assertTrue(info.getPurchaseTotals().isEmpty());
    }

    @Test
    @Requirement("TBX_058")
    @DisplayName("TBX_058: a username with spaces or slashes is encoded into the path")
    void playerLookupEncodesUsername() throws IOException {
        AtomicReference<String> rawPath = new AtomicReference<>();
        PluginApi api = clientFor(exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, "[]");
        });

        api.getPlayerLookupInfo(SECRET, "a b/c").join();

        assertEquals("/user/a%20b%2Fc", rawPath.get(),
                "a slash in a username must not create a new path segment");
    }
}
