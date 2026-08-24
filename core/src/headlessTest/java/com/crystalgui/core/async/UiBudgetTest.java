package com.crystalgui.core.async;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The frame-thread budget guard — what makes an expensive call NAME itself.
 *
 * <h3>Why a guard and not an audit</h3>
 *
 * <p>Two calls cost this application its frame rate and neither was visible from its call site:
 * {@code symbolOf(Resource)} reads exactly like a property getter and ran a 761ms compile, and
 * {@code HostClasspath.detect()} reads like a getter and opened every jar on the classpath. Nobody wrote
 * a bad call site, so auditing call sites would have found neither — the cost is behind the callee's
 * signature. What is worth having is something that reports the next one by name.</p>
 */
public class UiBudgetTest {

    @Before
    public void clearState() {
        UiThread.forgetForTesting();
        UiBudget.forgetForTesting();
    }

    @After
    public void restoreState() {
        UiThread.forgetForTesting();
        UiBudget.forgetForTesting();
    }

    private static void burn(long millis) {
        long until = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < until) {
            // Spinning rather than sleeping: the guard measures wall time either way, and a sleep in a
            // test is a scheduler's opinion about how long it lasted.
        }
    }

    /**
     * <b>Nothing is measured before the first frame</b>, so a server and a headless test pay one volatile
     * read.
     */
    @Test
    public void aThreadThatHasNeverDrawnAFrameIsNotTheFrameThread() {
        assertFalse("no frame has run, so nothing owns the tree yet", UiThread.isCurrent());
        assertEquals("timing must not even start off the frame thread", 0L, UiBudget.begin());
    }

    /** ...and the frame itself is what says otherwise. */
    @Test
    public void theFrameMarksItsOwnThread() {
        UiThread.markCurrent();
        assertTrue(UiThread.isCurrent());

        AtomicBoolean elsewhere = new AtomicBoolean(true);
        Thread other = new Thread(() -> elsewhere.set(UiThread.isCurrent()));
        other.start();
        try {
            other.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertFalse("a worker must never be mistaken for the frame thread", elsewhere.get());
    }

    /** <b>The report names the operation</b>, which is the whole point of it. */
    @Test
    public void workOverBudgetOnTheFrameThreadIsReported() {
        UiThread.markCurrent();
        long started = UiBudget.begin();
        burn(5L);
        UiBudget.end(started, "symbolOf library://java.util.ArrayList");

        assertTrue("an over-budget call went unreported",
                UiBudget.hasReported("symbolOf library://java.util.ArrayList"));
    }

    /**
     * The counter-assertion, and it is what stops the guard being noise.
     *
     * <p>A frame does many things and nearly all of them are fast. A guard that reported every one would
     * bury the report it exists to make, which is the same reason each operation is reported once.</p>
     */
    @Test
    public void ordinaryWorkIsNotReported() {
        UiThread.markCurrent();
        long started = UiBudget.begin();
        UiBudget.end(started, "displayName library://java.util.ArrayList");

        assertFalse("a cheap call was reported, which makes the guard noise",
                UiBudget.hasReported("displayName library://java.util.ArrayList"));
    }

    /**
     * <b>Slow work OFF the frame thread is not reported either</b> — that is where it is supposed to be.
     *
     * <p>Without this the guard would report every background compile in the application, which reads as
     * "everything is too slow" and means nothing.</p>
     */
    @Test
    public void slowWorkOffTheFrameThreadIsNotReported() throws Exception {
        UiThread.markCurrent();
        Thread worker = new Thread(() -> {
            long started = UiBudget.begin();
            burn(5L);
            UiBudget.end(started, "read library://java.util.ArrayList");
        });
        worker.start();
        worker.join();

        assertFalse("background work was reported as a frame cost",
                UiBudget.hasReported("read library://java.util.ArrayList"));
    }
}
