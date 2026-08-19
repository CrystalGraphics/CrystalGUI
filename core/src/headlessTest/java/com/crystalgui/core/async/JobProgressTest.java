package com.crystalgui.core.async;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>What the chrome is allowed to draw, and when.</b>
 *
 * <p>Every assertion here is about a policy that fails <em>silently</em> if it is wrong: a status bar that
 * strobes on every keystroke, a row that flashes in and out, a cancel that looks like a hang, a bar drawn
 * past its own end. None of them throws, and none is visible in a screenshot taken at the wrong moment —
 * which is exactly why they are pinned here rather than looked at.</p>
 *
 * <p>The clock is a field, so timing is stepped rather than waited on. A test that slept would be both
 * slow and flaky, and could not express "one millisecond before the threshold" at all.</p>
 */
public class JobProgressTest {

    private static final Executor SAME_THREAD = Runnable::run;

    /** Real threads, because a job must be observable WHILE it runs. @see #live */
    private static final Executor THREADS = runnable -> {
        Thread worker = new Thread(runnable, "job-progress-test");
        worker.setDaemon(true);
        worker.start();
    };

    private long now = 1_000L;
    private final LongSupplier clock = () -> now;

    private JobScheduler scheduler(Executor executor) {
        return new JobScheduler(executor, clock, 4);
    }

    private static JobKey key(String kind) {
        return JobKey.of(JobProgressTest.class, kind);
    }

