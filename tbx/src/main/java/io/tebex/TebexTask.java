package io.tebex;

import java.time.Instant;

/**
 * A unit of deferred work tracked by {@link TXE}, run on the host's main thread.
 *
 * <p>A task records the time at which it becomes due, so integrations can delay
 * delivery (for example a command with a configured delay). Running a task never
 * propagates an exception: the outcome is captured in {@link #getResult()} so a
 * single failing task cannot break the scheduler loop.
 *
 * <p>A task may carry an <em>idempotency key</em>. The queue refuses to hold two
 * pending tasks with the same key, which is what stops a repeatedly re-queued
 * deliverable from being executed more than once — see
 * {@link TXE#queueMainThreadTask(TebexTask)}.
 *
 * <p>There is no longer a main-thread flag: every task is a main-thread task.
 * The flag existed but nothing ever passed {@code false}, and a task carrying it
 * would have sat in the queue forever because nothing else drained it.
 */
public class TebexTask {

    private final long dueAt;
    private final String idempotencyKey;
    private final Runnable runnable;
    private boolean didRun;
    private String result;

    /**
     * Creates a task that may be queued more than once.
     *
     * @param dueAt    the earliest time the task may run in epoch seconds UTC
     * @param runnable the work to perform
     */
    public TebexTask(long dueAt, Runnable runnable) {
        this(dueAt, null, runnable);
    }

    /**
     * Creates a task identified by an idempotency key.
     *
     * @param dueAt          the earliest time the task may run in epoch seconds UTC
     * @param idempotencyKey identifies the work, so the queue can reject a
     *                       duplicate while this one is still pending;
     *                       {@code null} to allow duplicates
     * @param runnable       the work to perform
     */
    public TebexTask(long dueAt, String idempotencyKey, Runnable runnable) {
        this.dueAt = dueAt;
        this.idempotencyKey = idempotencyKey;
        this.runnable = runnable;
    }

    /**
     * Returns the key identifying this task's work.
     *
     * @return the idempotency key, or {@code null} if duplicates are allowed
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns whether the task is due to run at the current time.
     *
     * @return {@code true} if the due time has passed
     */
    public boolean isDue() {
        return isDue(Instant.now().getEpochSecond());
    }

    /**
     * Returns whether the task is due at the given time.
     *
     * <p>The engine passes its own clock here rather than letting the task read
     * the wall clock, so that fast-forwarding the engine (TASK_000) brings queued
     * deliverables forward with everything else.
     *
     * @param nowEpochSeconds the current time in epoch seconds UTC
     * @return {@code true} if the due time has passed
     */
    public boolean isDue(long nowEpochSeconds) {
        return nowEpochSeconds >= dueAt;
    }

    /**
     * Returns whether the task has been executed.
     *
     * @return {@code true} once {@link #run()} has completed
     */
    public boolean didRun() {
        return didRun;
    }

    /**
     * Returns the outcome of running the task: {@code "OK"} on success, or an
     * {@code "ERROR: ..."} description if the work threw. {@code null} until the
     * task has run.
     *
     * @return the recorded result, or {@code null} if not yet run
     */
    public String getResult() {
        return result;
    }

    /**
     * Runs the task, capturing the outcome. Any exception thrown by the work is
     * caught and recorded rather than propagated, so the scheduler that invokes
     * tasks cannot be broken by a single failure.
     */
    public void run() {
        try {
            if (runnable != null) {
                runnable.run();
            }
            this.result = "OK";
        } catch (Exception err) {
            this.result = "ERROR: " + err.getMessage();
        } finally {
            this.didRun = true;
        }
    }
}
