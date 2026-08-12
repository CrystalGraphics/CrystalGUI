package com.crystalgui.core.async;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The scheduler's whole contract, asserted directly rather than inferred.
 *
 * <p>Every test here runs on a <b>same-thread executor and a hand-cranked clock</b>, which is the reason
 * both are constructor parameters. {@code TransitionEngine} reads {@code System.nanoTime()} itself and
 * consequently can only ever be tested by asserting on its inputs; this class was built the other way
 * round so that "superseded", "cancelled", "debounced" and "starved" are each one exact assertion.</p>
 */
public class JobSchedulerTest {

    /** Runs inline, so a job is finished by the time {@code drain()} returns from promoting it. */
    private static final Executor SAME_THREAD = Runnable::run;

    private long now = 1_000L;
    private final LongSupplier clock = () -> now;

    private JobScheduler scheduler(int maxConcurrent) {
        return new JobScheduler(SAME_THREAD, clock, maxConcurrent);
    }

    /** A scheduler whose work is held until the returned list is run — i.e. jobs stay genuinely in flight. */
    private JobScheduler deferred(List<Runnable> pending, int maxConcurrent) {
        return new JobScheduler(pending::add, clock, maxConcurrent);
    }

    private static JobKey key(Object owner, String kind) {
        return JobKey.of(owner, kind);
    }

    // ── Delivery ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aResultIsDeliveredOnTheTickAndNotBefore() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        Object document = new Object();

        scheduler.job(key(document, "work"), JobLane.LATENCY, context -> "answer")
                .onDone(delivered::add)
                .submit();

        // Submitting alone must run nothing: the frame tick is the only thing that starts work, which is
        // what keeps every decision in this class on the UI thread.
        assertTrue("nothing may run before a drain", delivered.isEmpty());
        assertEquals(1, scheduler.waitingCount());

