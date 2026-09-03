package com.crystalgui.headless;

import com.crystalgui.core.async.JobContext;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.PendingStream;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.async.Stream;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan_fs_rewrite.md} F0.3, D15 — the one asynchronous shape.
 *
 * <p>Headless because {@code core.async} is: a reply is settled by a job on a worker or by a connection
 * on its tick, and neither needs a window.</p>
 */
public class ReplyTest {

    private static <T> PendingReply<T> pending() {
        return new PendingReply<>(null);
    }

    // ── Settling ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aValueReachesThen() {
        PendingReply<String> reply = pending();
        List<String> seen = new ArrayList<>();
        reply.then(seen::add);

        reply.resolve("here");

        assertEquals(List.of("here"), seen);
        assertTrue(reply.isDone());
        assertEquals("here", reply.result());
        assertNull(reply.error());
    }

    @Test
    public void anErrorReachesOnErrorAndNotThen() {
        PendingReply<String> reply = pending();
        List<String> values = new ArrayList<>();
        List<ReplyError> errors = new ArrayList<>();
        reply.then(values::add).onError(errors::add);

        reply.fail(new ReplyError("CONFLICT", "the file moved"));

        assertTrue(values.isEmpty());
        assertEquals(1, errors.size());
        assertEquals("CONFLICT", errors.get(0).code());
        assertNull("a failed reply has no value", reply.result());
    }

    @Test
    public void alwaysRunsOnBothOutcomes() {
        int[] settled = {0};
        PendingReply<String> ok = pending();
        ok.always(() -> settled[0]++);
        ok.resolve("x");

        PendingReply<String> bad = pending();
        bad.always(() -> settled[0]++);
        bad.fail(new ReplyError("NOPE", ""));

        assertEquals(2, settled[0]);
    }

    /**
     * <b>The rule that keeps a test over an in-memory transport honest.</b> Registering after the answer
     * arrived must run the callback, or whether a caller sees its result depends on whether the transport
     * was a socket or a map.
     */
    @Test
    public void aSettledReplyStillAnswersALateSubscriber() {
        PendingReply<String> reply = pending();
        reply.resolve("early");

        List<String> seen = new ArrayList<>();
        int[] settled = {0};
        reply.then(seen::add).always(() -> settled[0]++);

        assertEquals(List.of("early"), seen);
        assertEquals(1, settled[0]);
    }

    @Test
    public void aSettledFailureStillAnswersALateSubscriber() {
        PendingReply<String> reply = pending();
        reply.fail(new ReplyError("GONE", "deleted"));

        List<ReplyError> seen = new ArrayList<>();
        reply.onError(seen::add);

        assertEquals(1, seen.size());
        assertEquals("GONE", seen.get(0).code());
    }

    @Test
    public void aSecondSettleIsIgnored() {
        PendingReply<String> reply = pending();
        reply.resolve("first");
        reply.resolve("second");
        reply.fail(new ReplyError("LATE", ""));

        assertEquals("first", reply.result());
        assertNull(reply.error());
    }

    // ── Cancellation ────────────────────────────────────────────────────────────────────────────

    /**
     * A cancelled reply <b>settles</b>. It would be simpler to leave it pending and let the callbacks rot,
     * and that is exactly what leaves a batch counting completions one short forever.
     */
    @Test
    public void cancellingSettlesWithCancelledAndTellsTheProducer() {
        int[] told = {0};
        PendingReply<String> reply = new PendingReply<>(() -> told[0]++);
        int[] settled = {0};
        reply.always(() -> settled[0]++);

        reply.cancel();

        assertEquals("the producer is told to stop", 1, told[0]);
        assertTrue(reply.isDone());
        assertEquals(ReplyError.CANCELLED, reply.error().code());
        assertEquals("and anything waiting on it is released", 1, settled[0]);
    }

    @Test
    public void cancellingASettledReplyDoesNothing() {
        int[] told = {0};
        PendingReply<String> reply = new PendingReply<>(() -> told[0]++);
        reply.resolve("done");

        reply.cancel();

        assertEquals(0, told[0]);
        assertEquals("done", reply.result());
    }

    // ── map ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void mapTransformsTheValueAndPassesTheErrorThrough() {
        PendingReply<String> source = pending();
        Reply<Integer> mapped = source.map(String::length);
        source.resolve("four");
        assertEquals(Integer.valueOf(4), mapped.result());

        PendingReply<String> failing = pending();
        Reply<Integer> mappedFailure = failing.map(String::length);
        failing.fail(new ReplyError("BAD", ""));
        assertEquals("BAD", mappedFailure.error().code());
    }

    @Test
    public void cancellingAMappedReplyCancelsTheOneItCameFrom() {
        int[] told = {0};
        PendingReply<String> source = new PendingReply<>(() -> told[0]++);

        source.map(String::length).cancel();

        assertEquals(1, told[0]);
        assertTrue(source.isDone());
    }

    // ── Composition ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void bothWaitsForTwo() {
        PendingReply<String> a = pending();
        PendingReply<Integer> b = pending();
        Reply<Reply.Both<String, Integer>> joined = Reply.both(a, b);

        a.resolve("left");
        assertFalse("one is not both", joined.isDone());
        b.resolve(7);

        assertNotNull(joined.result());
        assertEquals("left", joined.result().first());
        assertEquals(Integer.valueOf(7), joined.result().second());
    }

    @Test
    public void bothFailsWithTheFirstFailure() {
        PendingReply<String> a = pending();
        PendingReply<Integer> b = pending();
        Reply<Reply.Both<String, Integer>> joined = Reply.both(a, b);

        b.fail(new ReplyError("FIRST", ""));
        a.resolve("late");

        assertEquals("FIRST", joined.error().code());
    }

    @Test
    public void bothOverTwoAlreadySettledRepliesSettlesImmediately() {
        Reply<Reply.Both<String, String>> joined = Reply.both(Reply.of("a"), Reply.of("b"));
        assertTrue(joined.isDone());
        assertEquals("a", joined.result().first());
    }

    @Test
    public void cancellingBothCancelsEach() {
        int[] told = {0, 0};
        PendingReply<String> a = new PendingReply<>(() -> told[0]++);
        PendingReply<String> b = new PendingReply<>(() -> told[1]++);

        Reply.both(a, b).cancel();

        assertEquals(1, told[0]);
        assertEquals(1, told[1]);
    }

    @Test
    public void allWaitsForEveryOne() {
        PendingReply<String> a = pending();
        PendingReply<String> b = pending();
        PendingReply<String> c = pending();
        Reply<Void> joined = Reply.all(Arrays.asList(a, b, c));

        a.resolve("1");
        c.resolve("3");
        assertFalse(joined.isDone());
        b.resolve("2");

        assertTrue(joined.isDone());
        assertNull(joined.error());
    }

    @Test
    public void allOverNothingIsAlreadyDone() {
        assertTrue(Reply.all(List.of()).isDone());
    }

    @Test
    public void allFailsWithTheFirstFailure() {
        PendingReply<String> a = pending();
        PendingReply<String> b = pending();
        Reply<Void> joined = Reply.all(Arrays.asList(a, b));

        b.fail(new ReplyError("ONE", ""));

        assertEquals("ONE", joined.error().code());
    }

    // ── Stream ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aStreamDeliversItsPiecesInOrderAndThenTheWhole() {
        PendingStream<String> stream = new PendingStream<>(null);
        List<String> pieces = new ArrayList<>();
        List<List<String>> whole = new ArrayList<>();
        stream.onPartial(pieces::add).then(whole::add);

        stream.emit("a");
        stream.emit("b");
        assertTrue("not settled until it finishes", whole.isEmpty());
        stream.finish();

        assertEquals(List.of("a", "b"), pieces);
        assertEquals(1, whole.size());
        assertEquals(List.of("a", "b"), whole.get(0));
    }

    /** A caller who never asked for pieces gets the same answer any other reply would have given. */
    @Test
    public void aStreamIsAnOrdinaryReplyToACallerThatIgnoresItsPieces() {
        PendingStream<String> stream = new PendingStream<>(null);
        stream.emit("a");
        stream.finish();

        assertEquals(List.of("a"), stream.result());
    }

    /** Late subscription replays, for {@link #aSettledReplyStillAnswersALateSubscriber}'s reason. */
    @Test
    public void aLatePartialSubscriberSeesThePiecesAlreadyDelivered() {
        PendingStream<String> stream = new PendingStream<>(null);
        stream.emit("first");
        stream.emit("second");

        List<String> seen = new ArrayList<>();
        stream.onPartial(seen::add);
        stream.emit("third");

        assertEquals(List.of("first", "second", "third"), seen);
    }

    @Test
    public void aFailedStreamKeepsWhatWasAlreadyDeliveredAndEmitsNoMore() {
        PendingStream<String> stream = new PendingStream<>(null);
        List<String> seen = new ArrayList<>();
        stream.onPartial(seen::add);

        stream.emit("a");
        stream.fail(new ReplyError("GONE", "the file was deleted mid-read"));
        stream.emit("b");

        assertEquals(List.of("a"), seen);
        assertEquals("GONE", stream.error().code());
    }

    @Test
    public void aStreamIsAReply() {
        Stream<String> stream = new PendingStream<>(null);
        Reply<List<String>> asReply = stream;
        assertSame(stream, asReply);
    }

    // ── A job is a reply ────────────────────────────────────────────────────────────────────────

    /** Same-thread executor and a hand-cranked clock, so the whole path is deterministic. */
    private JobScheduler sameThreadScheduler(AtomicLong clock) {
        return new JobScheduler(Runnable::run, clock::get, 4);
    }

    @Test
    public void aJobsResultArrivesThroughItsReply() {
        AtomicLong clock = new AtomicLong();
        JobScheduler jobs = sameThreadScheduler(clock);
        List<String> seen = new ArrayList<>();

        Reply<String> reply = jobs.job(JobKey.of(this, "work"), JobLane.LATENCY, context -> "computed")
                .submit()
                .then(seen::add);

        assertFalse("nothing is delivered before a drain", reply.isDone());
        jobs.drain();

        assertEquals(List.of("computed"), seen);
        assertEquals("computed", reply.result());
    }

    /**
     * <b>A job that throws fails its reply.</b> Before this, {@code deliver()} called {@code onDone} with a
     * null result whatever happened, so a failure was indistinguishable from a successful empty answer.
     */
    @Test
    public void aJobThatThrowsFailsItsReply() {
        AtomicLong clock = new AtomicLong();
        JobScheduler jobs = sameThreadScheduler(clock);
        List<String> values = new ArrayList<>();

        Function<JobContext, String> boom = context -> {
            throw new IllegalStateException("no");
        };
        Reply<String> reply = jobs.job(JobKey.of(this, "boom"), JobLane.LATENCY, boom)
                .submit().then(values::add);
        jobs.drain();

        assertTrue("a thrown job delivers no value", values.isEmpty());
        assertEquals(ReplyError.FAILED, reply.error().code());
        assertTrue(reply.error().cause() instanceof IllegalStateException);
    }

    /** Cancelling the reply reaches the scheduler, so the work stops rather than merely being ignored. */
    @Test
    public void cancellingAJobsReplyCancelsTheJob() {
        AtomicLong clock = new AtomicLong();
        JobScheduler jobs = sameThreadScheduler(clock);
        List<String> seen = new ArrayList<>();

        Reply<String> reply = jobs.job(JobKey.of(this, "work"), JobLane.LATENCY, context -> "computed")
                .submit()
                .then(seen::add);
        reply.cancel();
        jobs.drain();

        assertTrue("a cancelled job delivers nothing", seen.isEmpty());
        assertEquals(ReplyError.CANCELLED, reply.error().code());
    }

    /** A job and a wire call compose, which is the whole point of there being one type. */
    @Test
    public void aJobAndAPlainReplyCompose() {
        AtomicLong clock = new AtomicLong();
        JobScheduler jobs = sameThreadScheduler(clock);
        PendingReply<String> fromTheWire = pending();

        Reply<Reply.Both<String, String>> joined = Reply.both(
                jobs.job(JobKey.of(this, "local"), JobLane.LATENCY, context -> "local").submit(),
                fromTheWire);

        jobs.drain();
        assertFalse(joined.isDone());
        fromTheWire.resolve("remote");

        assertEquals("local", joined.result().first());
        assertEquals("remote", joined.result().second());
    }
}
