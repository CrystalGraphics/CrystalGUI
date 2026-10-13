package com.crystalgui.core.async;

import com.crystalgraphics.job.CgJobKey;
import com.crystalgraphics.job.CgJobLane;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the scheduler adds over {@code CgJob}: delivery at its drain, cancelling by owner, and disposal. Keys,
 * debounce, lanes and supersession are {@code CgJobLoopTest}'s.
 */
public class JobSchedulerTest {

    private static final Executor SAME_THREAD = Runnable::run;

    private long now = 1_000L;
    private final LongSupplier clock = () -> now;

    private JobScheduler scheduler() {
        return new JobScheduler(SAME_THREAD, clock);
    }

    /** Work is held until the list is run, so jobs stay in flight. */
    private JobScheduler deferred(List<Runnable> pending) {
        return new JobScheduler(pending::add, clock);
    }

    @Test
    public void aResultIsDeliveredAtTheDrainAndNotBefore() {
        JobScheduler scheduler = scheduler();
        List<String> delivered = new ArrayList<>();

        scheduler.job(CgJobKey.of(new Object(), "work"), CgJobLane.LATENCY, context -> "answer")
                .onDone(delivered::add)
                .submit();

        assertTrue("nothing lands before a drain", delivered.isEmpty());
        scheduler.drain();
        assertEquals(List.of("answer"), delivered);
    }

    @Test
    public void aDrainReportsWhetherAnythingIsOutstanding() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending);
        scheduler.job(CgJobKey.of(new Object(), "work"), CgJobLane.LATENCY, context -> 1).submit();

        assertTrue("in flight until it lands", scheduler.drain());
        pending.forEach(Runnable::run);
        scheduler.drain();
        assertFalse("idle, so a ticker can stop", scheduler.drain());
    }

    @Test
    public void aSupersededJobsOnDoneNeverRuns() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending);
        List<String> delivered = new ArrayList<>();
        CgJobKey compile = CgJobKey.of(new Object(), "compile");

        scheduler.job(compile, CgJobLane.LATENCY, context -> "stale").onDone(delivered::add).submit();
        scheduler.job(compile, CgJobLane.LATENCY, context -> "fresh").onDone(delivered::add).submit();

        for (int i = 0; i < 3; i++) {
            List<Runnable> batch = new ArrayList<>(pending);
            pending.clear();
            batch.forEach(Runnable::run);
            scheduler.drain();
        }
        assertEquals(List.of("fresh"), delivered);
    }

    @Test
    public void closingADocumentCancelsEverythingAboutIt() {
        JobScheduler scheduler = scheduler();
        List<String> delivered = new ArrayList<>();
        Object closing = new Object();
        Object staying = new Object();

        scheduler.job(CgJobKey.of(closing, "reparse"), CgJobLane.LATENCY, c -> "a").onDone(delivered::add).submit();
        scheduler.job(CgJobKey.of(closing, "diagnostics"), CgJobLane.LATENCY, c -> "b").onDone(delivered::add).submit();
        scheduler.job(CgJobKey.of(staying, "reparse"), CgJobLane.LATENCY, c -> "survivor").onDone(delivered::add).submit();

        scheduler.cancelAll(closing);
        scheduler.drain();

        assertEquals(List.of("survivor"), delivered);
    }

    @Test
    public void aThrowingJobDoesNotStopTheOthers() {
        JobScheduler scheduler = scheduler();
        List<String> delivered = new ArrayList<>();

        scheduler.job(CgJobKey.of(new Object(), "bad"), CgJobLane.LATENCY, context -> {
            throw new IllegalStateException("boom");
        }).onDone(result -> delivered.add("bad")).submit();
        scheduler.job(CgJobKey.of(new Object(), "good"), CgJobLane.LATENCY, c -> "good").onDone(delivered::add).submit();

        scheduler.drain();
        assertEquals("a failed job runs no onDone", List.of("good"), delivered);
    }

    @Test
    public void disposingStopsEverything() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending);
        List<String> delivered = new ArrayList<>();

        scheduler.job(CgJobKey.of(new Object(), "work"), CgJobLane.LATENCY, c -> "x").onDone(delivered::add).submit();
        scheduler.dispose();
        pending.forEach(Runnable::run);
        scheduler.drain();
        assertEquals(0, scheduler.pendingCount());

        scheduler.job(CgJobKey.of(new Object(), "later"), CgJobLane.LATENCY, c -> "y").onDone(delivered::add).submit();
        scheduler.drain();
        assertTrue("a disposed scheduler delivers nothing", delivered.isEmpty());
    }
}
