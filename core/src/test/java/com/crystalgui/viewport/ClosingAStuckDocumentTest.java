package com.crystalgui.viewport;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.crystalgui.core.async.UiSequence;

/** Closing a document returns whatever its thread is doing, so the shell that closes it is never frozen with it. */
public class ClosingAStuckDocumentTest {

    private static final long LIMIT_NANOS = TimeUnit.SECONDS.toNanos(3);

    @Test
    public void aTurnLoopingThroughTheEngineIsEndedAtACheckpoint() throws InterruptedException {
        UiSequence sequence = UiSequence.create("probe:engine-loop");
        CountDownLatch running = new CountDownLatch(1);
        sequence.execute(() -> {
            running.countDown();
            while (true) UiSequence.checkpoint();
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertReturns(sequence);
    }

    @Test
    public void aTurnLoopingWhereTheEngineCannotSeeItIsAbandoned() throws InterruptedException {
        UiSequence sequence = UiSequence.create("probe:deaf-loop");
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        sequence.execute(() -> {
            running.countDown();
            while (!stop.get()) Thread.onSpinWait();
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        try {
            assertReturns(sequence);
        } finally {
            stop.set(true);
        }
    }

    @Test
    public void whatIsQueuedRunsBeforeTheCloseWhenNothingIsStuck() {
        UiSequence sequence = UiSequence.create("probe:idle");
        AtomicBoolean disposed = new AtomicBoolean();
        sequence.execute(() -> disposed.set(true));
        DocumentThread.closeSequence(sequence);
        assertTrue("the queued dispose ran", disposed.get());
        assertTrue(sequence.isClosed());
    }

    private static void assertReturns(UiSequence sequence) {
        long started = System.nanoTime();
        DocumentThread.closeSequence(sequence);
        assertTrue("close returned in time", System.nanoTime() - started < LIMIT_NANOS);
        assertTrue("the sequence is closed", sequence.isClosed());
    }
}
