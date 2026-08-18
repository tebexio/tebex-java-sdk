package io.tebex;

import io.tebex.hooks.*;
import io.tebex.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The integration-facing half of the SDK: the command dispatcher, the platform
 * hooks, the store credentials, and the outbound event queues the engine drains.
 *
 * <p>An instance belongs to exactly one {@link TXE}. Everything a host installs —
 * a way to run commands, to inspect players, to read and write configuration —
 * arrives through the {@code Hook*} methods, and every one of them is optional:
 * with a hook missing the affected work is skipped or refused with a diagnostic
 * rather than throwing out of the SDK (TBX_060).
 */
public class Plugin {

    /** The player tags a store may write into a deliverable command (TBX_063). */
    private static final Pattern PLAYER_TAG =
            Pattern.compile("\\{(id|uuid|name|username)\\}", Pattern.CASE_INSENSITIVE);

    /** A Java Edition UUID as the plugin API sends it: 32 hex characters, no dashes. */
    private static final Pattern UNDASHED_UUID = Pattern.compile("[0-9a-fA-F]{32}");

    /** Configuration key holding the store secret key (TBX_003, TBX_004). */
    public static final String CONFIG_SECRET_KEY = "secret-key";

    /** Configuration key naming the buy command, {@code buy} by default (CFG_001). */
    public static final String CONFIG_BUY_COMMAND_NAME = "buy-command-name";

    /** Configuration key enabling or disabling the buy command (CFG_002). */
    public static final String CONFIG_BUY_COMMAND_ENABLED = "buy-command-enabled";

    /** Configuration key enabling or disabling debug logging (CFG_003). */
    public static final String CONFIG_DEBUG = "debug";

    /** Configuration key enabling or disabling plugin log collection (CFG_004). */
    public static final String CONFIG_LOG_EVENTS = "log-events";

    /** Configuration key declaring the server sits behind a proxy (CFG_005). */
    public static final String CONFIG_PROXY_MODE = "proxy-mode";

    /** The buy command's name when the configuration does not override it. */
    private static final String DEFAULT_BUY_COMMAND = "buy";

    /**
     * How long a command that calls the API waits before giving up. Bounded
     * because these commands run on the caller's thread, which on most platforms
     * is the server main thread — an unbounded wait there stalls the whole
     * server.
     */
    private static final int BLOCKING_CALL_TIMEOUT_SECONDS = 15;

    /**
     * How long to wait before re-checking when the plugin is not wired up enough
     * to deliver anything. Long enough not to spam the log every second.
     */
    private static final int NO_HOOK_BACKOFF_SECONDS = 120;

    /**
     * The engine that owns this plugin.
     *
     * <p>Held explicitly rather than reached for through {@code TXE.get()}: each
     * engine owns its own plugin, API client and task queue, so resolving the
     * singleton meant a non-singleton engine's plugin talked to a *different*
     * engine's client and queued its deliverables onto a queue nobody drained.
     */
    private final TXE txe;

    // volatile: written by whoever runs /tebex secret, read by the engine thread
    // on every tick. See applyCredentials for the ordering guarantee.
    volatile String key = "";
    volatile ServerInformation.Server server;
    volatile ServerInformation.Account account;

    // Set once during startup by the integration, read on every authentication.
    private volatile String expectedGameType;

    // Cached store catalogue, refreshed by the engine (TASK_004, TBX_061). Fully
    // qualified because this file imports io.tebex.model.*, which has its own
    // Category — the two would be ambiguous.
    private volatile List<io.tebex.headless.model.Category> categories;

    ServerCommand serverCommand;
    PlayerActions playerActions;
    Configuration config;

    // Typed PluginEvent, not the LogEvent named in the previous commented-out
    // form: no LogEvent type was ever written, and PluginEvent is what
    // PluginApi.sendPluginEvents accepts and what TBX_053/TBX_037 cover. Swap the
    // type here if a lighter log model is still wanted.
    ConcurrentLinkedQueue<PluginEvent> logEvents = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<ServerEvent> serverEvents = new ConcurrentLinkedQueue<>();
    ConcurrentHashMap<Integer, QueuedCommand> executedCommands = new ConcurrentHashMap<>();

    /**
     * Creates a plugin bound to the engine that owns it.
     *
     * <p>Called from a {@code TXE} field initialiser, so this must do nothing but
     * store the reference — the engine is not fully constructed yet.
     *
     * @param owner the owning engine
     */
    Plugin(TXE owner) {
        this.txe = owner;
    }

