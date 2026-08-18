package io.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tebex.requirements.Requirement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for the main-thread task queue: that a delayed task does not
 * block the ones behind it (TBX_032, TBX_035), and that a deliverable queued
 * repeatedly is executed once (TBX_036).
 *
 * <p>Written against the queue directly rather than through a running engine, so
 * they assert scheduling behaviour without depending on tick timing.
 */
class TaskQueueTest {

    /**
     * Returns an epoch-second due time offset from now.
     *
     * @param offsetSeconds seconds to add to the current time
     * @return the due time in epoch seconds
     */
    private static long dueIn(long offsetSeconds) {
        return Instant.now().getEpochSecond() + offsetSeconds;
    }

    @Test
    @Requirement("TBX_032")
    @Requirement("TBX_035")
    @DisplayName("TBX_032/035: a delayed task does not block tasks queued behind it")
    void delayedTaskDoesNotBlockTheQueue() {
        TXE txe = new TXE();
        List<String> ran = new CopyOnWriteArrayList<>();

        // The delayed task is queued FIRST, so it sits at the head. Previously
        // RunNextMainThreadTask only inspected the head and gave up when it was
        // not due, so everything behind it stalled for the whole delay.
        txe.queueMainThreadTask(new TebexTask(dueIn(3600), () -> ran.add("delayed")));
        txe.queueMainThreadTask(new TebexTask(dueIn(0), () -> ran.add("instant-a")));
        txe.queueMainThreadTask(new TebexTask(dueIn(0), () -> ran.add("instant-b")));

        txe.RunNextMainThreadTask();
        txe.RunNextMainThreadTask();

        assertEquals(java.util.Arrays.asList("instant-a", "instant-b"), ran,
                "both due tasks must run even though a not-yet-due task is ahead of them");
    }

    @Test
    @Requirement("TBX_032")
    @Requirement("TBX_035")
    @DisplayName("TBX_032/035: a delayed task still runs once its delay has elapsed")
    void delayedTaskRunsWhenDue() {
        TXE txe = new TXE();
        AtomicInteger runs = new AtomicInteger();

        // Due one second in the past: the delay is honoured, not ignored.
        txe.queueMainThreadTask(new TebexTask(dueIn(-1), runs::incrementAndGet));
        txe.RunNextMainThreadTask();
        assertEquals(1, runs.get(), "a task whose delay has elapsed must run");

        txe.queueMainThreadTask(new TebexTask(dueIn(3600), runs::incrementAndGet));
        txe.RunNextMainThreadTask();
        assertEquals(1, runs.get(), "a task whose delay has not elapsed must not run");
    }

    @Test
    @Requirement("TBX_032")
    @DisplayName("TBX_032: one call runs one task, leaving the rest queued")
    void oneCallRunsOneTask() {
        TXE txe = new TXE();
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            txe.queueMainThreadTask(new TebexTask(dueIn(0), runs::incrementAndGet));
        }

        txe.RunNextMainThreadTask();

