package com.crystalgui.language.run;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.console.RunElapsed;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.3 — the rail's clock.
 *
 * <p>The elapsed time is what stands in for IntelliJ's spinner, so it has to be right in the two states a
 * spinner cannot distinguish: still going, and finished a while ago.</p>
 */
public class RunElapsedTest {

    private static Resource script(String name) {
        return Resource.of(CgPath.of("workspace", "src/" + name));
    }

    private static long seconds(long value) {
        return TimeUnit.SECONDS.toNanos(value);
    }

    // ── Formatting ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Below a second says {@code <1 sec}, never {@code 0 sec}.</b>
     *
     * <p>Zero reads as "not started", which is the wrong thing to say about the one moment a script is
     * most obviously alive.</p>
     */
    @Test
    public void underASecondIsNotZero() {
        assertEquals("<1 sec", RunElapsed.format(0));
        assertEquals("<1 sec", RunElapsed.format(TimeUnit.MILLISECONDS.toNanos(999)));
        assertEquals("1 sec", RunElapsed.format(seconds(1)));
    }

    /** Two units, never three — the seconds are noise beside the hours and the rail is narrow. */
    @Test
    public void atMostTwoUnits() {
        assertEquals("45 sec", RunElapsed.format(seconds(45)));
        assertEquals("6 min, 32 sec", RunElapsed.format(seconds(6 * 60 + 32)));
        assertEquals("6 min", RunElapsed.format(seconds(6 * 60)));
        assertEquals("1 hr, 4 min", RunElapsed.format(seconds(3600 + 4 * 60 + 9)));
        assertEquals("2 hr", RunElapsed.format(seconds(7200)));
    }

    /** A negative duration is not a duration — clocks are read on two threads. */
    @Test
    public void aNegativeDurationIsFloored() {
        assertEquals("<1 sec", RunElapsed.format(-seconds(30)));
    }

    // ── Session timing ───────────────────────────────────────────────────────────────────────────

    /** An active session counts from when it started; the clock moves because the world does. */
    @Test
    public void anActiveSessionTicks() {
        AtomicLong now = new AtomicLong(1_000);
        RunSessions sessions = new RunSessions(now::get);
        Resource main = script("Main.java");

        sessions.set(main, RunState.RUNNING);
        now.addAndGet(seconds(5));

        assertEquals(seconds(5), sessions.sessionOf(main).elapsedNanos(now.get()));
    }

    /**
     * <b>A finished session freezes.</b>
     *
     * <p>So a row reads the same whether you watched it finish or came back an hour later — which is the
     * property a spinner cannot have, since it only ever says "now".</p>
     */
    @Test
    public void afinishedSessionFreezes() {
        AtomicLong now = new AtomicLong(1_000);
        RunSessions sessions = new RunSessions(now::get);
        Resource main = script("Main.java");

        sessions.set(main, RunState.RUNNING);
        now.addAndGet(seconds(3));
        sessions.set(main, RunState.FINISHED);
        now.addAndGet(seconds(600));

        assertEquals("the duration must not keep growing after it ended",
                seconds(3), sessions.sessionOf(main).elapsedNanos(now.get()));
    }

    /**
     * <b>Becoming LIVE does not restart the clock.</b>
     *
     * <p>A one-shot that registers handlers and settles into {@code LIVE} is one run, not two — restarting
     * at the handover would report a script that has been up for an hour as seconds old.</p>
     */
    @Test
    public void aHandoverBetweenActiveStatesKeepsTheStart() {
        AtomicLong now = new AtomicLong(1_000);
        RunSessions sessions = new RunSessions(now::get);
        Resource main = script("Main.java");

        sessions.set(main, RunState.RUNNING);
        now.addAndGet(seconds(10));
        sessions.set(main, RunState.LIVE, 3);
        now.addAndGet(seconds(5));

        assertEquals(seconds(15), sessions.sessionOf(main).elapsedNanos(now.get()));
    }

    /** A re-run starts a new clock — the previous run's duration is not this one's. */
    @Test
    public void aReRunStartsAgain() {
        AtomicLong now = new AtomicLong(1_000);
        RunSessions sessions = new RunSessions(now::get);
        Resource main = script("Main.java");

        sessions.set(main, RunState.RUNNING);
        now.addAndGet(seconds(30));
        sessions.set(main, RunState.FINISHED);
        now.addAndGet(seconds(120));

        sessions.set(main, RunState.RUNNING);
        now.addAndGet(seconds(2));

        assertEquals(seconds(2), sessions.sessionOf(main).elapsedNanos(now.get()));
    }

    /**
     * <b>The timestamps must not make every reading a change.</b>
     *
     * <p>{@code set} no-ops when the state is unchanged, which is what stops a per-tick script emitting a
     * signal on every invocation. Comparing whole records would defeat it outright — two readings a
     * nanosecond apart are never equal — so the comparison is on state and handlers alone.</p>
     */
    @Test
    public void anUnchangedStateIsStillNotAnnounced() {
        AtomicLong now = new AtomicLong(1_000);
        RunSessions sessions = new RunSessions(now::get);
        Resource main = script("Main.java");
        sessions.set(main, RunState.LIVE, 3);

        int[] changes = {0};
        sessions.onDidChange.connect(script -> changes[0]++);

        for (int tick = 0; tick < 50; tick++) {
            now.addAndGet(seconds(1));
            sessions.set(main, RunState.LIVE, 3);
        }
        assertEquals("a handler firing does not change what the script IS", 0, changes[0]);

        sessions.set(main, RunState.LIVE, 4);
        assertEquals("but the handler count does", 1, changes[0]);
    }
}