    /**
     * Returns the cached store catalogue, as last refreshed from the Headless API.
     *
     * @return the categories and their packages, never {@code null}; empty until
     *         the first refresh completes
     */
    public List<io.tebex.headless.model.Category> Categories() {
        List<io.tebex.headless.model.Category> current = categories;
        return current == null
                ? Collections.<io.tebex.headless.model.Category>emptyList()
                : current;
    }

    /**
     * Replaces the cached catalogue. Called by the engine's refresh cycle.
     *
     * @param refreshed the catalogue just fetched, may be {@code null}
     */
    void setCategories(List<io.tebex.headless.model.Category> refreshed) {
        this.categories = refreshed;
    }

    /**
     * Declares the game type this integration is built for, so a secret key
     * belonging to a different game's store is refused (TBX_006).
     *
     * <p>Optional: with none declared, any store the key resolves to is accepted.
     * Set it during startup, before the engine authenticates.
     *
     * @param gameType the expected game type exactly as the store reports it, for
     *                 example {@code "Minecraft: Java Edition"}; {@code null} or
     *                 blank to accept any store
     */
    public void ExpectGameType(String gameType) {
        this.expectedGameType = gameType == null || gameType.trim().isEmpty() ? null : gameType.trim();
    }

    /**
     * Returns the game type this integration expects.
     *
     * @return the expected game type, or {@code null} if any store is accepted
     */
    public String ExpectedGameType() {
        return expectedGameType;
    }

    /**
     * Returns whether an account's game type is one this integration will accept
     * (TBX_006).
     *
     * @param candidate the account resolved from a secret key
     * @return {@code true} if the store may be adopted
     */
    boolean gameTypeMatches(ServerInformation.Account candidate) {
        String expected = expectedGameType;
        if (expected == null) {
            return true;
        }
        return candidate != null && expected.equalsIgnoreCase(candidate.getGameType());
    }

    /**
     * Returns whether players are identified by an authenticated account
     * (TBX_006, CFG_005).
     *
     * <p>The store's own {@code online_mode} answers this, except when the
     * operator has declared a proxy in configuration: behind a proxy the backend
     * server sees unauthenticated connections even though the proxy in front of
     * it authenticated them, so its own view would say offline and player
     * identities would not line up with the store's.
     *
     * @return {@code true} if players are authenticated
     */
    public boolean IsOnlineMode() {
        if (booleanConfig(CONFIG_PROXY_MODE, false)) {
            return true;
        }
        ServerInformation.Account current = account;
        return current != null && current.isOnlineMode();
    }

    /**
     * Handles one line of console or player input (TBX_022–TBX_031, CFG_001).
     *
     * <p>Two prefixes are recognised: {@code /tebex}, which carries every
     * administrative subcommand, and the configured buy command, which is a
     * top-level command of its own because that is how players expect to reach a
     * store. Anything else is rejected with a diagnostic rather than dispatched
     * (TBX_060).
     *
     * @param rawInput       the raw input command, ex. "/tebex help"
     * @param playerUsername the invoking player, empty or {@code null} for console
     * @param playerUuid     the invoking player's uuid, may be empty
     * @return the lines to show the caller
     * @throws Exception if a command handler fails in a way it cannot report
     */
    public String[] Input(String rawInput, String playerUsername, String playerUuid) throws Exception {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return singleLine("input cannot be empty");
        }
        rawInput = rawInput.trim();
        if (rawInput.startsWith("/")) { // strip leading / if present
            rawInput = rawInput.substring(1);
        }
        // Split on runs of whitespace, not a single space: "tebex  help" would
        // otherwise produce an empty token and dispatch nothing.
        String[] tokens = rawInput.trim().split("\\s+");

        // The prefix is validated for every input length, not just the one-token
        // case — otherwise "tebexx help" would fall through and run "help".
        String prefix = tokens[0].toLowerCase();
        if (!prefix.equals("tebex")) {
            // Checked after the tebex prefix, so a configuration that names the
            // buy command "tebex" cannot shadow the administrative commands.
            if (isBuyCommand(prefix)) {
                if (!hasPermission(playerUsername, playerUuid, "tebex.buy")) {
                    return singleLine("you do not have the necessary permissions");
                }
                return buy(Arrays.copyOfRange(tokens, 1, tokens.length), playerUsername);
            }
            return singleLine("unrecognized command: '" + prefix + "'. all commands must be prefixed with /tebex");
        }
        if (tokens.length == 1) { // "tebex" on its own behaves as "tebex help"
            return helpText();
        }