        scheduler.drain();
        assertEquals(List.of("answer"), delivered);
    }

    @Test
    public void aDrainReportsWhetherAnythingIsOutstanding() {
        // Deferred, so the job is genuinely still in flight when the first drain returns — on a
        // same-thread executor it would already have finished and the answer would be "idle" at once.
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 4);
        scheduler.job(key(new Object(), "work"), JobLane.LATENCY, context -> 1).submit();

        assertTrue("the job it just started is outstanding until it lands", scheduler.drain());

        pending.forEach(Runnable::run);
        assertFalse("an idle scheduler reports idle, so a ticker can stop", scheduler.drain());
    }

    // ── Single-flight ───────────────────────────────────────────────────────────────────────────

    @Test
    public void resubmittingAWaitingKeyReplacesItRatherThanQueueingASecond() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        Object document = new Object();
        JobKey reparse = key(document, "reparse");

        scheduler.job(reparse, JobLane.LATENCY, context -> "first").onDone(delivered::add).submit();
        scheduler.job(reparse, JobLane.LATENCY, context -> "second").onDone(delivered::add).submit();
        scheduler.job(reparse, JobLane.LATENCY, context -> "third").onDone(delivered::add).submit();

        assertEquals("three submissions of one key are one job", 1, scheduler.waitingCount());
        scheduler.drain();

        // The LAST submission wins -- convergence is structural, so a burst of keystrokes cannot leave the
        // editor showing the answer to an older one.
        assertEquals(List.of("third"), delivered);
    }

    @Test
    public void twoDocumentsDoNotFightOverTheSameKind() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        Object left = new Object();
        Object right = new Object();

        scheduler.job(key(left, "reparse"), JobLane.LATENCY, c -> "left").onDone(delivered::add).submit();
        scheduler.job(key(right, "reparse"), JobLane.LATENCY, c -> "right").onDone(delivered::add).submit();

        assertEquals("the owner is half the key; a split pair must not cancel itself",
                2, scheduler.waitingCount());
        scheduler.drain();
        assertEquals(2, delivered.size());
    }

    @Test
    public void twoKindsAboutOneDocumentDoNotFight() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        Object document = new Object();

        scheduler.job(key(document, "reparse"), JobLane.LATENCY, c -> "tokens").onDone(delivered::add).submit();
        scheduler.job(key(document, "diagnostics"), JobLane.BACKGROUND, c -> "errors").onDone(delivered::add).submit();

        assertEquals("a reparse must not cancel its own document's diagnostics",
                2, scheduler.waitingCount());
        scheduler.drain();
        assertEquals(2, delivered.size());
    }

    // ── Supersession ────────────────────────────────────────────────────────────────────────────

    @Test
    public void aSupersededRunningJobsResultIsDiscardedEvenIfItNeverPolled() {
        // The load-bearing one. A job body that ignores its context entirely must still not be able to
        // deliver a stale answer -- correctness cannot depend on well-behaved job bodies.
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 4);
        List<String> delivered = new ArrayList<>();
        JobKey compile = key(new Object(), "compile");

        scheduler.job(compile, JobLane.LATENCY, context -> "stale").onDone(delivered::add).submit();
        scheduler.drain();
        assertEquals("in flight, not finished", 1, scheduler.runningCount());

        scheduler.job(compile, JobLane.LATENCY, context -> "fresh").onDone(delivered::add).submit();

        pending.forEach(Runnable::run);           // the first job finishes, obliviously
        pending.clear();
        scheduler.drain();                        // ... and its result is dropped on generation
        assertTrue("a superseded result must never be delivered", delivered.isEmpty());

        scheduler.drain();                        // the replacement starts
        pending.forEach(Runnable::run);
        scheduler.drain();
        assertEquals(List.of("fresh"), delivered);
    }

    @Test
    public void aSupersededRunningJobIsAskedToStop() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 4);
        JobKey compile = key(new Object(), "compile");
        List<JobContext> contexts = new ArrayList<>();

        scheduler.job(compile, JobLane.LATENCY, context -> {
            contexts.add(context);
            return "x";
        }).submit();
        scheduler.drain();
        pending.forEach(Runnable::run);           // capture the context by running the body

        assertFalse(contexts.get(0).isCancelled());
        scheduler.job(compile, JobLane.LATENCY, context -> "y").submit();
        assertTrue("supersession asks the runner to stop, as well as dropping it",
                contexts.get(0).isCancelled());
    }

    @Test
    public void cancellingDropsAWaitingJobEntirely() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        JobKey work = key(new Object(), "work");

        scheduler.job(work, JobLane.LATENCY, context -> "gone").onDone(delivered::add).submit();
        scheduler.cancel(work);
        scheduler.drain();

        assertTrue(delivered.isEmpty());
        assertEquals(0, scheduler.waitingCount());
    }

    @Test
    public void closingADocumentCancelsEverythingAboutIt() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        Object closing = new Object();
        Object staying = new Object();

        scheduler.job(key(closing, "reparse"), JobLane.LATENCY, c -> "a").onDone(delivered::add).submit();
        scheduler.job(key(closing, "diagnostics"), JobLane.LATENCY, c -> "b").onDone(delivered::add).submit();
        scheduler.job(key(staying, "reparse"), JobLane.LATENCY, c -> "survivor").onDone(delivered::add).submit();

        scheduler.cancelAll(closing);
        scheduler.drain();

        assertEquals("a closed document's work must not arrive for an editor that no longer exists",
                List.of("survivor"), delivered);
    }

    @Test
    public void aCancelledJobThatThrowsIsSilent() {
        // throwIfCancelled is control flow, not failure: nothing is logged and nothing is delivered.
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 4);
        List<String> delivered = new ArrayList<>();
        JobKey work = key(new Object(), "work");

        scheduler.job(work, JobLane.LATENCY, context -> {
            context.throwIfCancelled();
            return "unreachable-if-cancelled";
        }).onDone(delivered::add).submit();
        scheduler.drain();

        scheduler.cancel(work);
        pending.forEach(Runnable::run);
        scheduler.drain();

        assertTrue(delivered.isEmpty());
    }

    // ── Debounce ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aDebouncedJobWaitsForTheWindow() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();
        JobKey work = key(new Object(), "diagnostics");

        scheduler.job(work, JobLane.LATENCY, context -> "compiled")
                .debounce(300).onDone(delivered::add).submit();

        now += 299;
        scheduler.drain();
        assertTrue("still inside the window", delivered.isEmpty());

        now += 1;
        scheduler.drain();
        assertEquals(List.of("compiled"), delivered);
    }

    @Test
    public void resubmittingInsideTheWindowPushesTheDeadlineOut() {
        // The property that makes a run of keystrokes one compile rather than one per key.
        JobScheduler scheduler = scheduler(4);
        AtomicInteger runs = new AtomicInteger();
        JobKey work = key(new Object(), "diagnostics");

        for (int keystroke = 0; keystroke < 5; keystroke++) {
            scheduler.job(work, JobLane.LATENCY, context -> runs.incrementAndGet())
                    .debounce(300).submit();
            now += 100;                          // typing faster than the window
            scheduler.drain();
        }
        assertEquals("nothing runs while the user is still typing", 0, runs.get());

        now += 300;
        scheduler.drain();
        assertEquals("five keystrokes produce exactly one compile", 1, runs.get());
    }

    // ── Lanes and starvation ────────────────────────────────────────────────────────────────────

    @Test
    public void aMoreUrgentLaneStartsFirst() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 1);
        List<String> order = new ArrayList<>();

        scheduler.job(key(new Object(), "index"), JobLane.BACKGROUND, c -> order.add("background")).submit();
        scheduler.job(key(new Object(), "reparse"), JobLane.LATENCY, c -> order.add("latency")).submit();
        scheduler.job(key(new Object(), "complete"), JobLane.INTERACTIVE, c -> order.add("interactive")).submit();

        scheduler.drain();
        pending.forEach(Runnable::run);

        assertEquals("the human waiting on an answer goes first", List.of("interactive"), order);
    }

    @Test
    public void aStarvedBackgroundJobIsPromotedAheadOfAnUrgentOne() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 1).setStarvationGuardMillis(2_000);
        List<String> order = new ArrayList<>();

        scheduler.job(key(new Object(), "index"), JobLane.BACKGROUND, c -> order.add("background")).submit();

        // The document is being typed in continuously, so higher-lane work keeps arriving.
        now += 2_000;
        scheduler.job(key(new Object(), "reparse"), JobLane.LATENCY, c -> order.add("latency")).submit();

        scheduler.drain();
        pending.forEach(Runnable::run);

        assertEquals("without the guard an index queued behind continuous typing is never built",
                List.of("background"), order);
    }

    @Test
    public void concurrencyIsCapped() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = deferred(pending, 2);

        for (int i = 0; i < 5; i++) {
            scheduler.job(key(new Object(), "work" + i), JobLane.LATENCY, c -> c).submit();
        }
        scheduler.drain();

        assertEquals(2, scheduler.runningCount());
        assertEquals(3, scheduler.waitingCount());
    }

    // ── Failure containment ─────────────────────────────────────────────────────────────────────

    @Test
    public void aThrowingJobDoesNotStopTheOthers() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();

        scheduler.job(key(new Object(), "bad"), JobLane.LATENCY, context -> {
            throw new IllegalStateException("boom");
        }).onDone(result -> delivered.add("bad")).submit();
        scheduler.job(key(new Object(), "good"), JobLane.LATENCY, c -> "good").onDone(delivered::add).submit();

        scheduler.drain();
        assertEquals("a failed job is logged and dropped, never delivered", List.of("good"), delivered);
    }

    @Test
    public void aThrowingCompletionHandlerDoesNotTakeDownTheTick() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();

        scheduler.job(key(new Object(), "a"), JobLane.LATENCY, c -> "a").onDone(result -> {
            throw new IllegalStateException("handler boom");
        }).submit();
        scheduler.job(key(new Object(), "b"), JobLane.LATENCY, c -> "b").onDone(delivered::add).submit();

        scheduler.drain();
        assertEquals("one bad handler must not eat the rest of the tick's results", List.of("b"), delivered);
    }

    @Test
    public void disposingStopsEverything() {
        JobScheduler scheduler = scheduler(4);
        List<String> delivered = new ArrayList<>();

        scheduler.job(key(new Object(), "work"), JobLane.LATENCY, c -> "x").onDone(delivered::add).submit();
        scheduler.dispose();
        scheduler.drain();

        assertTrue(delivered.isEmpty());
        assertEquals(0, scheduler.waitingCount());

        scheduler.job(key(new Object(), "later"), JobLane.LATENCY, c -> "y").onDone(delivered::add).submit();
        scheduler.drain();
        assertTrue("a disposed scheduler accepts nothing further", delivered.isEmpty());
    }
}
