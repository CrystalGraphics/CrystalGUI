package com.crystalgui.core.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.crystalgui.core.signal.Signal;

/** One probe per rung of closing a document that does not answer: abort, interrupt, abandon. */
public class CloseLadderTest {

    private static void await(UiSequence sequence) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!sequence.isClosed() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue("the sequence ended", sequence.isClosed());
    }

    @Test
    public void aLoopThroughTheEngineIsAbortedAtACheckpoint() throws InterruptedException {
        UiSequence sequence = UiSequence.create("probe:loop");
        Signal.Action tick = new Signal.Action();
        AtomicInteger heard = new AtomicInteger();
        tick.connect(heard::incrementAndGet);
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        sequence.execute(() -> {
            try {
                running.countDown();
                while (true) tick.emit();                 // never returns on its own: each emit is a checkpoint
            } finally {
                cleaned.set(true);                        // a finally still runs under an abort
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        sequence.abort();
        await(sequence);
        assertTrue("the unwinding ran the author's finally", cleaned.get());
    }

    @Test
    public void anAbortIsNotSwallowedByACatchOfException() throws InterruptedException {
        UiSequence sequence = UiSequence.create("probe:swallow");
        Signal.Action tick = new Signal.Action();
        tick.connect(() -> { });
        CountDownLatch running = new CountDownLatch(1);
        sequence.execute(() -> {
            running.countDown();
            while (true) {
                try {
                    tick.emit();
                } catch (Exception ignored) {
                    // an author's catch: an Error passes it
                }
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        sequence.abort();
        await(sequence);
    }

    @Test
    public void aBlockingWaitIsInterruptedAndTheSequenceEndsAfterIt() throws InterruptedException {
        UiSequence sequence = UiSequence.create("probe:wait");
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        sequence.execute(() -> {
            waiting.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        sequence.abort();
        assertFalse("a sleep does not reach a checkpoint", sequence.isClosed());
        sequence.interrupt();
        await(sequence);
        assertTrue(interrupted.get());
    }

    @Test
    public void aTightLoopOutsideTheEngineIsAbandonedAndContained() throws InterruptedException {
        UiSequence sequence = UiSequence.create("probe:spin");
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch spinning = new CountDownLatch(1);
        Thread[] thread = new Thread[1];
        sequence.execute(() -> {
            thread[0] = Thread.currentThread();
            spinning.countDown();
            while (!release.get()) Thread.onSpinWait();   // no checkpoint, no wait
        });
        try {
            assertTrue(spinning.await(5, TimeUnit.SECONDS));
            sequence.abort();
            sequence.interrupt();
            Thread.sleep(100);
            assertFalse("neither rung reaches it", sequence.isClosed());
            long before = System.nanoTime();
            sequence.abandon();
            sequence.runNow(() -> { });                   // what closing the document does: it must not wait
            assertTrue("did not wait for the stuck thread", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
            assertTrue(sequence.isClosed());
            assertEquals("left at minimum priority", Thread.MIN_PRIORITY, thread[0].getPriority());
            AtomicBoolean ran = new AtomicBoolean();
            sequence.execute(() -> ran.set(true));
            Thread.sleep(50);
            assertFalse("nothing more is accepted", ran.get());
        } finally {
            release.set(true);
        }
    }
}
