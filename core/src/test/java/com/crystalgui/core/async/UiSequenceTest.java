package com.crystalgui.core.async;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A document on a sequence: tasks in order and never two at once, the tree refused to anything outside the
 * sequence, and a job's answer delivered on the sequence that posted it.
 */
public class UiSequenceTest {

    private final List<UiSequence> sequences = new ArrayList<>();

    private UiSequence sequence(String name) {
        UiSequence sequence = UiSequence.create(name);
        sequences.add(sequence);
        return sequence;
    }

    @After
    public void close() {
        sequences.forEach(UiSequence::close);
    }

    /** Runs {@code task} on {@code sequence} and waits for it, rethrowing what it threw. */
    private static <T> T on(UiSequence sequence, java.util.concurrent.Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        sequence.execute(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue("the sequence never ran the task", done.await(10, TimeUnit.SECONDS));
        if (thrown.get() instanceof Exception exception) throw exception;
        if (thrown.get() instanceof Error error) throw error;
        return result.get();
    }

    @Test
    public void tasksRunInOrderAndNeverTwoAtOnce() throws Exception {
        UiSequence sequence = sequence("order");
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        int[] running = {0};
        int[] overlap = {0};
        for (int i = 0; i < 500; i++) {
            int index = i;
            sequence.execute(() -> {
                if (++running[0] > 1) overlap[0]++;
                seen.add(index);
                running[0]--;
            });
        }
        on(sequence, () -> null);
        assertEquals(500, seen.size());
        for (int i = 0; i < 500; i++) assertEquals(i, (int) seen.get(i));
        assertEquals("two tasks of one sequence ran at once", 0, overlap[0]);
    }

    @Test
    public void theTreeIsRefusedOutsideItsSequence() throws Exception {
        UiSequence sequence = sequence("owner");
        UIDocument document = new UIDocument().runOn(sequence);
        on(sequence, () -> {
            document.append(new UIElement());
            document.frame(0.016f, 200f, 100f);
            return null;
        });
        try {
            document.append(new UIElement());
            fail("a node was added from outside the tree's sequence");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("owner"));
        }
        UiSequence other = sequence("other");
        try {
            on(other, () -> document.append(new UIElement()));
            fail("a node was added from another sequence");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("other"));
        }
    }

    @Test
    public void aJobsAnswerComesBackToTheSequenceThatPostedIt() throws Exception {
        UiSequence a = sequence("a");
        UiSequence b = sequence("b");
        JobScheduler aJobs = on(a, JobScheduler::shared);
        JobScheduler bJobs = on(b, JobScheduler::shared);
        assertNotSame("two sequences share one scheduler", aJobs, bJobs);
        assertNotSame("a sequence drains the render thread's scheduler", JobScheduler.shared(), aJobs);

        AtomicReference<UiSequence> deliveredOn = new AtomicReference<>();
        on(a, () -> JobScheduler.shared().job(JobKey.of(this, "answer"), JobLane.INTERACTIVE, context -> 42)
                .onDone(answer -> deliveredOn.set(UiSequence.current()))
                .submit());
        long deadline = System.currentTimeMillis() + 10_000;
        while (deliveredOn.get() == null && System.currentTimeMillis() < deadline) {
            on(b, () -> JobScheduler.shared().drain());
            on(a, () -> JobScheduler.shared().drain());
        }
        assertNotNull("the answer was never delivered", deliveredOn.get());
        assertSame(a, deliveredOn.get());
    }
}
