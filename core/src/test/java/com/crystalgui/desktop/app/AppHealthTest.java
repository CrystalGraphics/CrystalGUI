package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;

/** An application's document is not responding after 5 s of waiting input; closing climbs abort, interrupt, abandon. */
public class AppHealthTest {

    private static final long SECOND = 1_000_000_000L;

    private static final class Probe implements AppDocument.Backend {
        int aborted, interrupted, abandoned;
        boolean stopped;

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return true;
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return true;
        }

        @Override
        public Thread runningThread() {
            return Thread.currentThread();
        }

        @Override
        public void abort() {
            aborted++;
        }

        @Override
        public void interrupt() {
            interrupted++;
        }

        @Override
        public void abandon() {
            abandoned++;
        }

        @Override
        public boolean stopped() {
            return stopped;
        }

        @Override
        public void close() {
        }
    }

    private Probe probe;
    private AppHealth health;
    private long now;

    @Before
    public void arrange() {
        probe = new Probe();
        health = new AppHealth(probe, () -> "VFX Lab");
        now = System.nanoTime();
    }

    @Test
    public void inputWaitingFiveSecondsWithNoCommitIsNotResponding() {
        health.offered();
        assertFalse("not yet", health.step(now + 4 * SECOND));
        assertTrue(health.step(now + 6 * SECOND));
        assertEquals(AppHealth.State.NOT_RESPONDING, health.state());
        assertNotNull("the stack was taken for the report", health.report());
        assertEquals("VFX Lab is not responding", health.report().title());
    }

    @Test
    public void aDocumentWithNoInputWaitingIsNeverNotResponding() {
        assertFalse(health.step(now + 60 * SECOND));
        assertEquals(AppHealth.State.RESPONDING, health.state());
    }

    @Test
    public void aCommitEndsIt() {
        health.offered();
        health.step(now + 6 * SECOND);
        health.committed();
        assertEquals(AppHealth.State.RESPONDING, health.state());
        assertNull(health.report());
    }

    @Test
    public void waitKeepsTheWindowGhostedUntilTheDocumentAnswers() {
        health.offered();
        health.step(now + 6 * SECOND);
        health.patient();
        assertEquals("still not responding", AppHealth.State.NOT_RESPONDING, health.state());
        assertTrue("the box does not reopen by itself", health.isPatient());
        health.committed();
        assertEquals(AppHealth.State.RESPONDING, health.state());
        assertFalse("an answer ends the patience", health.isPatient());
    }

    @Test
    public void closingAbortsAtOnceInterruptsThenAbandonsAfterTwoSeconds() {
        health.offered();
        health.step(now + 6 * SECOND);
        long closed = now + 6 * SECOND;
        health.close(closed);
        assertEquals("rung 1 at once", 1, probe.aborted);
        health.step(closed + SECOND / 10);
        assertEquals("not yet", 0, probe.interrupted);
        health.step(closed + SECOND / 2);
        assertEquals("rung 2", 1, probe.interrupted);
        assertEquals(0, probe.abandoned);
        assertTrue("rung 3 after two seconds", health.step(closed + 2 * SECOND));
        assertEquals(1, probe.abandoned);
        assertEquals(AppHealth.State.ABANDONED, health.state());
        assertTrue(health.ended());
    }

    @Test
    public void aDocumentThatUnwindsEndsTheLadderEarly() {
        health.close(now);
        probe.stopped = true;
        assertTrue(health.ended());
        assertFalse("nothing is abandoned that already stopped", health.step(now + 3 * SECOND));
        assertEquals(0, probe.abandoned);
    }
}
