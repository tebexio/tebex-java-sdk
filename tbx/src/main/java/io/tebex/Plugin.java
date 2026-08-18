package io.tebex;

import io.tebex.hooks.*;
import io.tebex.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class Plugin {

    /**
     * How long {@code /tebex secret} waits for the API before giving up. Bounded
     * because the command runs on the caller's thread, which on most platforms is
     * the server main thread — an unbounded wait there stalls the whole server.
     */
    private static final int SECRET_VALIDATION_TIMEOUT_SECONDS = 15;

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
     * Placeholder entry point for feeding console/command input into the engine.
     *
     * @param rawInput the raw input command, ex. "/tebex help"
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
                // A reload can bring in a different key; adopt it rather than
                // carrying on with the old one.
                String reloadedKey = config.Get("secret-key");
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
            case "checkout":
            case "sendlink":
            case "ban":
            case "goals":
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
     *
     * @param newKey the candidate secret key
     * @return the lines to report back to the caller
     */
    private List<String> setSecretKey(String newKey) {
        List<String> out = new ArrayList<>();
        try {
            ServerInformation info = txe.PluginApi()
                    .getServerInformation(newKey)
                    .get(SECRET_VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            applyCredentials(newKey, info.getAccount(), info.getServer());

            if (config == null) {
                out.add("Secret key set, but no configuration hook is installed - "
                        + "it will not persist across a restart.");
            } else {
                config.Set("secret-key", newKey);
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
        return new String[] {
            "Tebex commands:",
            "  /tebex info        - show the connected store",
            "  /tebex secret <key> - set the store secret key",
            "  /tebex forcecheck  - run the command queue check now",
            "  /tebex reload      - reload the configuration file",
            "  /tebex debug <true|false> - toggle debug logging",
            "  /tebex help        - show this message",
        };
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

    public String[] Input(String value, String playerUsername) throws Exception {
        return Input(value, playerUsername, "");
    }

    private String[] singleLine(String message) {
        return new String[]{message};
    }

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
                if (executedCommands.containsKey(offlineCommand.getId())) {
                    continue; // do not re-queue commands already marked executed
                }
                txe.queueMainThreadTask(new TebexTask(calculateDueAt(offlineCommand),
                        commandKey(offlineCommand), () -> {
                            serverCommand.Execute(offlineCommand.getCommand());
                            executedCommands.put(offlineCommand.getId(), offlineCommand);
                        }));
            }
        }

        // now check all players for their commands due and execute them
        for (QueuedPlayer player : duePlayers.getPlayers()) {
            List<QueuedCommand> onlineCommandsForPlayer = txe.PluginApi().getOnlineCommands(key, player).get();
            for (QueuedCommand dueCommand : onlineCommandsForPlayer) {
                if (executedCommands.containsKey(dueCommand.getId())) {
                    continue; // do not re-queue commands already marked executed
                }

                // Both of these must `continue`: without it the command was queued
                // anyway, so an online command was delivered to an offline player
                // and an item was given to a player with no room to hold it.
                //
                // With no PlayerActions hook neither check is possible. Both then
                // pass rather than block: the API has already decided these players
                // are due, and refusing everything would mean a platform that hooks
                // only ServerCommand never delivers anything at all.
                if (playerActions != null && !playerActions.IsOnline(player.getName())) {
                    TXE.Log().Debug(player.getName() + " has commands due, but is not online. Skipping.");
                    continue;
                }

                int needSlots = dueCommand.getRequiredSlots();
                if (needSlots > 0 && playerActions != null) {
                    int hasSlots = playerActions.GetNumInventorySlotsAvailable(player.getName());
                    if (hasSlots < needSlots) {
                        TXE.Log().Debug(player.getName() + " has command requiring " + needSlots
                                + " inventory slots, but has only " + hasSlots + ". Skipping.");
                        continue;
                    }
                }

                // online and slots check passed, we can execute now or after the delay
                //
                // The idempotency key is what stops identical tasks accumulating
                // when the host is not draining the queue: a command's id only
                // reaches executedCommands once its task body runs, so every check
                // in the meantime would otherwise queue it again and the player
                // would receive it once per elapsed cycle. The queue owns that
                // check now, not this method.
                txe.queueMainThreadTask(new TebexTask(calculateDueAt(dueCommand),
                        commandKey(dueCommand), () -> {
                            serverCommand.Execute(dueCommand.getCommand());
                            executedCommands.put(dueCommand.getId(), dueCommand);
                        }));
            }
        }

        return duePlayers.getNextCheck();
    }

    public void HookPlayerActions(PlayerActions actions) {
        this.playerActions = actions;
    }
    public void HookServerCommand(ServerCommand command) {
        this.serverCommand = command;
    }

    public void HookConfig(Configuration config) {
        this.config = config;
    }

    public Configuration Config() {
        return config;
    }

    public void Join(String username, String uuid, String ip) {
        enqueueServerEvent(new ServerEvent(uuid, username, ip, ServerEvent.Type.JOIN));
    }

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

    private long calculateDueAt(QueuedCommand command) {
        long dueAt = Instant.now().getEpochSecond();
        if (command.getDelay() > 0) {
            dueAt += command.getDelay(); // delay is in seconds, add to the dueAt
        }
        return dueAt;
    }
}
