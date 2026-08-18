package io.tebex.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.tebex.TXE;
import io.tebex.exception.AuthenticationException;
import io.tebex.exception.TebexException;
import io.tebex.model.Category;
import io.tebex.model.CheckoutUrl;
import io.tebex.model.CommunityGoal;
import io.tebex.model.Coupon;
import io.tebex.model.CreateCouponRequest;
import io.tebex.model.DuePlayersResponse;
import io.tebex.model.OfflineCommandsResponse;
import io.tebex.model.PaginatedResponse;
import io.tebex.model.PlayerLookupInfo;
import io.tebex.model.PluginEvent;
import io.tebex.model.QueuedCommand;
import io.tebex.model.QueuedPlayer;
import io.tebex.model.ServerEvent;
import io.tebex.model.ServerInformation;
import io.tebex.model.StartupTelemetry;
import io.tebex.model.StorePackage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin client for the Tebex plugin API ({@code https://plugin.tebex.io}).
 *
 * <p>Built on the JDK 8 {@link HttpURLConnection} so the SDK carries no
 * third-party HTTP dependency, no Minecraft dependency, and no API newer than
 * Java 8 — the module compiles to Java 8 bytecode so that every platform module
 * can consume it (see {@code CODE_006}). The base URLs are injectable so the
 * client can be pointed at a local server in tests.
 *
 * <p>Requests are performed on this client's own single daemon thread, never on
 * the caller's thread and deliberately never on the common
 * {@link java.util.concurrent.ForkJoinPool}: {@link HttpURLConnection} blocks
 * while waiting, and the common pool is shared with the host server (its
 * parallelism is one thread on a two-core machine), so a slow Tebex call there
 * could stall unrelated host work. The SDK must never harm the host (TBX_001).
 *
 * <p><b>Three hosts, not one.</b> The plugin API, the plugin-log intake, and the
 * startup analytics endpoint are separate services on separate domains. Each has
 * its own injectable base URL.
 *
 * <p><b>What this class deliberately does not do.</b> It performs no placeholder
 * substitution on command strings (the old SDK did, using platform services this
 * SDK does not have), holds no secret key of its own (every authenticated call
 * takes one, so key rotation cannot leave a stale copy behind), and owns no
 * queue state — batching callers pass the list to send and decide what to do with
 * the outcome.
 */
public final class PluginApi {

    /** The production plugin API base URL. */
    public static final String DEFAULT_BASE_URL = "https://plugin.tebex.io";

    /** The production plugin-log intake base URL, a different host to the plugin API. */
    public static final String DEFAULT_LOGS_BASE_URL = "https://plugin-logs.tebex.io";

    /** The production startup-analytics base URL, a different host again. */
    public static final String DEFAULT_ANALYTICS_BASE_URL = "https://plugin.buycraft.net";

    private static final String SECRET_HEADER = "X-Tebex-Secret";

    /** How long to wait for the connection to be established, in milliseconds. */
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;

    /** How long to wait for the response once connected, in milliseconds. */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    /** The status code at and above which the response body arrives on the error stream. */
    private static final int FIRST_ERROR_STATUS = 400;

    /** The number of events sent per request before the API is asked to take more. */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * The thread every plugin API request runs on: one daemon thread, shared by all
     * clients in the process.
     *
     * <p>Single-threaded because the engine already issues these calls serially from
     * its own worker; concurrent callers queue rather than fan out, which bounds the
     * SDK's thread footprint inside a game server. Daemon so it never delays JVM
     * shutdown.
     */
    private static final ExecutorService REQUEST_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tebex-plugin-api");
                thread.setDaemon(true);
                return thread;
            });

    private final String baseUrl;
    private final String logsBaseUrl;
    private final String analyticsBaseUrl;
    private final Gson gson;

    /**
     * Creates a client for the production APIs.
     */
    public PluginApi() {
        this(DEFAULT_BASE_URL);
    }

    /**
     * Creates a client pointed at a specific plugin API base URL, leaving the log
     * and analytics hosts at their production values.
     *
     * @param baseUrl the plugin API base URL, without a trailing slash
     */
    public PluginApi(String baseUrl) {
        this(baseUrl, DEFAULT_LOGS_BASE_URL, DEFAULT_ANALYTICS_BASE_URL, new Gson());
    }

    /**
     * Creates a client with an explicit JSON codec.
     *
     * @param baseUrl the plugin API base URL, without a trailing slash
     * @param gson    the JSON codec to use
     */
    public PluginApi(String baseUrl, Gson gson) {
        this(baseUrl, DEFAULT_LOGS_BASE_URL, DEFAULT_ANALYTICS_BASE_URL, gson);
    }

    /**
     * Creates a client with every host overridden, so a test can point all three at
     * one stub server.
     *
     * @param baseUrl          the plugin API base URL
     * @param logsBaseUrl      the plugin-log intake base URL
     * @param analyticsBaseUrl the startup-analytics base URL
     * @param gson             the JSON codec to use
     */
    public PluginApi(String baseUrl, String logsBaseUrl, String analyticsBaseUrl, Gson gson) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.logsBaseUrl = stripTrailingSlash(logsBaseUrl);
        this.analyticsBaseUrl = stripTrailingSlash(analyticsBaseUrl);
        this.gson = gson;
    }

    // ------------------------------------------------------------------
    // Store information
    // ------------------------------------------------------------------

    /**
     * Retrieves the store and server information for the given secret key by
     * calling {@code GET /information}.
     *
     * <p>The returned future completes exceptionally with an
     * {@link AuthenticationException} for HTTP 403/404 (bad or unknown key), or a
     * {@link TebexException} for any other non-200 response, a malformed body, or
     * a transport failure.
     *
     * @param secretKey the store secret key
     * @return a future completing with the parsed {@link ServerInformation}
     */
    public CompletableFuture<ServerInformation> getServerInformation(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/information", secretKey, null);
            return parseInformation(response.statusCode, response.body);
        });
    }

    // ------------------------------------------------------------------
    // Command queue — the core loop
    // ------------------------------------------------------------------

    /**
     * Retrieves the players with commands waiting via {@code GET /queue}.
     *
     * <p>Fails fast without touching the network when no secret key is present
     * (TBX_007). The response's {@code next_check} is the API's own backoff
     * instruction and callers are expected to honour it (TBX_009, TBX_010).
     *
     * @param secretKey the store secret key
     * @return a future completing with the due players and queue metadata
     */
    public CompletableFuture<DuePlayersResponse> getDuePlayers(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/queue", secretKey, null);
            return parse(response, 200, "/queue", DuePlayersResponse.class);
        });
    }

    /**
     * Retrieves commands for players who need not be connected, via
     * {@code GET /queue/offline-commands}.
     *
     * @param secretKey the store secret key
     * @return a future completing with the offline commands
     */
    public CompletableFuture<OfflineCommandsResponse> getOfflineCommands(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/queue/offline-commands", secretKey, null);
            return parse(response, 200, "/queue/offline-commands", OfflineCommandsResponse.class);
        });
    }

    /**
     * Retrieves the commands waiting for one connected player, via
     * {@code GET /queue/online-commands/{id}}.
     *
     * <p>The response omits the player, so each returned command is bound to the
     * {@code player} argument and marked as an online command.
     *
     * @param secretKey the store secret key
     * @param player    the player to fetch commands for
     * @return a future completing with that player's commands
     */
    public CompletableFuture<List<QueuedCommand>> getOnlineCommands(String secretKey, QueuedPlayer player) {
        if (player == null) {
            return failed(new TebexException("A player is required to fetch online commands."));
        }
        return authenticated(secretKey, () -> {
            Response response = send("GET",
                    baseUrl + "/queue/online-commands/" + player.getId(), secretKey, null);
            return parseOnlineCommands(response.statusCode, response.body, player);
        });
    }

    /**
     * Acknowledges executed commands via {@code DELETE /queue}, so Tebex stops
     * returning them (TBX_036).
     *
     * <p>Sending an empty list is treated as a no-op that succeeds without a
     * request: the API has nothing to delete, and issuing the call anyway would
     * burn a request on every idle cycle.
     *
     * @param secretKey  the store secret key
     * @param commandIds the ids of the commands that have been executed
     * @return a future completing with {@code true} when the ids were accepted
     */
    public CompletableFuture<Boolean> deleteCommands(String secretKey, List<Integer> commandIds) {
        if (commandIds == null || commandIds.isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        return authenticated(secretKey, () -> {
            JsonObject payload = new JsonObject();
            payload.add("ids", gson.toJsonTree(commandIds));
            Response response = send("DELETE", baseUrl + "/queue", secretKey, gson.toJson(payload));
            // The API answers 204 No Content here, not 200.
            expectStatus(response, 204, "DELETE /queue");
            return Boolean.TRUE;
        });
    }

    // ------------------------------------------------------------------
    // Storefront
    // ------------------------------------------------------------------

    /**
     * Retrieves the store listing (categories and their packages) via
     * {@code GET /listing}.
     *
     * @param secretKey the store secret key
     * @return a future completing with the store's categories
     */
    public CompletableFuture<List<Category>> getListing(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/listing", secretKey, null);
            return parseListing(response.statusCode, response.body);
        });
    }

    /**
     * Retrieves every package on the store via {@code GET /packages}.
     *
     * @param secretKey the store secret key
     * @return a future completing with the store's packages
     */
    public CompletableFuture<List<StorePackage>> getPackages(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/packages", secretKey, null);
            Type listType = new TypeToken<List<StorePackage>>() { }.getType();
            List<StorePackage> packages = parse(response, 200, "/packages", listType);
            return packages == null ? Collections.<StorePackage>emptyList() : packages;
        });
    }

    /**
     * Retrieves one package via {@code GET /package/{id}}.
     *
     * @param secretKey the store secret key
     * @param packageId the package to retrieve
     * @return a future completing with the package
     */
    public CompletableFuture<StorePackage> getPackage(String secretKey, int packageId) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/package/" + packageId, secretKey, null);
            return parse(response, 200, "/package/" + packageId, StorePackage.class);
        });
    }

    /**
     * Creates a hosted checkout link for a package via {@code POST /checkout}
     * (TBX_017).
     *
     * <p>This endpoint answers {@code 201 Created}, and reports validation
     * problems as a {@code 400} carrying an {@code error_message} — which is
     * surfaced as the {@link TebexException} message rather than being flattened
     * into a bare status code, because it is the only place the API explains
     * <em>why</em> a checkout was refused.
     *
     * @param secretKey the store secret key
     * @param packageId the package to check out
     * @param username  the customer's username
     * @return a future completing with the checkout URL
     */
    public CompletableFuture<CheckoutUrl> createCheckoutUrl(String secretKey, int packageId, String username) {
        return authenticated(secretKey, () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("package_id", packageId);
            payload.addProperty("username", username);
            Response response = send("POST", baseUrl + "/checkout", secretKey, gson.toJson(payload));
            return parseCheckoutUrl(response.statusCode, response.body);
        });
    }

    /**
     * Retrieves every community goal via {@code GET /community_goals} (TBX_021).
     *
     * @param secretKey the store secret key
     * @return a future completing with the store's community goals
     */
    public CompletableFuture<List<CommunityGoal>> getCommunityGoals(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/community_goals", secretKey, null);
            Type listType = new TypeToken<List<CommunityGoal>>() { }.getType();
            List<CommunityGoal> goals = parse(response, 200, "/community_goals", listType);
            return goals == null ? Collections.<CommunityGoal>emptyList() : goals;
        });
    }

    /**
     * Retrieves one community goal via {@code GET /community_goals/{id}}
     * (TBX_021).
     *
     * @param secretKey       the store secret key
     * @param communityGoalId the goal to retrieve
     * @return a future completing with the goal
     */
    public CompletableFuture<CommunityGoal> getCommunityGoal(String secretKey, int communityGoalId) {
        return authenticated(secretKey, () -> {
            Response response = send("GET",
                    baseUrl + "/community_goals/" + communityGoalId, secretKey, null);
            return parse(response, 200, "/community_goals/" + communityGoalId, CommunityGoal.class);
        });
    }

    // ------------------------------------------------------------------
    // Events and logs
    // ------------------------------------------------------------------

    /**
     * Sends player join/leave events via {@code POST /events} (TBX_033, TBX_034).
     *
     * <p>Events are sent in batches of {@value #DEFAULT_BATCH_SIZE}. If the API
     * answers {@code 413 Payload Too Large} the batch size is halved and that
     * batch retried, repeatedly, until it fits or the size reaches zero.
     *
     * @param secretKey the store secret key
     * @param events    the events to report
     * @return a future completing with {@code true} if every batch was accepted
     */
    public CompletableFuture<Boolean> sendJoinEvents(String secretKey, List<ServerEvent> events) {
        if (events == null || events.isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        return authenticated(secretKey, () ->
                sendInBatches(events, baseUrl + "/events", secretKey, 204));
    }

    /**
     * Sends buffered warning/error logs to the plugin-log intake (TBX_011,
     * TBX_012, TBX_013, TBX_037).
     *
     * <p>This targets a <em>different host</em> to every other call here and is
     * unauthenticated — the old SDK sent no secret key to it, and that is
     * preserved.
     *
     * <p>Unlike the old SDK this does not clear the caller's buffer. That code
     * discarded queued events on failure as well as success to bound memory
     * growth, which is a sensible policy but belongs with whoever owns the buffer,
     * not in an HTTP client. The return value tells the caller which happened.
     *
     * @param events the events to report
     * @return a future completing with {@code true} if every batch was accepted
     */
    public CompletableFuture<Boolean> sendPluginEvents(List<PluginEvent> events) {
        if (events == null || events.isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        return CompletableFuture.supplyAsync(
                () -> sendInBatches(events, logsBaseUrl + "/events", null, -1),
                REQUEST_EXECUTOR);
    }

    /**
     * Reports server startup analytics.
     *
     * <p>Targets the analytics host, and answers {@code {"success": true}} rather
     * than using the status code alone.
     *
     * @param secretKey the store secret key
     * @param telemetry the payload describing the host server and plugin
     * @return a future completing with the {@code success} flag from the response
     */
    public CompletableFuture<Boolean> sendTelemetry(String secretKey, StartupTelemetry telemetry) {
        return authenticated(secretKey, () -> {
            Response response = send("POST", analyticsBaseUrl + "/analytics/startup",
                    secretKey, gson.toJson(telemetry));
            expectStatus(response, 200, "/analytics/startup");
            JsonObject parsed = readJsonObject(response.body, "/analytics/startup");
            return parsed.has("success") && parsed.get("success").getAsBoolean();
        });
    }

    // ------------------------------------------------------------------
    // Coupons
    // ------------------------------------------------------------------

    /**
     * Retrieves the first page of store coupons via {@code GET /coupons}.
     *
     * @param secretKey the store secret key
     * @return a future completing with a page of coupons
     */
    public CompletableFuture<PaginatedResponse<Coupon>> getCoupons(String secretKey) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/coupons", secretKey, null);
            Type pageType = new TypeToken<PaginatedResponse<Coupon>>() { }.getType();
            return parse(response, 200, "/coupons", pageType);
        });
    }

    /**
     * Retrieves one coupon via {@code GET /coupons/{id}}.
     *
     * @param secretKey the store secret key
     * @param couponId  the coupon to retrieve
     * @return a future completing with the coupon
     */
    public CompletableFuture<Coupon> getCoupon(String secretKey, int couponId) {
        return authenticated(secretKey, () -> {
            Response response = send("GET", baseUrl + "/coupons/" + couponId, secretKey, null);
            expectStatus(response, 200, "/coupons/" + couponId);
            return unwrapData(response.body, "/coupons/" + couponId, Coupon.class);
        });
    }

    /**
     * Creates a coupon via {@code POST /coupons}.
     *
     * @param secretKey the store secret key
     * @param request   the coupon to create
     * @return a future completing with the created coupon
     */
    public CompletableFuture<Coupon> createCoupon(String secretKey, CreateCouponRequest request) {
        if (request == null) {
            return failed(new TebexException("A coupon request is required."));
        }
        try {
            request.validate();
        } catch (IllegalStateException invalid) {
            return failed(new TebexException(invalid.getMessage(), invalid));
        }
        return authenticated(secretKey, () -> {
            Response response = send("POST", baseUrl + "/coupons", secretKey, gson.toJson(request));
            failOnErrorMessage(response, 200, "/coupons");
            return unwrapData(response.body, "/coupons", Coupon.class);
        });
    }

    /**
     * Deletes a coupon via {@code DELETE /coupons/{id}}.
     *
     * @param secretKey the store secret key
     * @param couponId  the coupon to delete
     * @return a future completing with {@code true} once deleted
     */
    public CompletableFuture<Boolean> deleteCoupon(String secretKey, int couponId) {
        return authenticated(secretKey, () -> {
            Response response = send("DELETE", baseUrl + "/coupons/" + couponId, secretKey, null);
            expectStatus(response, 204, "DELETE /coupons/" + couponId);
            return Boolean.TRUE;
        });
    }

    // ------------------------------------------------------------------
    // Player administration
    // ------------------------------------------------------------------

    /**
     * Bans a player from the webstore via {@code POST /bans}.
     *
     * <p>Returns {@code false} on a non-200 response rather than failing the
     * future, which is what the old SDK did. The distinction matters to callers:
     * a refused ban is an outcome to report to an operator, not an SDK fault.
     * Transport failures still fail the future.
     *
     * @param secretKey  the store secret key
     * @param playerUuid the player to ban, by UUID or username
     * @param ip         the IP address to ban alongside the account
     * @param reason     the reason recorded against the ban
     * @return a future completing with whether the store accepted the ban
     */
    public CompletableFuture<Boolean> createBan(String secretKey, String playerUuid, String ip, String reason) {
        return authenticated(secretKey, () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("user", playerUuid);
            payload.addProperty("ip", ip);
            payload.addProperty("reason", reason);
            Response response = send("POST", baseUrl + "/bans", secretKey, gson.toJson(payload));
            return response.statusCode == 200;
        });
    }

    /**
     * Looks up the store's record of a player via {@code GET /user/{username}}.
     *
     * <p>Answers {@code null} when the store has no record: the API signals this
     * with {@code 404}, {@code 400}, or a {@code 200} whose body is the empty JSON
     * array {@code []}. All three are normalised to {@code null} rather than an
     * exception, because "no such customer" is an ordinary answer to this
     * question.
     *
     * @param secretKey the store secret key
     * @param username  the username or UUID to look up
     * @return a future completing with the player's record, or {@code null}
     */
    public CompletableFuture<PlayerLookupInfo> getPlayerLookupInfo(String secretKey, String username) {
        return authenticated(secretKey, () -> {
            String encoded = urlEncode(username);
            Response response = send("GET", baseUrl + "/user/" + encoded, secretKey, null);
            return parsePlayerLookup(response.statusCode, response.body);
        });
    }

    // ------------------------------------------------------------------
    // Parsing seams — package-private so they can be tested without a server
    // ------------------------------------------------------------------

    /**
     * Parses an {@code /information} response, translating error statuses into
     * the SDK's exception types.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body
     * @return the parsed information
     */
    ServerInformation parseInformation(int statusCode, String body) {
        if (statusCode == 403 || statusCode == 404) {
            throw new CompletionException(
                    new AuthenticationException("The provided secret key was rejected (HTTP " + statusCode + ")."));
        }
        if (statusCode != 200) {
            throw new CompletionException(
                    new TebexException("Unexpected status code from /information (HTTP " + statusCode + ")."));
        }

        try {
            ServerInformation information = gson.fromJson(body, ServerInformation.class);
            if (information == null || information.getAccount() == null) {
                throw new CompletionException(
                        new TebexException("The /information response was empty or malformed."));
            }
            return information;
        } catch (JsonSyntaxException e) {
            throw new CompletionException(
                    new TebexException("Failed to parse the /information response: " + e.getMessage(), e));
        }
    }

    /**
     * Parses an online-commands response and binds each command to the player it
     * was requested for.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body
     * @param player     the player the request was made for
     * @return the player's commands, never {@code null}
     */
    List<QueuedCommand> parseOnlineCommands(int statusCode, String body, QueuedPlayer player) {
        String path = "/queue/online-commands";
        expectStatus(new Response(statusCode, body), 200, path);
        JsonObject parsed = readJsonObject(body, path);
        Type listType = new TypeToken<List<QueuedCommand>>() { }.getType();
        List<QueuedCommand> commands = fromJson(parsed.get("commands"), listType, path);
        if (commands == null) {
            return Collections.emptyList();
        }
        for (QueuedCommand command : commands) {
            command.bindOnlinePlayer(player);
        }
        return commands;
    }

    /**
     * Parses a {@code /listing} response, which nests the categories under a
     * {@code categories} key and reports refusals with an {@code error_message}.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body
     * @return the store's categories, never {@code null}
     */
    List<Category> parseListing(int statusCode, String body) {
        failOnErrorMessage(new Response(statusCode, body), 200, "/listing");
        JsonObject parsed = readJsonObject(body, "/listing");
        Type listType = new TypeToken<List<Category>>() { }.getType();
        List<Category> categories = fromJson(parsed.get("categories"), listType, "/listing");
        return categories == null ? Collections.<Category>emptyList() : categories;
    }

    /**
     * Parses a {@code /checkout} response, honouring its {@code 201} success code
     * and its {@code 400} error-message contract.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body
     * @return the parsed checkout URL
     */
    CheckoutUrl parseCheckoutUrl(int statusCode, String body) {
        // 201 Created, not 200 — and a 400 carries the reason in error_message.
        failOnErrorMessage(new Response(statusCode, body), 201, "/checkout");
        CheckoutUrl checkoutUrl = fromJson(body, CheckoutUrl.class, "/checkout");
        if (checkoutUrl == null || checkoutUrl.getUrl() == null) {
            throw new CompletionException(
                    new TebexException("The /checkout response contained no url."));
        }
        return checkoutUrl;
    }

    /**
     * Parses a {@code /user/{username}} response, normalising every "no such
     * customer" signal to {@code null}.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body
     * @return the player's record, or {@code null} if the store has none
     */
    PlayerLookupInfo parsePlayerLookup(int statusCode, String body) {
        if (statusCode == 404 || statusCode == 400) {
            return null;
        }
        expectStatus(new Response(statusCode, body), 200, "/user");
        String trimmed = body == null ? "" : body.trim();
        // The API answers with an empty JSON *array* when it holds no record.
        if (trimmed.isEmpty() || "[]".equals(trimmed)) {
            return null;
        }
        JsonObject parsed = readJsonObject(trimmed, "/user");
        if (parsed.has("error_message")) {
            throw new CompletionException(
                    new TebexException(parsed.get("error_message").getAsString()));
        }
        // purchaseTotals is [] rather than {} when empty, which cannot bind to a
        // map; drop it and let the model default to an empty map.
        if (parsed.has("purchaseTotals") && !parsed.get("purchaseTotals").isJsonObject()) {
            parsed.remove("purchaseTotals");
        }
        return fromJson(parsed, PlayerLookupInfo.class, "/user");
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Runs an authenticated call on the request thread, refusing before any
     * network access if no secret key was supplied (TBX_007).
     *
     * @param secretKey the secret key the call will authenticate with
     * @param call      the work to perform
     * @param <T>       the result type
     * @return a future completing with the call's result
     */
    private <T> CompletableFuture<T> authenticated(String secretKey, java.util.function.Supplier<T> call) {
        if (secretKey == null || secretKey.trim().isEmpty()) {
            return failed(new AuthenticationException(
                    "No secret key is configured; the store must be set up before this call."));
        }
        return CompletableFuture.supplyAsync(call, REQUEST_EXECUTOR);
    }

    /**
     * Returns a future already failed with the given cause, wrapped the same way
     * an in-flight failure would be.
     *
     * @param cause the failure
     * @param <T>   the result type the caller expected
     * @return the failed future
     */
    private static <T> CompletableFuture<T> failed(TebexException cause) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(new CompletionException(cause));
        return future;
    }

    /**
     * Posts a list in batches, halving the batch on {@code 413} until it fits.
     *
     * @param items          the items to send
     * @param url            the absolute URL to post to
     * @param secretKey      the secret key, or {@code null} for an unauthenticated host
     * @param expectedStatus the success status, or {@code -1} to accept any 2xx
     * @param <T>            the item type
     * @return {@code true} if every batch was accepted
     */
    private <T> boolean sendInBatches(List<T> items, String url, String secretKey, int expectedStatus) {
        int offset = 0;
        int batchSize = DEFAULT_BATCH_SIZE;

        while (offset < items.size()) {
            List<T> batch = new ArrayList<T>(items.subList(offset, Math.min(offset + batchSize, items.size())));
            Response response = send("POST", url, secretKey, gson.toJson(batch));

            if (response.statusCode == 413) {
                batchSize = batchSize / 2;
                if (batchSize < 1) {
                    // A single item is too large to send; give up rather than loop.
                    return false;
                }
                // offset is deliberately not advanced: the same items are retried
                // at the smaller size. Note that batchSize is never raised again
                // for the rest of this call, so one 413 degrades every later batch
                // — the old SDK behaved identically (it passed the reduced size
                // down its recursion), and a store that rejected 100 once will
                // very likely reject the next 100 too.
                continue;
            }

            boolean accepted = expectedStatus < 0
                    ? response.statusCode >= 200 && response.statusCode < 300
                    : response.statusCode == expectedStatus;
            if (!accepted) {
                return false;
            }
            offset += batch.size();
        }
        return true;
    }

    /**
     * Performs an HTTP request against an absolute URL and returns the status code
     * and body verbatim, leaving interpretation to the caller.
     *
     * @param method    the HTTP method
     * @param url       the absolute URL to call
     * @param secretKey the secret key to authenticate with, or {@code null} to omit it
     * @param body      the request body, or {@code null} for none
     * @return the status code and body of the response
     */
    private Response send(String method, String url, String secretKey, String body) {
        HttpURLConnection connection = null;
        debug(method + " " + url + (body == null ? "" : " body=" + body));
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            if (secretKey != null) {
                connection.setRequestProperty(SECRET_HEADER, secretKey);
            }
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);

            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode);
            debug("<- " + statusCode + " " + url + (responseBody.isEmpty() ? "" : " body=" + responseBody));
            return new Response(statusCode, responseBody);
        } catch (IOException err) {
            // A transport failure is not an authentication failure: surface it as
            // the general SDK exception so the engine logs and recovers (TBX_001).
            throw new CompletionException(
                    new TebexException("Failed to call " + url + ": " + err.getMessage(), err));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Logs a request or response line when debug mode is on (TBX_027).
     *
     * <p>The flag is checked before the caller's string is used so that debugging
     * costs nothing while it is off.
     *
     * <p><b>The secret key is never logged.</b> It travels in the
     * {@code X-Tebex-Secret} header, and headers are deliberately not included
     * here — only the method, URL, status and bodies are. Response bodies can
     * contain the store's <em>public</em> token, which is public by definition.
     *
     * @param message the line to log
     */
    private static void debug(String message) {
        if (!TXE.DEBUG_MODE) {
            return;
        }
        TXE.Log().Debug(message);
    }

    /**
     * Reads the response body as UTF-8 text, taking it from the error stream for
     * error statuses (where {@link HttpURLConnection#getInputStream()} throws).
     *
     * @param connection the connection to read from
     * @param statusCode the status code already read from the connection
     * @return the body text, or an empty string if the response had no body
     * @throws IOException if the body cannot be read
     */
    private static String readBody(HttpURLConnection connection, int statusCode) throws IOException {
        InputStream stream = statusCode >= FIRST_ERROR_STATUS
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return "";
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try (InputStream in = stream) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Checks the status code and deserializes the body in one step.
     *
     * @param response       the response to interpret
     * @param expectedStatus the only status treated as success
     * @param path           the path, for error messages
     * @param type           the type to deserialize into
     * @param <T>            the result type
     * @return the deserialized body
     */
    private <T> T parse(Response response, int expectedStatus, String path, Type type) {
        expectStatus(response, expectedStatus, path);
        return fromJson(response.body, type, path);
    }

    /**
     * Fails unless the response carries exactly the expected status.
     *
     * <p>403 <em>and</em> 404 become an {@link AuthenticationException}, matching
     * both {@link #parseInformation} and the documented contract of
     * {@code AuthenticationException} itself ("rejected key, or a key that does
     * not resolve to a store"). The old SDK drew the same line with a separate
     * {@code ServerNotFoundException} on 404; collapsing the two keeps one
     * meaning — "this secret key will not work" — under one type, which is the
     * distinction a caller acts on. Every other status is a
     * {@link TebexException}.
     *
     * <p>Endpoints for which 404 means something else must handle it
     * <em>before</em> calling this; {@code /user/{username}} does, because there
     * a 404 means "no such customer", not "no such store".
     *
     * @param response       the response to check
     * @param expectedStatus the only status treated as success
     * @param path           the path, for error messages
     */
    private static void expectStatus(Response response, int expectedStatus, String path) {
        if (response.statusCode == expectedStatus) {
            return;
        }
        if (response.statusCode == 403 || response.statusCode == 404) {
            throw new CompletionException(new AuthenticationException(
                    "The provided secret key was rejected by " + path
                            + " (HTTP " + response.statusCode + ")."));
        }
        throw new CompletionException(new TebexException(
                "Unexpected status code from " + path + " (HTTP " + response.statusCode + ")."));
    }

    /**
     * Like {@link #expectStatus} but first surfaces an {@code error_message} from
     * the body, which several endpoints use to explain a refusal.
     *
     * @param response       the response to check
     * @param expectedStatus the only status treated as success
     * @param path           the path, for error messages
     */
    private void failOnErrorMessage(Response response, int expectedStatus, String path) {
        if (response.statusCode == expectedStatus) {
            return;
        }
        try {
            JsonObject parsed = gson.fromJson(response.body, JsonObject.class);
            if (parsed != null && parsed.has("error_message")) {
                throw new CompletionException(
                        new TebexException(parsed.get("error_message").getAsString()));
            }
        } catch (JsonSyntaxException ignored) {
            // Not a JSON error envelope; fall through to the status-code message.
        }
        expectStatus(response, expectedStatus, path);
    }

    /**
     * Deserializes the {@code data} envelope the coupon endpoints wrap their
     * payload in.
     *
     * @param body the response body
     * @param path the path, for error messages
     * @param type the type to deserialize into
     * @param <T>  the result type
     * @return the deserialized {@code data} member
     */
    private <T> T unwrapData(String body, String path, Class<T> type) {
        JsonObject parsed = readJsonObject(body, path);
        if (!parsed.has("data")) {
            throw new CompletionException(
                    new TebexException("The " + path + " response contained no data member."));
        }
        return fromJson(parsed.get("data"), type, path);
    }

    /**
     * Parses a body that must be a JSON object.
     *
     * @param body the response body
     * @param path the path, for error messages
     * @return the parsed object
     */
    private JsonObject readJsonObject(String body, String path) {
        JsonObject parsed = fromJson(body, JsonObject.class, path);
        if (parsed == null) {
            throw new CompletionException(
                    new TebexException("The " + path + " response was empty."));
        }
        return parsed;
    }

    /**
     * Deserializes JSON text, translating a syntax error into a
     * {@link TebexException} so a malformed response never escapes as a raw Gson
     * failure (TBX_001).
     *
     * @param json the JSON text
     * @param type the target type
     * @param path the path, for error messages
     * @param <T>  the result type
     * @return the deserialized value
     */
    private <T> T fromJson(String json, Type type, String path) {
        try {
            return gson.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            throw new CompletionException(new TebexException(
                    "Failed to parse the " + path + " response: " + e.getMessage(), e));
        }
    }

    /**
     * Deserializes an already-parsed JSON tree with the same error handling as
     * {@link #fromJson(String, Type, String)}.
     *
     * @param element the JSON tree, may be {@code null}
     * @param type    the target type
     * @param path    the path, for error messages
     * @param <T>     the result type
     * @return the deserialized value, or {@code null} if the element was absent
     */
    private <T> T fromJson(com.google.gson.JsonElement element, Type type, String path) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return gson.fromJson(element, type);
        } catch (JsonSyntaxException e) {
            throw new CompletionException(new TebexException(
                    "Failed to parse the " + path + " response: " + e.getMessage(), e));
        }
    }

    /**
     * Percent-encodes a single path segment.
     *
     * <p>{@link URLEncoder} targets query strings, where a space becomes
     * {@code +}; in a path segment that is a literal plus, so it is re-escaped.
     *
     * @param segment the value to encode
     * @return the encoded segment
     */
    private static String urlEncode(String segment) {
        if (segment == null) {
            return "";
        }
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            // Every JVM is required to support UTF-8.
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    /**
     * Removes a single trailing slash from a base URL so paths can be
     * concatenated without producing a double slash.
     *
     * @param url the configured base URL
     * @return the base URL without a trailing slash
     */
    private static String stripTrailingSlash(String url) {
        if (url == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * An HTTP status code paired with the response body it arrived with.
     */
    private static final class Response {

        /** The HTTP status code of the response. */
        private final int statusCode;

        /** The response body as UTF-8 text, empty if there was none. */
        private final String body;

        /**
         * Creates a response record.
         *
         * @param statusCode the HTTP status code
         * @param body       the response body
         */
        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
