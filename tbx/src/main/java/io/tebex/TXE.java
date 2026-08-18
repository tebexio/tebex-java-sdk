package io.tebex;

import io.tebex.hooks.Configuration;
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

/**
 * The Tebex Engine: the entry point and interface every integration uses to
 * drive activity on the Tebex system.
 *
 * <p>TXE owns the secret key, the plugin API client, a queue of work that must
 * run on the host's main thread, and a background worker thread. Mistakes in the
 * SDK or transient API failures must never crash the host, so all engine work is
 * wrapped to capture, log, and recover from unexpected exceptions.
 *
 * <p>This class is a process-wide singleton obtained via {@link #get()}.
 */
public class TXE {
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

    /** How often the store catalogue is refreshed, in seconds (TASK_004). */
    private static final long STORE_REFRESH_SECONDS = 300L;

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

    // epoch timestamp of the allowed next check time
    private long nextCheck = 0L;

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

    public static Log Log() {
        return log;
    }

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

    public PluginApi PluginApi() {
        return pluginApi;
    }

    public Plugin Plugin() {
        return plugin;
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
        main = new Thread(() -> {
            try {
                ServerInformation information = pluginApi.getServerInformation(key).join();
                ServerInformation.Account account = information.getAccount();
                log.Info("Connected to store '" + account.getName() + "' (" + account.getDomain() + ") as game type '"
                        + account.getGameType() + "'.");
                plugin.key = key;
                plugin.account = account;
                plugin.server = information.getServer();
                headlessApi.setToken(information.getPublicToken());
                while (!stop) {
                    try {
                        emptyLogEvents();
                    } catch (Exception e) {
                        log.Error("unexpected error emptying log events", e);
                    }
                    try {
                        emptyPlayerEvents();
                    } catch (Exception e) {
                        log.Error("unexpected error emptying server events", e);
                    }
                    try {
                        checkDuePlayers();
                    } catch (Exception e) {
                        log.Error("unexpected error checking due players", e);
                    }
                    try {
                        refreshStore();
                    } catch (Throwable e) {
                        log.Error("unexpected error refreshing store", e);
                    }
                    try {
                        deleteCompletedCommands();
                    } catch (Throwable e) {
                        log.Error("unexpected error deleting completed commands", e);
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
            } catch (Exception err) {
                log.Error("Failed to connect to Tebex: " + rootMessage(err), err);
            }
        }, "tebex-main");
        main.start();
    }

    /**
     * Stops the engine, interrupting the background worker.
     */
    public void Stop() {
        if (main != null) {
            stop = true;
            main.interrupt();
            main = null;
        }
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
        Iterator<TebexTask> pending = tasks.iterator();
        while (pending.hasNext()) {
            TebexTask task = pending.next();
            if (!task.isDue()) {
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
    void setHeadlessApi(HeadlessApi headlessApi) {
        if (headlessApi != null) {
            this.headlessApi = headlessApi;
        }
    }

    private void emptyLogEvents() {
        if (plugin.logEvents.isEmpty()) {
            return;
        }

        // Drained into a List because sendPluginEvents takes List<PluginEvent> and
        // a ConcurrentLinkedQueue is a Collection, not a List — passing the queue
        // straight through cannot compile. Mirrors emptyPlayerEvents.
        List<PluginEvent> events = drain(plugin.logEvents);
        pluginApi.sendPluginEvents(events).whenComplete((sent, error) ->
                requeueIfFailed(plugin.logEvents, events, sent, error, "plugin log"));
    }

    private void emptyPlayerEvents() {
        if (plugin.serverEvents.isEmpty()) {
            return;
        }

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
     * <p>Package-private test seam, and the only way to exercise a second refresh
     * without waiting out {@link #STORE_REFRESH_SECONDS} — there is no injectable
     * clock in this module yet.
     */
    void refreshStoreNow() {
        nextRefreshStore = 0L;
    }

    private void checkDuePlayers() {
        if (Instant.now().getEpochSecond() < nextCheck) {
            return; // not time for the next check
        }

        // set next check timer to the value provided by the api
        int apiNextCheckSeconds = 0;
        try {
            apiNextCheckSeconds = plugin.CheckCommandsDue();
            nextCheck = Instant.now().getEpochSecond() + apiNextCheckSeconds;
        } catch (Exception e) {
            log.Error("due players check failed, retrying after 120 seconds", e);
            nextCheck = Instant.now().getEpochSecond() + 120;
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
        if (Instant.now().getEpochSecond() < nextRefreshStore) {
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
            nextRefreshStore = Instant.now().getEpochSecond() + STORE_REFRESH_SECONDS;
        }
    }

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
}