    /**
     * A job that has reported and is <b>still running</b>, held until it is finished.
     *
     * <p>The fixture this file needed and did not have at first. With an executor that defers or runs
     * inline, the work body runs to completion the moment it is released -- so the job is removed from
     * {@code running} by {@code deliverCompleted()} in the very drain that would have drawn it, and
     * nothing is ever visible. Progress is a property of work <em>in flight</em>, so the test has to keep
     * some in flight. Latches rather than sleeps, so nothing here is timing-dependent.</p>
     */
    private Held live(JobScheduler scheduler, String what, long total) {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobKey key = key(what);
        scheduler.job(key, JobLane.BACKGROUND, context -> {
            context.progress().begin(what, total);
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return what;
        }).submit();
        scheduler.drain();
        try {
            assertTrue("the worker never started", reported.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Held(key, release);
    }

    private record Held(JobKey key, CountDownLatch release) {
    }

    /** Lets a held job end and waits for the scheduler to collect it. */
    private void finishAndCollect(JobScheduler scheduler, Held held) {
        held.release().countDown();
        for (int attempt = 0; attempt < 500 && scheduler.runningCount() > 0; attempt++) {
            scheduler.drain();
            if (scheduler.runningCount() == 0) break;
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Appearing ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A job that never calls begin() is never drawn</b>, however long it runs.
     *
     * <p>The strobe test. An analysis runs on every keystroke and none of it announces itself, so if
     * merely being in flight were enough the status bar would flicker continuously.</p>
     */
    @Test
    public void aJobThatNeverAnnouncesItselfIsNeverShown() {
        JobScheduler scheduler = scheduler(SAME_THREAD);
        scheduler.job(key("quiet"), JobLane.BACKGROUND, context -> "done").submit();
        scheduler.drain();

        now += 10_000L;
        scheduler.drain();
        assertTrue("a job that never called begin() reached the chrome", scheduler.active().isEmpty());
    }

    /** And one that does announce itself still waits out the delay before appearing. */
    @Test
    public void anAnnouncedJobWaitsOutTheDelay() {
        JobScheduler scheduler = scheduler(THREADS);
        Held held = live(scheduler, "downloading", 100);

        now += JobScheduler.DEFAULT_SHOW_AFTER_MILLIS - 1;
        scheduler.drain();
        assertTrue("shown one millisecond early", scheduler.active().isEmpty());

        now += 1;
        scheduler.drain();
        assertEquals(1, scheduler.active().size());
        assertEquals("downloading", scheduler.active().get(0).state().what());
        finishAndCollect(scheduler, held);
    }

    // ── Staying ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Once shown it stays</b>, even though the job has already finished.
     *
     * <p>Without this a job that crosses the delay and finishes a frame later flashes a row in and out,
     * which reads as a glitch rather than as work.</p>
     */
    @Test
    public void aShownJobStaysForTheMinimum() {
        JobScheduler scheduler = scheduler(THREADS);
        Held held = live(scheduler, "downloading", 100);

        now += JobScheduler.DEFAULT_SHOW_AFTER_MILLIS;
        scheduler.drain();
        assertEquals("did not appear", 1, scheduler.active().size());

        // The job finishes here -- its result is delivered and it leaves `running`.
        finishAndCollect(scheduler, held);
        assertEquals("left as soon as the work finished", 1, scheduler.active().size());

        now += JobScheduler.DEFAULT_MINIMUM_VISIBLE_MILLIS;
        scheduler.drain();
        assertTrue("outstayed its minimum", scheduler.active().isEmpty());
    }

    /** A job that finishes before the delay leaves at once — there is no minimum to protect. */
    @Test
    public void aJobThatFinishesBeforeTheDelayNeverAppears() {
        JobScheduler scheduler = scheduler(THREADS);
        Held held = live(scheduler, "quick", 10);
        finishAndCollect(scheduler, held);

        now += 10;
        scheduler.drain();
        now += 10_000L;
        scheduler.drain();
        assertTrue("a job too short to be drawn was drawn anyway", scheduler.active().isEmpty());
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Cancelling marks the row; it does not remove it.</b>
     *
     * <p>Cancellation is cooperative, so there is a real gap between asking and the worker noticing. A row
     * that vanished on the click would claim the work had stopped when it had not.</p>
     */
    @Test
    public void cancellingMarksTheRowAndKeepsIt() {
        JobScheduler scheduler = scheduler(THREADS);
        Held held = live(scheduler, "downloading", 100);
        now += JobScheduler.DEFAULT_SHOW_AFTER_MILLIS;
        scheduler.drain();
        assertFalse(scheduler.active().get(0).cancelRequested());

        scheduler.cancel(held.key());
        assertEquals("the row left on cancel", 1, scheduler.active().size());
        assertTrue("cancel was not visible on the row", scheduler.active().get(0).cancelRequested());
        finishAndCollect(scheduler, held);
    }

    /** Most recently begun first — never by how far along, which reorders rows under the cursor. */
    @Test
    public void newestFirst() {
        JobScheduler scheduler = scheduler(THREADS);
        Held first = live(scheduler, "first", 100);
        now += 50;
        Held second = live(scheduler, "second", 100);

        now += JobScheduler.DEFAULT_SHOW_AFTER_MILLIS;
        scheduler.drain();
        List<ActiveJob> active = scheduler.active();
        assertEquals(2, active.size());
        assertEquals("second", active.get(0).state().what());
        assertEquals("first", active.get(1).state().what());
        finishAndCollect(scheduler, first);
        finishAndCollect(scheduler, second);
    }

    // ── The state itself ────────────────────────────────────────────────────────────────────────

    /** Never null, so a job never branches on whether anyone is watching. */
    @Test
    public void progressIsNeverNull() {
        JobScheduler scheduler = scheduler(SAME_THREAD);
        scheduler.job(key("checks"), JobLane.BACKGROUND, context -> {
            assertNotNull("a running job had no progress channel", context.progress());
            return "done";
        }).submit();
        scheduler.drain();
        assertSame(Progress.NONE, Progress.NONE);
    }

    /**
     * <b>A bar is never drawn past its own end</b>, and an over-report is clamped rather than shown.
     *
     * <p>A caller that reports more than it promised is reporting its own bug; the chrome is the wrong
     * place to display it. And a zero total is complete rather than a division by zero — "nothing to do"
     * is done.</p>
     */
    @Test
    public void theFractionIsClamped() {
        assertEquals(0f, new ProgressState("x", "", -5, 100, 0L).fraction(), 0.0001f);
        assertEquals(1f, new ProgressState("x", "", 500, 100, 0L).fraction(), 0.0001f);
        assertEquals(1f, new ProgressState("x", "", 0, 0, 0L).fraction(), 0.0001f);
        assertEquals(0.25f, new ProgressState("x", "", 25, 100, 0L).fraction(), 0.0001f);
        assertTrue(new ProgressState("x", "", 0, -1, 0L).isIndeterminate());
        assertEquals(-1f, new ProgressState("x", "", 0, -1, 0L).fraction(), 0.0001f);
    }

    /** Reporting before begin() is meaningless rather than fatal — a library must not throw from it. */
    @Test
    public void reportingBeforeBeginIsHarmless() {
        JobScheduler scheduler = scheduler(SAME_THREAD);
        scheduler.job(key("early"), JobLane.BACKGROUND, context -> {
            context.progress().advance(5);
            context.progress().detail("nothing announced yet");
            return "done";
        }).submit();
        scheduler.drain();
        now += 10_000L;
        scheduler.drain();
        assertTrue(scheduler.active().isEmpty());
    }
}
