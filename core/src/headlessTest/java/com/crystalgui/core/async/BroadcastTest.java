package com.crystalgui.core.async;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@link Broadcast} across real sequences, and {@code Signal}'s sequence check. */
public class BroadcastTest {

    private final UiSequence first = UiSequence.create("first");
    private final UiSequence second = UiSequence.create("second");

    @After
    public void close() {
        first.close();
        second.close();
    }

    /** Runs {@code work} on {@code sequence} and waits for it. */
    private static void on(UiSequence sequence, Runnable work) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        sequence.execute(() -> {
            try {
                work.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue("the sequence never ran the work", done.await(5, TimeUnit.SECONDS));
    }

    /** Waits until everything posted to {@code sequence} before now has run. */
    private static void settle(UiSequence sequence) throws InterruptedException {
        on(sequence, () -> { });
    }

    @Test
    public void eachSubscriberHearsOnTheSequenceItSubscribedFrom() throws InterruptedException {
        Broadcast<String> news = Broadcast.create("news");
        AtomicReference<UiSequence> heardOnFirst = new AtomicReference<>();
        AtomicReference<UiSequence> heardOnSecond = new AtomicReference<>();
        on(first, () -> news.subscribe(text -> heardOnFirst.set(UiSequence.current())));
        on(second, () -> news.subscribe(text -> heardOnSecond.set(UiSequence.current())));

        on(first, () -> news.post("hello"));
        settle(first);
        settle(second);

        assertSame(first, heardOnFirst.get());
        assertSame("posted from the first sequence, heard on the second", second, heardOnSecond.get());
    }

    @Test
    public void aPostIsNeverHeardInsideItself() throws InterruptedException {
        Broadcast<Integer> counts = Broadcast.create("counts");
        List<Integer> heard = new CopyOnWriteArrayList<>();
        AtomicReference<List<Integer>> duringPost = new AtomicReference<>();
        on(first, () -> {
            counts.subscribe(heard::add);
            counts.post(1);
            counts.post(2);
            duringPost.set(List.copyOf(heard));
        });
        settle(first);

        assertEquals("not even on the posting sequence", List.of(), duringPost.get());
        assertEquals("and in posting order", List.of(1, 2), heard);
    }

    @Test
    public void disconnectingDropsWhatIsQueued() throws InterruptedException {
        Broadcast<String> news = Broadcast.create("news");
        List<String> heard = new CopyOnWriteArrayList<>();
        AtomicReference<Connection> subscription = new AtomicReference<>();
        on(second, () -> subscription.set(news.subscribe(heard::add)));

        CountDownLatch hold = new CountDownLatch(1);
        second.execute(() -> {
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        // Queued behind the held task, then disconnected before it runs.
        on(first, () -> news.post("queued"));
        subscription.get().disconnect();
        hold.countDown();
        settle(second);

        assertEquals(List.of(), heard);
        assertEquals(0, news.subscriberCount());
    }

    @Test
    public void aWorkerCannotSubscribe() throws InterruptedException {
        Broadcast<String> news = Broadcast.create("news");
        AtomicReference<Throwable> refused = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                news.subscribe(text -> { });
            } catch (IllegalStateException expected) {
                refused.set(expected);
            }
        }, "a-worker");
        // The frame thread is claimed by this test's own thread, so the worker is neither it nor a sequence.
        CgHostThread.drainFrames();
        worker.start();
        worker.join(5000);
        assertNotNull("a worker has nowhere to be told", refused.get());
        assertTrue(refused.get().getMessage(), refused.get().getMessage().contains("news"));
    }

    @Test
    public void aSignalRefusesASecondSequence() throws InterruptedException {
        Signal.Action local = new Signal.Action();
        on(first, () -> local.connect(() -> { }));
        AtomicReference<Throwable> refused = new AtomicReference<>();
        on(second, () -> {
            try {
                local.emit();
            } catch (IllegalStateException expected) {
                refused.set(expected);
            }
        });
        assertNotNull("emitting from another sequence must fail at once", refused.get());
        assertTrue(refused.get().getMessage(), refused.get().getMessage().contains("first"));
        assertTrue(refused.get().getMessage(), refused.get().getMessage().contains("second"));
    }

    @Test
    public void aSignalBuiltOffEverySequenceBindsWhereItIsFirstUsed() throws InterruptedException {
        Signal.Action built = new Signal.Action();
        int[] heard = {0};
        built.connect(() -> heard[0]++);   // the render thread building a tree, before its sequence owns it
        on(first, built::emit);
        assertEquals(1, heard[0]);
        AtomicReference<Throwable> refused = new AtomicReference<>();
        on(second, () -> {
            try {
                built.emit();
            } catch (IllegalStateException expected) {
                refused.set(expected);
            }
        });
        assertNotNull("bound to the first sequence that used it", refused.get());
    }

    @Test
    public void theServicesSequenceOutlivesAClose() {
        UiSequence services = UiSequence.services();
        services.close();
        assertFalse(services.isClosed());
        assertSame(services, UiSequence.services());
    }
}