        assertEquals(1, runs.get(), "the host drains one task per tick, not the whole queue");
    }

    @Test
    @Requirement("TBX_036")
    @DisplayName("TBX_036: re-queueing the same deliverable while it is pending executes it once")
    void duplicateQueueingExecutesOnce() {
        TXE txe = new TXE();
        AtomicInteger deliveries = new AtomicInteger();

        // This is the duplicate-delivery scenario: a command's id only reaches
        // executedCommands once its body runs, so every check cycle in the
        // meantime re-queues it. Ten cycles must still deliver one item.
        for (int cycle = 0; cycle < 10; cycle++) {
            txe.queueMainThreadTask(
                    new TebexTask(dueIn(0), "command:42", deliveries::incrementAndGet));
        }

        for (int tick = 0; tick < 10; tick++) {
            txe.RunNextMainThreadTask();
        }

        assertEquals(1, deliveries.get(),
                "a deliverable queued 10 times while pending must be executed exactly once");
    }

    @Test
    @Requirement("TBX_036")
    @DisplayName("TBX_036: a delayed deliverable re-queued every cycle still executes once")
    void duplicateQueueingOfADelayedTaskExecutesOnce() {
        TXE txe = new TXE();
        AtomicInteger deliveries = new AtomicInteger();

        // The worst case in practice: the task is not yet due, so it stays in the
        // queue while checks keep re-queueing it, and they all come due together.
        for (int cycle = 0; cycle < 5; cycle++) {
            txe.queueMainThreadTask(
                    new TebexTask(dueIn(-1), "command:7", deliveries::incrementAndGet));
        }
        for (int tick = 0; tick < 5; tick++) {
            txe.RunNextMainThreadTask();
        }

        assertEquals(1, deliveries.get(), "only one copy may be held and executed");
    }

    @Test
    @Requirement("TBX_036")
    @DisplayName("TBX_036: the key is released after running, so a later re-issue can be delivered")
    void keyIsReleasedAfterRunning() {
        TXE txe = new TXE();
        AtomicInteger deliveries = new AtomicInteger();

        txe.queueMainThreadTask(new TebexTask(dueIn(0), "command:1", deliveries::incrementAndGet));
        txe.RunNextMainThreadTask();
        assertEquals(1, deliveries.get());

        // Same key again after the first completed: this is a genuinely new
        // delivery, not a duplicate of a pending one, so it must be accepted.
        txe.queueMainThreadTask(new TebexTask(dueIn(0), "command:1", deliveries::incrementAndGet));
        txe.RunNextMainThreadTask();
        assertEquals(2, deliveries.get(), "the key must not block deliveries forever");
    }

    @Test
    @Requirement("TBX_036")
    @Requirement("TBX_001")
    @DisplayName("TBX_036/TBX_001: a task whose body throws releases its key so it can be retried")
    void failedTaskReleasesItsKey() {
        TXE txe = new TXE();
        AtomicInteger attempts = new AtomicInteger();

        txe.queueMainThreadTask(new TebexTask(dueIn(0), "command:9", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("delivery failed");
        }));
        txe.RunNextMainThreadTask();
        assertEquals(1, attempts.get());

        // The command was never marked executed, so the next check re-queues it.
        // If the key leaked, the retry would be silently dropped forever.
        txe.queueMainThreadTask(new TebexTask(dueIn(0), "command:9", attempts::incrementAndGet));
        txe.RunNextMainThreadTask();

        assertEquals(2, attempts.get(), "a failed delivery must be retryable");
    }

    @Test
    @Requirement("TBX_036")
    @DisplayName("TBX_036: tasks without a key are never deduplicated")
    void tasksWithoutKeysAreNotDeduplicated() {
        TXE txe = new TXE();
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            txe.queueMainThreadTask(new TebexTask(dueIn(0), runs::incrementAndGet));
        }
        for (int tick = 0; tick < 3; tick++) {
            txe.RunNextMainThreadTask();
        }

        assertEquals(3, runs.get(), "an unkeyed task opts out of deduplication");
    }

    @Test
    @Requirement("TBX_036")
    @DisplayName("TBX_036: different deliverables are not confused with each other")
    void differentKeysAreIndependent() {
        TXE txe = new TXE();
        List<String> ran = new ArrayList<>();

        txe.queueMainThreadTask(new TebexTask(dueIn(0), "command:1", () -> ran.add("one")));
        txe.queueMainThreadTask(new TebexTask(dueIn(0), "command:2", () -> ran.add("two")));
        txe.RunNextMainThreadTask();
        txe.RunNextMainThreadTask();

        assertTrue(ran.contains("one") && ran.contains("two"), "got: " + ran);
        assertEquals(2, ran.size());
    }

    @Test
    @Requirement("TBX_040")
    @DisplayName("TBX_040: draining an empty queue is a no-op")
    void emptyQueueIsSafe() {
        TXE txe = new TXE();
        AtomicInteger runs = new AtomicInteger();

        txe.RunNextMainThreadTask();
        txe.queueMainThreadTask(null);
        txe.RunNextMainThreadTask();

        assertEquals(0, runs.get());
        assertFalse(txe.IsRunning());
    }
}