        // 2 or more tokens, ex. "/tebex help"
        String command = tokens[1].toLowerCase();
        if (!hasPermission(playerUsername, playerUuid, "tebex." + command)) {
            return singleLine("you do not have the necessary permissions");
        }
        String[] args = Arrays.copyOfRange(tokens, 2, tokens.length);
        List<String> out = new ArrayList<>();
        switch (command) {
            case "help":
                return helpText();
            case "info":
                if (account == null || server == null) {
                    return singleLine("not connected to a store yet. set a key with /tebex secret <key>.");
                }
                out.add("Store Information: " + account.getName());
                out.add("Server: " + server.getName());
                out.add("Webstore: " + account.getDomain());
                out.add("Prices are in " + account.getCurrency().getSymbol() + account.getCurrency().getIso4217());
                break;
            case "secret":
                if (args.length < 1 || args[0].trim().isEmpty()) {
                    return singleLine("usage: /tebex secret <key>");
                }
                out.addAll(setSecretKey(args[0].trim()));
                break;
            case "forcecheck":
                if (txe.ForceCheckNow()) {
                    out.add("Queue check scheduled; it will run on the next engine tick.");
                } else {
                    out.add("The engine is not running, so no check was scheduled.");
                }
                break;
            case "reload":
                if (config == null) {
                    return singleLine("no configuration hook is installed, so there is nothing to reload.");
                }
                config.Load();
                out.add("Configuration reloaded.");
                // A reload can change the debug setting as well as the key
                // (CFG_003); both must take effect without a restart.
                applyConfiguredDebugMode();
                // A reload can bring in a different key; adopt it rather than
                // carrying on with the old one.
                String reloadedKey = config.Get(CONFIG_SECRET_KEY);
                if (reloadedKey != null && !reloadedKey.trim().isEmpty() && !reloadedKey.equals(key)) {
                    String candidate = reloadedKey.trim();
                    out.addAll(setSecretKey(candidate));
                    if (!candidate.equals(key)) {
                        // setSecretKey leaves the working key in place when the
                        // candidate is rejected, which is right — but silence here
                        // would leave the operator believing the reload took
                        // effect while the file and the running key disagree, and
                        // the bad key would be loaded on the next restart.
                        out.add("Warning: the key in the configuration file was not accepted. "
                                + "The server is still using the previously loaded key - "
                                + "correct the file before restarting.");
                    }
                }
                break;
            case "buy":
                out.addAll(Arrays.asList(buy(args, playerUsername)));
                break;
            case "redeem":
                out.addAll(redeem(playerUsername));
                break;
            case "goals":
                out.addAll(goals());
                break;
            case "checkout":
            case "sendlink":
            case "ban":
            case "lookup":
                out.add("'" + command + "' is not implemented yet.");
                break;
            case "debug":
                if (args.length < 1) {
                    out.add("Debug mode is currently " + (TXE.DEBUG_MODE ? "enabled" : "disabled")
                            + ". usage: /tebex debug <true|false>");
                } else if (args[0].equalsIgnoreCase("true")) {
                    TXE.DEBUG_MODE = true;
                    out.add("Debug mode enabled.");
                } else if (args[0].equalsIgnoreCase("false")) {
                    TXE.DEBUG_MODE = false;
                    out.add("Debug mode disabled.");
                } else {
                    out.add("Invalid argument: must be true/false");
                }
                break;
            default:
                return singleLine("unrecognized command: " + command);
        }
        return out.toArray(new String[0]);
    }

    /**
     * Validates a secret key against the API and adopts it only if it is accepted.
     *
     * <p>Synchronous, unlike the fire-and-forget version this replaces: {@code Input}
     * returns its reply as a {@code String[]}, so a result produced on a callback
     * thread after the method had already returned could never be delivered. The
     * wait is bounded so a hung API cannot stall the caller's thread indefinitely.
     *
     * <p>Because the swap happens only after a successful response, a rejected key
     * leaves the previous credentials untouched — no rollback is needed (TBX_005).
     * A key that resolves to the wrong game's store is rejected the same way
     * (TBX_006).
     *
     * @param newKey the candidate secret key
     * @return the lines to report back to the caller
     */
    private List<String> setSecretKey(String newKey) {
        List<String> out = new ArrayList<>();
        try {
            ServerInformation info = txe.PluginApi()
                    .getServerInformation(newKey)
                    .get(BLOCKING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!gameTypeMatches(info.getAccount())) {
                out.add("That secret key belongs to a '" + info.getAccount().getGameType()
                        + "' store, but this server is a '" + expectedGameType + "' integration.");
                out.add("The previous key is still in use.");
                return out;
            }

            applyCredentials(newKey, info.getAccount(), info.getServer());

            if (config == null) {
                out.add("Secret key set, but no configuration hook is installed - "
                        + "it will not persist across a restart.");
            } else {
                config.Set(CONFIG_SECRET_KEY, newKey);
                config.Save();
                out.add("Secret key set. Connected to '" + info.getAccount().getName() + "'.");
            }
        } catch (Exception failure) {
            out.add("That secret key was rejected: " + rootMessage(failure));
            out.add("The previous key is still in use.");
        }
        return out;
    }

    /**
     * Creates a checkout link for a package (CFG_001, CFG_002, TBX_017).
     *
     * @param args     the command arguments, the first of which is the package id
     * @param username the buying player, empty for console
     * @return the lines to report back to the caller
     */
    private String[] buy(String[] args, String username) {
        if (key == null || key.trim().isEmpty()) {
            return singleLine("this server is not connected to a store yet.");
        }
        if (args.length < 1 || args[0].trim().isEmpty()) {
            return singleLine("usage: /" + buyCommandName() + " <package id>");
        }
        if (username == null || username.trim().isEmpty()) {
            // The checkout is created for a named customer, so there is nobody to
            // create it for when the console runs it.
            return singleLine("the " + buyCommandName() + " command must be run by a player.");
        }

        int packageId;
        try {
            packageId = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException notANumber) {
            return singleLine("'" + args[0] + "' is not a package id.");
        }

        try {
            CheckoutUrl checkout = txe.PluginApi()
                    .createCheckoutUrl(key, packageId, username)
                    .get(BLOCKING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new String[] {
                "Complete your purchase here:",
                checkout.getUrl(),
            };
        } catch (Exception failure) {
            return singleLine("Could not create a checkout link: " + rootMessage(failure));
        }
    }

    /**
     * Delivers the commands waiting for the calling player right now, rather than
     * making them wait for the next scheduled queue check (TBX_025).
     *
     * @param username the calling player, empty for console
     * @return the lines to report back to the caller
     */
    private List<String> redeem(String username) {
        List<String> out = new ArrayList<>();
        if (username == null || username.trim().isEmpty()) {
            out.add("the redeem command must be run by a player.");
            return out;
        }
        if (key == null || key.trim().isEmpty()) {
            out.add("this server is not connected to a store yet.");
            return out;
        }
        if (serverCommand == null) {
            out.add("no server command hook is installed, so nothing can be delivered.");
            return out;
        }

        try {
            DuePlayersResponse due = txe.PluginApi().getDuePlayers(key)
                    .get(BLOCKING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            QueuedPlayer player = null;
            for (QueuedPlayer candidate : due.getPlayers()) {
                if (username.equalsIgnoreCase(candidate.getName())) {
                    player = candidate;
                    break;
                }
            }
            if (player == null) {
                out.add("You have no commands waiting. Purchases can take a moment to arrive.");
                return out;
            }

            List<QueuedCommand> commands = txe.PluginApi().getOnlineCommands(key, player)
                    .get(BLOCKING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int queued = 0;
            for (QueuedCommand command : commands) {
                if (queueCommand(command)) {
                    queued++;
                }
            }

            if (queued == 0) {
                out.add("You have no commands waiting. Purchases can take a moment to arrive.");
            } else {
                out.add("Queued " + queued + " command" + (queued == 1 ? "" : "s") + " for delivery.");
            }
        } catch (Exception failure) {
            out.add("Could not check for your commands: " + rootMessage(failure));
        }
        return out;
    }

    /**
     * Summarises the store's community goals and how far along each one is
     * (TBX_028).
     *
     * @return the lines to report back to the caller
     */
    private List<String> goals() {
        List<String> out = new ArrayList<>();
        if (key == null || key.trim().isEmpty()) {
            out.add("this server is not connected to a store yet.");
            return out;
        }

        try {
            List<CommunityGoal> communityGoals = txe.PluginApi().getCommunityGoals(key)
                    .get(BLOCKING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (communityGoals.isEmpty()) {
                out.add("This store has no community goals.");
                return out;
            }

            out.add("Community goals:");
            for (CommunityGoal goal : communityGoals) {
                // getProgress() is a fraction in [0, 1]; a player reads a
                // percentage.
                out.add("  " + goal.getName() + " - " + formatAmount(goal.getCurrent())
                        + "/" + formatAmount(goal.getTarget())
                        + " (" + Math.round(goal.getProgress() * 100d) + "%)");
            }
        } catch (Exception failure) {
            out.add("Could not load the community goals: " + rootMessage(failure));
        }
        return out;
    }

    /**
     * Formats a goal amount without a trailing {@code .0} on whole numbers.
     *
     * @param amount the amount to format
     * @return the amount as a display string
     */
    private static String formatAmount(double amount) {
        if (amount == Math.rint(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }

    /**
     * Publishes a validated set of credentials as a unit.
     *
     * <p>The three fields are read by the engine thread every tick while
     * {@code /tebex secret} may be replacing them, so they are {@code volatile} and
     * written under a lock. {@code key} is assigned <em>last</em> on purpose: it is
     * the field the engine actually uses to call the API, so ordering it after the
     * store data means a reader can never pick up a new key before the account and
     * server it belongs to.
     *
     * @param newKey     the validated secret key
     * @param newAccount the account it resolves to
     * @param newServer  the server it resolves to
     */
    synchronized void applyCredentials(String newKey, ServerInformation.Account newAccount,
                                       ServerInformation.Server newServer) {
        this.account = newAccount;
        this.server = newServer;
        this.key = newKey;
    }

    /**
     * Returns the secret key held in the integration's configuration (TBX_004).
     *
     * @return the configured key, or {@code null} if there is no configuration
     *         hook or no key in it
     */
    String configuredSecretKey() {
        return configValue(CONFIG_SECRET_KEY);
    }

    /**
     * Applies the configured debug setting to the process-wide flag (CFG_003).
     *
     * <p>A configuration that says nothing about debug leaves the flag alone, so
     * a {@code /tebex debug true} issued at runtime is not undone by a reload.
     */
    void applyConfiguredDebugMode() {
        String configured = configValue(CONFIG_DEBUG);
        if (configured != null) {
            TXE.DEBUG_MODE = Boolean.parseBoolean(configured.trim());
        }
    }

    /**
     * Records an event derived from something the SDK logged, if this store and
     * this operator want them collected (CFG_004).
     *
     * <p>Two switches, either of which is enough to turn collection off: the
     * operator's {@code log-events} configuration key, and the store's own
     * {@code log_events} flag from {@code /information}. Before the engine has
     * authenticated there is no store flag to consult, so only the configuration
     * applies.
     *
     * @param event the event to queue for the plugin-logs service
     */
    void recordLogEvent(PluginEvent event) {
        if (event == null || !collectsLogEvents()) {
            return;
        }
        if (logEvents.size() >= TXE.MAX_QUEUED_EVENTS) {
            // No warning logged here: warning would come straight back through
            // this method and recurse.
            return;
        }
        logEvents.add(event.onStore(account).onServer(server));
    }

    /**
     * Returns whether runtime warnings and errors are collected for Tebex
     * (CFG_004).
     *
     * @return {@code true} if plugin logs should be collected
     */
    public boolean collectsLogEvents() {
        if (!booleanConfig(CONFIG_LOG_EVENTS, true)) {
            return false;
        }
        ServerInformation.Account current = account;
        return current == null || current.isLogEvents();
    }

    /**
     * Returns the name of the buy command, {@code buy} unless configuration says
     * otherwise (CFG_001).
     *
     * @return the buy command name, without a leading slash
     */
    public String buyCommandName() {
        String configured = configValue(CONFIG_BUY_COMMAND_NAME);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_BUY_COMMAND;
        }
        // A configured name may arrive with the slash the operator types.
        String name = configured.trim();
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /**
     * Returns whether the buy command is enabled (CFG_002).
     *
     * @return {@code true} if the buy command may be used
     */
    public boolean isBuyCommandEnabled() {
        return booleanConfig(CONFIG_BUY_COMMAND_ENABLED, true);
    }

    /**
     * Returns whether an input prefix is the buy command.
     *
     * @param prefix the lower-cased first token of the input
     * @return {@code true} if the input should be handled as a purchase
     */
    private boolean isBuyCommand(String prefix) {
        return isBuyCommandEnabled() && buyCommandName().toLowerCase().equals(prefix);
    }

    /**
     * Reads a configuration value, tolerating a missing hook.
     *
     * @param configKey the key to read
     * @return the configured value, or {@code null} if unset or unavailable
     */
    private String configValue(String configKey) {
        Configuration current = config;
        if (current == null) {
            return null;
        }
        return current.Get(configKey);
    }

    /**
     * Reads a boolean configuration value.
     *
     * <p>Anything other than {@code true}/{@code false} (in any case) is treated
     * as unset rather than as {@code false}: a typo in a config file should not
     * silently switch a feature off.
     *
     * @param configKey    the key to read
     * @param defaultValue the value to use when the key is unset or unreadable
     * @return the configured value, or {@code defaultValue}
     */
    private boolean booleanConfig(String configKey, boolean defaultValue) {
        String configured = configValue(configKey);
        if (configured == null) {
            return defaultValue;
        }
        String value = configured.trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return defaultValue;
    }

    /**
     * Resolves whether the caller may run a command.
     *
     * @param username   the invoking player, empty or {@code null} for console
     * @param uuid       the invoking player's uuid, may be empty
     * @param permission the permission node required
     * @return {@code true} if the command may proceed
     */
    private boolean hasPermission(String username, String uuid, String permission) {
        if (username == null || username.trim().isEmpty()) {
            return true; // console has no player to check and is already privileged
        }
        if (playerActions == null) {
            // No hook installed, so no permissions are applied — matching the
            // behaviour chosen for the previous checkPermission helper. A host
            // that wants gating must call HookPlayerActions.
            return true;
        }
        return playerActions.HasPermission(username, uuid == null ? "" : uuid, permission);
    }

    /**
     * Returns the list of available commands (TBX_030, TBX_031).
     *
     * @return the help output
     */
    private String[] helpText() {
        List<String> help = new ArrayList<>(Arrays.asList(
            "Tebex commands:",
            "  /tebex info        - show the connected store",
            "  /tebex secret <key> - set the store secret key",
            "  /tebex forcecheck  - run the command queue check now",
            "  /tebex reload      - reload the configuration file",
            "  /tebex redeem      - deliver your waiting purchases now",
            "  /tebex goals       - show progress towards community goals",
            "  /tebex debug <true|false> - toggle debug logging",
            "  /tebex help        - show this message"));
        if (isBuyCommandEnabled()) {
            help.add("  /" + buyCommandName() + " <package id> - buy a package");
        }
        return help.toArray(new String[0]);
    }

    /**
     * Returns the message of the deepest cause of a throwable, so a reply shows the
     * actual reason rather than a wrapper's class name.
     *
     * @param error the throwable to unwrap
     * @return the root cause message
     */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * Handles one line of input from a caller whose uuid is not known.
     *
     * @param value          the raw input command
     * @param playerUsername the invoking player, empty for console
     * @return the lines to show the caller
     * @throws Exception if a command handler fails in a way it cannot report
     */
    public String[] Input(String value, String playerUsername) throws Exception {
        return Input(value, playerUsername, "");
    }

    /**
     * Wraps a single message as a reply.
     *
     * @param message the message
     * @return the message as a one-line reply
     */
    private String[] singleLine(String message) {
        return new String[]{message};
    }

    /**
     * Runs one pass of the command queue: fetch what is due and queue it for
     * delivery on the host's main thread.
     *
     * @return how many seconds to wait before the next check
     * @throws ExecutionException   if an API call failed
     * @throws InterruptedException if the calling thread was interrupted
     */
    public int CheckCommandsDue() throws ExecutionException, InterruptedException {
        // Without a way to run commands there is nothing this check can achieve,
        // and queueing tasks anyway would leave them failing forever inside
        // TebexTask (which swallows the throw), so the commands would never be
        // marked executed and never be deleted. Refuse early and back off.
        if (serverCommand == null) {
            TXE.Log().Error("Cannot deliver commands: no ServerCommand hook is installed. "
                    + "Call Plugin().HookServerCommand(...) during startup.", null);
            return NO_HOOK_BACKOFF_SECONDS;
        }

        DuePlayersResponse duePlayers = txe.PluginApi().getDuePlayers(key).get();

        if (duePlayers.isExecuteOffline()) { // execute offline commands due immediately
            OfflineCommandsResponse offlineCommands = txe.PluginApi().getOfflineCommands(key).get();
            for (QueuedCommand offlineCommand : offlineCommands.getCommands()) {
                queueCommand(offlineCommand);
            }
        }

        // now check all players for their commands due and execute them
        for (QueuedPlayer player : duePlayers.getPlayers()) {
            List<QueuedCommand> onlineCommandsForPlayer = txe.PluginApi().getOnlineCommands(key, player).get();
            for (QueuedCommand dueCommand : onlineCommandsForPlayer) {
                queueCommand(dueCommand);
            }
        }

        return duePlayers.getNextCheck();
    }

    /**
     * Queues one deliverable for the host's main thread, if it should be
     * delivered at all.
     *
     * <p>Shared by the scheduled queue check and {@code /tebex redeem} so both
     * apply the same rules: a command already executed is not repeated (TBX_036),
     * an offline player or one with no room for the item is skipped, and the task
     * carries the command's delay (TBX_032) and its idempotency key.
     *
     * @param command the command to deliver
     * @return {@code true} if the command was queued
     */
    private boolean queueCommand(QueuedCommand command) {
        if (executedCommands.containsKey(command.getId())) {
            return false; // do not re-queue commands already marked executed
        }

        QueuedPlayer player = command.getPlayer();
        if (player != null && command.isOnline()) {
            // Both of these checks must be able to refuse: without them an online
            // command was delivered to an offline player, and an item was given to
            // a player with no room to hold it.
            //
            // With no PlayerActions hook neither check is possible. Both then
            // pass rather than block: the API has already decided these players
            // are due, and refusing everything would mean a platform that hooks
            // only ServerCommand never delivers anything at all.
            if (playerActions != null && !playerActions.IsOnline(player.getName())) {
                TXE.Log().Debug(player.getName() + " has commands due, but is not online. Skipping.");
                return false;
            }

            int needSlots = command.getRequiredSlots();
            if (needSlots > 0 && playerActions != null) {
                int hasSlots = playerActions.GetNumInventorySlotsAvailable(player.getName());
                if (hasSlots < needSlots) {
                    TXE.Log().Debug(player.getName() + " has command requiring " + needSlots
                            + " inventory slots, but has only " + hasSlots + ". Skipping.");
                    return false;
                }
            }
        }

        // The idempotency key is what stops identical tasks accumulating when the
        // host is not draining the queue: a command's id only reaches
        // executedCommands once its task body runs, so every check in the meantime
        // would otherwise queue it again and the player would receive it once per
        // elapsed cycle. The queue owns that check, not this method.
        txe.queueMainThreadTask(new TebexTask(calculateDueAt(command), commandKey(command), () -> {
            serverCommand.Execute(applyPlayerTags(command.getCommand(), command.getPlayer()));
            executedCommands.put(command.getId(), command);
        }));
        return true;
    }

    /**
     * Installs the hook used to inspect and message players.
     *
     * @param actions the hook, or {@code null} to remove it
     */
    public void HookPlayerActions(PlayerActions actions) {
        this.playerActions = actions;
    }

    /**
     * Installs the hook used to run commands on the host.
     *
     * @param command the hook, or {@code null} to remove it
     */
    public void HookServerCommand(ServerCommand command) {
        this.serverCommand = command;
    }

    /**
     * Installs the hook used to read and write the integration's configuration.
     *
     * @param config the hook, or {@code null} to remove it
     */
    public void HookConfig(Configuration config) {
        this.config = config;
    }

    /**
     * Returns the installed configuration hook.
     *
     * @return the configuration hook, or {@code null} if none is installed
     */
    public Configuration Config() {
        return config;
    }

    /**
     * Records that a player joined, for reporting to Tebex (TBX_033).
     *
     * @param username the player's name
     * @param uuid     the player's uuid
     * @param ip       the address they connected from
     */
    public void Join(String username, String uuid, String ip) {
        enqueueServerEvent(new ServerEvent(uuid, username, ip, ServerEvent.Type.JOIN));
    }

    /**
     * Records that a player left, for reporting to Tebex (TBX_034).
     *
     * @param username the player's name
     * @param uuid     the player's uuid
     * @param ip       the address they connected from
     */
    public void Leave(String username, String uuid, String ip) {
        enqueueServerEvent(new ServerEvent(uuid, username, ip, ServerEvent.Type.LEAVE));
    }

    /**
     * Adds a player event to the outbound queue unless it is already full.
     *
     * <p>The cap matters here and not only on the requeue path: if the events
     * endpoint is failing, the requeue puts a capped batch back but every new
     * join and leave would otherwise keep growing the queue past that cap. A
     * busy server would then accumulate events indefinitely — the exact failure
     * the cap exists to prevent.
     *
     * @param event the event to enqueue
     */
    private void enqueueServerEvent(ServerEvent event) {
        if (serverEvents.size() >= TXE.MAX_QUEUED_EVENTS) {
            TXE.Log().Warn("Dropping a player event: the outbound queue is full at "
                    + TXE.MAX_QUEUED_EVENTS + " events, which means they are not reaching Tebex.");
            return;
        }
        serverEvents.add(event);
    }

    /**
     * Fills in the player tags a store wrote into a command, from the player the
     * queue returned alongside it (TBX_063).
     *
     * <p>{@code {id}} and {@code {uuid}} become {@link #resolvePlayerId}, and
     * {@code {name}} and {@code {username}} become the username. Matching is
     * case-insensitive because store owners type these by hand. A tag whose value
     * is unknown — and every tag the host resolves for itself — is left exactly as
     * it was: an untouched tag is visible in the console and in the store's
     * command log, whereas a command run against an empty target looks like it
     * worked.
     *
     * @param command the command line as Tebex queued it
     * @param player  the player it was queued for, {@code null} for a command
     *                that belongs to no player
     * @return the command with its player tags resolved
     */
    private static String applyPlayerTags(String command, QueuedPlayer player) {
        if (command == null || player == null || command.indexOf('{') < 0) {
            return command;
        }

        Matcher tag = PLAYER_TAG.matcher(command);
        // StringBuffer rather than StringBuilder: appendReplacement takes only
        // the former on Java 8, which this module targets (CODE_006).
        StringBuffer out = new StringBuffer();
        while (tag.find()) {
            String name = tag.group(1).toLowerCase();
            String value = name.equals("id") || name.equals("uuid")
                    ? resolvePlayerId(player)
                    : trimToNull(player.getName());
            tag.appendReplacement(out, Matcher.quoteReplacement(value == null ? tag.group() : value));
        }
        tag.appendTail(out);
        return out.toString();
    }

    /**
     * Returns how a command should identify a player: their UUID, or their XUID if
     * Tebex knows them only by that, or failing both their username.
     *
     * <p>The XUID step is the one that is easy to miss, and getting it wrong is
     * silent. A Bedrock player connected through Geyser has no Minecraft UUID, so
     * the field is empty and an integration that reads only that field falls
     * straight to the username — which is not what the store's command log
     * records, and not what a permissions plugin keyed on the XUID will match.
     * Tebex sends the XUID for exactly these players, in the same payload as the
     * command.
     *
     * @param player the player the command was queued for
     * @return the identifier, or {@code null} if the payload carried none
     */
    private static String resolvePlayerId(QueuedPlayer player) {
        String uuid = trimToNull(player.getUuid());
        if (uuid != null) {
            return canonicalUuid(uuid);
        }

        String xuid = trimToNull(player.getXuid());
        return xuid != null ? xuid : trimToNull(player.getName());
    }

    /**
     * Returns a UUID in the dashed form commands are written against.
     *
     * <p>The API sends Java Edition UUIDs without dashes, while every integration
     * that substituted this tag before the SDK did read it off the host's player
     * object, which is dashed. Stores have their commands configured against that
     * form, so the dashes are restored here rather than quietly changing what
     * every existing {@code {uuid}} expands to. Anything that is not a bare
     * 32-character hex id — an XUID, or an already-dashed UUID — is passed
     * through untouched.
     *
     * @param value the identifier as the API sent it
     * @return the identifier in its canonical form
     */
    private static String canonicalUuid(String value) {
        if (!UNDASHED_UUID.matcher(value).matches()) {
            return value;
        }
        return value.substring(0, 8) + '-' + value.substring(8, 12) + '-' + value.substring(12, 16)
                + '-' + value.substring(16, 20) + '-' + value.substring(20);
    }

    /**
     * Treats a blank field as an absent one, because the API sends an empty string
     * as readily as a JSON null for an id a store does not hold.
     *
     * @param value the field to check
     * @return the trimmed value, or {@code null} if it held nothing
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Returns the idempotency key identifying a command's delivery task.
     *
     * <p>Keyed on the Tebex command id, which is the same identity used to
     * acknowledge the command back to the API, so a command can be pending in the
     * queue exactly once (TBX_036).
     *
     * @param command the command about to be queued
     * @return the key for its delivery task
     */
    private static String commandKey(QueuedCommand command) {
        return "command:" + command.getId();
    }

    /**
     * Returns the engine time at which a command may be delivered, honouring the
     * delay the store attached to it (TBX_032, TBX_035).
     *
     * @param command the command about to be queued
     * @return the due time in engine epoch seconds
     */
    private long calculateDueAt(QueuedCommand command) {
        long dueAt = txe.now();
        if (command.getDelay() > 0) {
            dueAt += command.getDelay(); // delay is in seconds, add to the dueAt
        }
        return dueAt;
    }
}
