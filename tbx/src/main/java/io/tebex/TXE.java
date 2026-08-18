package io.tebex;

import io.tebex.headless.invoker.ApiException;
import io.tebex.headless.model.CategoryResponse;
import io.tebex.http.HeadlessApi;
import io.tebex.http.PluginApi;
import io.tebex.model.PluginEvent;
import io.tebex.model.QueuedCommand;
import io.tebex.model.ServerEvent;
import io.tebex.model.ServerInformation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Tebex Engine: the entry point and interface every integration uses to
 * drive activity on the Tebex system.
 *
 * <p>TXE owns the secret key, the plugin API client, a queue of work that must
 * run on the host's main thread, and a background worker thread. Mistakes in the
 * SDK or transient API failures must never crash the host, so all engine work is
 * wrapped to capture, log, and recover from unexpected exceptions.
 *
 * <p>The worker ticks once a second, and each piece of periodic work decides for
 * itself whether it is due: the command queue check (TASK_001), the player
 * join/leave flush (TASK_002), the plugin-log flush (TASK_003) and the store
 * catalogue refresh (TASK_004) all run on their own cadence off the engine
 * clock. That clock can be fast-forwarded in a test (TASK_000) so a cadence
 * measured in minutes can be verified in milliseconds.
 *
 * <p>This class is a process-wide singleton obtained via {@link #get()}.
 */
public class TXE {
    /** Whether debug logging is enabled process-wide (TBX_026, TBX_027, CFG_003). */
    public static boolean DEBUG_MODE = false;

    /**
     * The most events either outbound queue may hold. Enforced on both paths that
     * can grow a queue — a requeue after a failed send, and a fresh
     * {@code Join}/{@code Leave} — because bounding only one of them still lets a
     * busy server with a failing endpoint grow without limit.
     */
    static final int MAX_QUEUED_EVENTS = 1000;

    private static TXE txe;

    // Initialised eagerly: TXE.Log() and every log.* call below dereference this,
    // so leaving it null makes the first log line an NPE — including the one in
    // StartPlugin's own failure handler, which would then die silently.
    static Log log = new Log();

    /**
     * How long to wait before the next command queue check when the API does not
     * say otherwise (TASK_001).
     *
     * <p>The API's {@code next_check} is authoritative when it sends one; this is
     * the fallback for a check that failed, or that came back with no usable
     * value, so a broken endpoint is polled on a sane cadence rather than every
     * tick.
     */
    static final long QUEUE_CHECK_SECONDS = 120L;

    /** How often queued player join/leave events are sent, in seconds (TASK_002). */
    static final long PLAYER_EVENT_SECONDS = 60L;

    /** How often queued plugin log events are sent, in seconds (TASK_003). */
    static final long LOG_EVENT_SECONDS = 120L;

    /** How often the store catalogue is refreshed, in seconds (TASK_004). */
    static final long STORE_REFRESH_SECONDS = 300L;

    private Thread main;
    private volatile boolean stop;
    private final Queue<TebexTask> tasks = new ConcurrentLinkedQueue<>();

    /** Idempotency keys of the tasks currently pending in {@link #tasks}. */
    private final Set<String> queuedKeys = ConcurrentHashMap.newKeySet();

    // `this` is published to Plugin from a field initialiser, which is safe only
    // because Plugin's constructor does nothing but store the reference. Do not
    // add work to that constructor.
    private final Plugin plugin = new Plugin(this);
    private PluginApi pluginApi = new PluginApi();
    private HeadlessApi headlessApi = new HeadlessApi();

    /**
     * Seconds added to the wall clock by {@link #fastForward(long)} (TASK_000).
     *
     * <p>Every deadline in the engine is expressed against {@link #now()} rather
     * than {@link Instant#now()}, so moving this moves all of them together —
     * including the due times of queued deliverables.
     */
    private final AtomicLong clockOffsetSeconds = new AtomicLong();

    /** The sink this engine installs on {@link #log} while it is running. */
    private final LogSink logSink = plugin::recordLogEvent;

    // epoch timestamp of the allowed next check time
    private long nextCheck = 0L;

    // epoch timestamp of when the queued player events may next be sent
    private long nextPlayerEventFlush = 0L;

    // epoch timestamp of when the queued plugin log events may next be sent
    private long nextLogEventFlush = 0L;

    // epoch timestamp of when we should refresh the store next
    private long nextRefreshStore = 0L;

    /**
     * integrations obtain the engine through {@link #get()};
     * tests construct isolated instances.
     */
    TXE() {}

    /**
     * Returns the shared engine instance, creating it on first use.
     *
     * @return the singleton engine
     */
    public static TXE get() {
        if (txe == null) {
            txe = new TXE();
        }
        return txe;
    }

    /**
     * Returns the SDK's logging harness, shared by every engine in the process.
     *
     * @return the logger facade
     */
    public static Log Log() {
        return log;
    }

    /**
     * Returns whether the engine's background worker is alive.
     *
     * @return {@code true} while the engine is running
     */
    public boolean IsRunning() {
        return main != null && main.isAlive();
    }

    /**
     * Returns the Headless API access point for storefront reads.
     *
     * @return the headless client
     */
    public HeadlessApi HeadlessApi() {
        return headlessApi;
    }

    /**
     * Returns the plugin API client this engine talks to Tebex through.
     *
     * @return the plugin API client
     */
    public PluginApi PluginApi() {
        return pluginApi;
    }

    /**
     * Returns the plugin this engine drives: the command dispatcher, the platform
     * hooks and the outbound event queues.
     *
     * @return the engine's plugin
     */
    public Plugin Plugin() {
        return plugin;
    }

    /**
     * Starts the engine with the secret key held in the integration's
     * configuration (TBX_004).
     *
     * <p>Requires a {@link io.tebex.hooks.Configuration} hook: without one there
     * is nowhere to read a key from, which is reported rather than treated as an
     * empty key. The key is then authenticated exactly as
     * {@link #StartPlugin(String)} does, so a key that the API rejects leaves the
     * engine stopped rather than half-connected.
     */
    public void StartPlugin() {
        String configured = plugin.configuredSecretKey();
        if (configured == null || configured.trim().isEmpty()) {
            log.Error("No secret key is configured. Set one with '/tebex secret <key>', "
                    + "or install a configuration hook that provides '"
                    + Plugin.CONFIG_SECRET_KEY + "'.", null);
            return;
        }
        StartPlugin(configured.trim());
    }

    /**
     * Starts the engine with the given plugin secret key.
     *
     * <p>The first thing the engine does is authenticate by fetching the store
     * information from {@code /information} and logging it. This runs on a
     * background thread and never throws: any failure is logged and the host
     * continues unaffected.
     *
     * @param key the store secret key
     */
    public void StartPlugin(String key) {
        if (main != null && main.isAlive()) {
            return;
        }
        // Cleared here, not only in the constructor: Stop() leaves it set, so an
        // engine that is started again would otherwise exit its loop immediately.
        stop = false;
        plugin.applyConfiguredDebugMode();
        log.SetSink(logSink);
        main = new Thread(() -> run(key), "tebex-main");
        main.start();
    }

    /**
     * The engine worker: authenticate once, then tick until asked to stop.
     *
     * @param key the store secret key to authenticate with
     */
    private void run(String key) {
        if (!authenticate(key)) {
            return;
        }

        while (!stop) {
            if (!tick()) {
                break; // interrupted: a shutdown, not a failure
            }
            try {
                Thread.sleep(1000L); // engine ticks each second
            } catch (InterruptedException interrupted) {
                // Stop() interrupts this thread; that is a normal shutdown,
                // not a failure. Restore the flag and leave the loop.
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Authenticates the secret key and adopts the store it resolves to.
     *
     * @param key the store secret key
     * @return {@code true} if the engine may proceed to its tick loop
     */
    private boolean authenticate(String key) {
        try {
            ServerInformation information = pluginApi.getServerInformation(key).join();
            ServerInformation.Account account = information.getAccount();

            String expectedGameType = plugin.ExpectedGameType();
            if (!plugin.gameTypeMatches(account)) {
                // TBX_006: an integration that only knows how to run one game's
                // commands must not be pointed at another game's store. Refusing
                // here is the difference between a clear message at startup and
                // deliveries that silently do nothing.
                log.Error("The secret key belongs to a '" + account.getGameType()
                        + "' store, but this integration is for '" + expectedGameType
                        + "'. The key has not been adopted.", null);
                return false;
            }

            log.Info("Connected to store '" + account.getName() + "' (" + account.getDomain() + ") as game type '"
                    + account.getGameType() + "'.");
            plugin.applyCredentials(key, account, information.getServer());
            headlessApi.setToken(information.getPublicToken());
            return true;
        } catch (Exception err) {
            log.Error("Failed to connect to Tebex: " + rootMessage(err), err);
            return false;
        }
    }

    /**
     * Runs one engine tick: every periodic job, each of which decides whether it
     * is due and none of which may stop the loop by failing (TASK_001–TASK_004).
     *
     * @return {@code false} if the tick was interrupted and the engine should
     *         shut down, {@code true} to keep ticking
     */
    private boolean tick() {
        return step(this::emptyLogEvents, "emptying log events")
                && step(this::emptyPlayerEvents, "emptying server events")
                && step(this::checkDuePlayers, "checking due players")
                && step(this::refreshStore, "refreshing store")
                && step(this::deleteCompletedCommands, "deleting completed commands");
    }

    /**
     * Runs one piece of periodic work, absorbing whatever it throws.
     *
     * <p>A failure is logged and the loop continues, which is what keeps a broken
     * endpoint from stopping every other job (TASK_001–TASK_004). Interruption is
     * the one exception: it is how {@link #Stop()} asks the worker to finish, so
     * it is reported back rather than logged as an error (TBX_001).
     *
     * @param work        the work to run
     * @param description what the work was doing, for the log message
     * @return {@code true} to keep ticking, {@code false} if shutting down
     */
    private boolean step(EngineStep work, String description) {
        try {
            work.run();
            return true;
        } catch (Throwable failure) {
            if (stop || isShutdown(failure)) {
                Thread.currentThread().interrupt();
                return false;
            }
            log.Error("unexpected error " + description, failure);
            return true;
        }
    }

    /**
     * Returns whether a failure is the engine being asked to shut down rather
     * than something going wrong.
     *
     * @param failure the throwable to inspect
     * @return {@code true} if the failure is an interruption
     */
    private static boolean isShutdown(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break; // a self-referencing cause would loop forever
            }
        }
        return false;
    }

    /**
     * Stops the engine, interrupting the background worker.
     */
    public void Stop() {
        log.ClearSink(logSink);
        if (main != null) {
            stop = true;
            main.interrupt();
            main = null;
        }
    }

    /**
     * Returns the engine's current time in epoch seconds, including any offset
     * applied by {@link #fastForward(long)}.
     *
     * @return the engine clock, in epoch seconds
     */
    long now() {
        return Instant.now().getEpochSecond() + clockOffsetSeconds.get();
    }

    /**
     * Moves the engine clock forward (TASK_000).
     *
     * <p>Test seam. Every engine deadline — the queue check, both event flushes,
     * the catalogue refresh and the due time of each queued deliverable — is read
     * from {@link #now()}, so this brings all of them forward together and lets a
     * cadence measured in minutes be verified without waiting for one.
     *
     * @param seconds how far to advance the clock
     */
    void fastForward(long seconds) {
        clockOffsetSeconds.addAndGet(seconds);
    }

    /**
     * Queues a task to be executed the next time the host runs
     * {@link #RunNextMainThreadTask()} on its main thread.
     *
     * @param task the task to queue
     */
    public void queueMainThreadTask(TebexTask task) {
        if (task == null) {
            return;
        }

        String key = task.getIdempotencyKey();
        if (key != null && !queuedKeys.add(key)) {
            // An identical task is already pending. Without this, a deliverable
            // that is queued but not yet due is re-queued by every command check
            // (its id only reaches executedCommands once the task body runs), so
            // when the delay finally elapses the player receives it once per
            // elapsed check cycle.
            return;
        }
        tasks.add(task);
    }

    /**
     * Runs the first queued task that is due. Intended to be called by the host on
     * its main thread (for example once per server tick). Running a task never
     * throws — the task captures its own outcome — so a failing task cannot break
     * the host's tick loop.
     *
     * <p>Scans for the first <em>due</em> task rather than only inspecting the
     * head. A queue is FIFO, and deliverables can carry a delay of minutes or
     * hours, so stopping at a not-yet-due head would let one delayed command block
     * every task queued behind it — including instant purchases.
     */
    public void RunNextMainThreadTask() {
        long now = now();
        Iterator<TebexTask> pending = tasks.iterator();
        while (pending.hasNext()) {
            TebexTask task = pending.next();
            if (!task.isDue(now)) {
                continue;
            }

            pending.remove();
            try {
                task.run();
            } finally {
                // Released even when the body threw, so the deliverable can be
                // re-queued and retried by the next command check.
                String key = task.getIdempotencyKey();
                if (key != null) {
                    queuedKeys.remove(key);
                }
            }

            // A failed deliverable must not be dropped silently (TBX_001).
            if (task.getResult() != null && task.getResult().startsWith("ERROR")) {
                log.Warn("A queued task failed: " + task.getResult());
            }
            return;
        }
    }

    /**
     * Returns the message of the deepest cause of a throwable, for a concise
     * one-line log entry.
     *
     * @param error the throwable to unwrap
     * @return the root cause message, or the root cause's class name if none
     */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * Replaces the plugin API client. Test seam: lets a test point the engine at
     * a local stub server instead of {@code https://plugin.tebex.io}.
     *
     * @param pluginApi the client the engine should use
     */
    void setPluginApi(PluginApi pluginApi) {
        if (pluginApi != null) {
            this.pluginApi = pluginApi;
        }
    }

    /**
     * Replaces the Headless API client. Test seam, as with
     * {@link #setPluginApi(PluginApi)}.
     *
     * @param headlessApi the client the engine should use
     */
    void setHeadlessApi(HeadlessApi headlessApi) {
        if (headlessApi != null) {
            this.headlessApi = headlessApi;
        }
    }

    /**
     * Sends the queued plugin log events, at most once every
     * {@link #LOG_EVENT_SECONDS} (TASK_003).
     *
     * <p>An empty queue does not advance the deadline, so the first report after
     * an idle period goes out on the next tick rather than waiting out a full
     * interval; it is a burst of reports that the cadence exists to space out.
     */
    private void emptyLogEvents() {
        if (plugin.logEvents.isEmpty()) {
            return;
        }
        if (now() < nextLogEventFlush) {
            return;
        }
        nextLogEventFlush = now() + LOG_EVENT_SECONDS;

        // Drained into a List because sendPluginEvents takes List<PluginEvent> and
        // a ConcurrentLinkedQueue is a Collection, not a List — passing the queue
        // straight through cannot compile. Mirrors emptyPlayerEvents.
        List<PluginEvent> events = drain(plugin.logEvents);
        pluginApi.sendPluginEvents(events).whenComplete((sent, error) ->
                requeueIfFailed(plugin.logEvents, events, sent, error, "plugin log"));
    }

    /**
     * Sends the queued player join/leave events, at most once every
     * {@link #PLAYER_EVENT_SECONDS} (TASK_002).
     */
    private void emptyPlayerEvents() {
        if (plugin.serverEvents.isEmpty()) {
            return;
        }
        if (now() < nextPlayerEventFlush) {
            return;
        }
        nextPlayerEventFlush = now() + PLAYER_EVENT_SECONDS;

        List<ServerEvent> events = drain(plugin.serverEvents);
        pluginApi.sendJoinEvents(plugin.key, events).whenComplete((sent, error) ->
                requeueIfFailed(plugin.serverEvents, events, sent, error, "player"));
    }

    /**
     * Removes every element currently in a queue and returns them in order.
     *
     * @param queue the queue to drain
     * @param <T>   the element type
     * @return the drained elements
     */
    private static <T> List<T> drain(Queue<T> queue) {
        List<T> drained = new ArrayList<>();
        T next;
        while ((next = queue.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    /**
     * Puts a batch back on its queue when the send did not succeed, so a transient
     * API failure does not silently discard events that have already been taken
     * off the queue.
     *
     * <p>Two deliberate limits. Events are appended to the tail, so a requeued
     * batch ends up behind anything enqueued while the request was in flight —
     * harmless, because each event carries its own timestamp. And the queue is
     * capped: a permanently failing endpoint would otherwise reintroduce the
     * unbounded growth this requeue exists to avoid, so the oldest events beyond
     * {@link #MAX_QUEUED_EVENTS} are dropped and the loss is logged rather than
     * hidden.
     *
     * @param queue    the queue to return the events to
     * @param events   the batch that was sent
     * @param sent     the send result, {@code null} if the call failed
     * @param error    the failure, or {@code null} if the call completed
     * @param what     a noun describing the events, for logging
     * @param <T>      the element type
     */
    private static <T> void requeueIfFailed(Queue<T> queue, List<T> events,
                                            Boolean sent, Throwable error, String what) {
        if (error == null && Boolean.TRUE.equals(sent)) {
            return;
        }

        int room = MAX_QUEUED_EVENTS - queue.size();
        int requeued = 0;
        for (T event : events) {
            if (requeued >= room) {
                break;
            }
            queue.add(event);
            requeued++;
        }

        int dropped = events.size() - requeued;
        String reason = error == null ? "the api rejected them" : rootMessage(error);
        if (dropped > 0) {
            log.Warn("Failed to send " + events.size() + " " + what + " events (" + reason
                    + "); requeued " + requeued + " and dropped " + dropped
                    + " because the queue is at its " + MAX_QUEUED_EVENTS + " event cap.");
        } else {
            log.Warn("Failed to send " + requeued + " " + what + " events (" + reason
                    + "); they have been requeued for the next attempt.");
        }
    }

    /**
     * Bypasses the queue-check backoff so the next engine tick runs a check
     * immediately (TBX_010).
     *
     * <p>Only the timer is cleared here; the check itself still runs on the engine
     * thread, which then adopts the {@code next_check} the API returns exactly as
     * a scheduled check would. Doing it this way rather than calling
     * {@code CheckCommandsDue()} inline matters: {@code Input} is invoked from the
     * host's command handler, usually the main thread, and the check performs
     * blocking HTTP — running it there would freeze the server for the duration.
     *
     * @return {@code true} if a check was scheduled, {@code false} if the engine
     *         is not running and so nothing will pick it up
     */
    boolean ForceCheckNow() {
        nextCheck = 0L;
        return IsRunning();
    }

    /**
     * Clears the catalogue-refresh backoff so the next engine tick refreshes.
     *
     * <p>Package-private test seam for forcing a refresh at a point of the test's
     * choosing; {@link #fastForward(long)} is the way to prove the cadence itself.
     */
    void refreshStoreNow() {
        nextRefreshStore = 0L;
    }

    /**
     * Checks the command queue when it is due, and adopts the API's own backoff
     * instruction for the next one (TBX_009, TASK_001).
     *
     * @throws Exception if the check was interrupted, so the tick loop can treat
     *                   it as a shutdown rather than a failure
     */
    private void checkDuePlayers() throws Exception {
        if (now() < nextCheck) {
            return; // not time for the next check
        }

        // set next check timer to the value provided by the api
        try {
            int apiNextCheckSeconds = plugin.CheckCommandsDue();
            // A missing or nonsensical next_check falls back to the standard
            // cadence rather than to zero, which would poll on every tick.
            nextCheck = now() + (apiNextCheckSeconds > 0 ? apiNextCheckSeconds : QUEUE_CHECK_SECONDS);
        } catch (Exception e) {
            if (isShutdown(e)) {
                throw e;
            }
            log.Error("due players check failed, retrying after " + QUEUE_CHECK_SECONDS + " seconds", e);
            nextCheck = now() + QUEUE_CHECK_SECONDS;
        }
    }

    /**
     * Refreshes the cached store catalogue from the Headless API (TASK_004,
     * TBX_061).
     *
     * <p>The token this needs is bound in {@link #StartPlugin(String)} before the
     * tick loop starts, so it is always set by the time this runs.
     */
    private void refreshStore() {
        if (now() < nextRefreshStore) {
            return;
        }

        try {
            CategoryResponse response = headlessApi.Headless.getCategoriesIncludePackages();
            plugin.setCategories(response == null ? null : response.getData());
        } catch (ApiException failed) {
            // Keep the previous catalogue rather than clearing it: a stale listing
            // is more useful than none, and the refresh will be retried.
            log.Warn("Could not refresh the store catalogue: " + rootMessage(failed));
        } finally {
            // Advanced even on failure. Setting it only after a successful fetch
            // would make a broken store re-request on every single tick, and
            // TASK_004 requires the loop keep running regardless.
            nextRefreshStore = now() + STORE_REFRESH_SECONDS;
        }
    }

    /**
     * Acknowledges the commands that have been delivered, so Tebex stops sending
     * them (TBX_036).
     *
     * @throws ExecutionException   if the delete call failed
     * @throws InterruptedException if the engine was interrupted while waiting
     */
    private void deleteCompletedCommands() throws ExecutionException, InterruptedException {
        if (plugin.executedCommands.isEmpty()) {
            return;
        }

        List<Integer> completedCommands = new ArrayList<>();
        for (Map.Entry<Integer, QueuedCommand> entry : plugin.executedCommands.entrySet()) {
            completedCommands.add(entry.getKey());
        }

        CompletableFuture<Boolean> deleteCommandsSuccess = pluginApi.deleteCommands(plugin.key, completedCommands);
        if (!deleteCommandsSuccess.get()) {
            throw new ExecutionException(new Throwable("delete commands call did not succeed"));
        }

        // on success, go through every deleted command and remove it from the list of executed commands
        for (int commandId : completedCommands) {
            plugin.executedCommands.remove(commandId);
        }
    }

    /**
     * One piece of periodic engine work, as run by {@link #step(EngineStep, String)}.
     *
     * <p>Declared to throw {@code Throwable} because that is exactly what the
     * caller absorbs: the point of the wrapper is that no job, however it fails,
     * can stop the engine loop.
     */
    private interface EngineStep {
        /**
         * Performs the work.
         *
         * @throws Throwable if the work failed
         */
        void run() throws Throwable;
    }
}
