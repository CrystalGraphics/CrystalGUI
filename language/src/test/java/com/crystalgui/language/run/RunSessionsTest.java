package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** M9.5 §9.5.3 — which scripts are live, and what the rail and the indicator read. */
public class RunSessionsTest {

    private static Resource script(String name) {
        return Resource.of(Resource.SCHEME_PROJECT, "src/" + name);
    }

    /**
     * <b>A state that has not changed emits nothing.</b>
     *
     * <p>The reason is the same firehose the console guards against, arriving by another door: a tick
     * handler firing does not change the fact that its script is {@code LIVE}, so a host that reports
     * the state around every invocation would emit twenty signals a second and a rail that redrew on
     * each would spend the frame writing the same word. The no-op belongs here rather than in every
     * caller, because every caller would have to remember it.</p>
     */
    @Test
    public void repeatingAStateIsNotAChange() {
        RunSessions sessions = new RunSessions();
        AtomicInteger changes = new AtomicInteger();
        sessions.onDidChange.connect(r -> changes.incrementAndGet());

        sessions.set(script("tick.js"), RunState.LIVE, 3);
        for (int i = 0; i < 100; i++) sessions.set(script("tick.js"), RunState.LIVE, 3);

        assertEquals("only the transition is a change", 1, changes.get());
    }

    /**
     * <b>The version moves only when the map does — which is the whole point of it.</b>
     *
     * <p>Two per-frame readers, the rail and the panel, used to copy this map to answer questions whose
     * answers change a handful of times in a session. The counter is what lets them ask an int instead,
     * and it is only worth anything if a tick handler reporting {@code LIVE} twenty times a second does
     * not move it — which is the same no-op {@link #repeatingAStateIsNotAChange} pins from the other
     * side. Version and signal have to agree, or a reader that trusts one and a reader that trusts the
     * other will disagree about what the current state is.</p>
     */
    @Test
    public void theVersionTracksRealChangesAndNothingElse() {
        RunSessions sessions = new RunSessions();
        int start = sessions.version();

        sessions.set(script("tick.js"), RunState.LIVE, 3);
        int afterFirst = sessions.version();
        assertNotEquals("a first state was not counted", start, afterFirst);

        for (int i = 0; i < 100; i++) sessions.set(script("tick.js"), RunState.LIVE, 3);
        assertEquals("a repeated state moved the version", afterFirst, sessions.version());

        sessions.set(script("tick.js"), RunState.STOPPED);
        assertNotEquals("a real transition was not counted", afterFirst, sessions.version());

        int beforeForget = sessions.version();
        sessions.forget(script("tick.js"));
        assertNotEquals("forgetting a script was not counted", beforeForget, sessions.version());

        int afterForget = sessions.version();
        sessions.forget(script("never-ran.js"));
        assertEquals("forgetting something absent moved the version", afterForget, sessions.version());
    }

    /**
     * The allocation-free queries answer exactly what the copying ones did.
     *
     * <p>Asserted against each other rather than against literals, because the risk is not that one of
     * them is wrong on its own — it is that the cheap one and the copying one drift, and then a control
     * greys on a different rule from the row beside it.</p>
     */
    @Test
    public void theCheapQueriesAgreeWithTheCopyingOnes() {
        RunSessions sessions = new RunSessions();
        assertEquals(sessions.scripts().isEmpty(), sessions.isEmpty());
        assertEquals(!sessions.active().isEmpty(), sessions.anyActive());
        assertNull(sessions.firstActive());

        sessions.set(script("done.java"), RunState.FINISHED);
        assertEquals(sessions.scripts().isEmpty(), sessions.isEmpty());
        assertEquals("a finished script is not active", !sessions.active().isEmpty(),
                sessions.anyActive());
        assertNull("a finished script was offered as the thing to stop", sessions.firstActive());

        sessions.set(script("live.java"), RunState.LIVE, 1);
        assertEquals(!sessions.active().isEmpty(), sessions.anyActive());
        assertEquals("firstActive disagreed with active()",
                sessions.active().get(0), sessions.firstActive());
    }

    /** A changed handler count IS a change — it is what the rail shows beside the state. */
    @Test
    public void aHandlerCountChangeIsAChange() {
        RunSessions sessions = new RunSessions();
        AtomicInteger changes = new AtomicInteger();
        sessions.set(script("tick.js"), RunState.LIVE, 1);
        sessions.onDidChange.connect(r -> changes.incrementAndGet());

        sessions.set(script("tick.js"), RunState.LIVE, 2);
        assertEquals(1, changes.get());
        assertEquals(2, sessions.sessionOf(script("tick.js")).handlers());
    }

    /**
     * <b>Active means "can still do something without being asked again"</b>, which is what the
     * indicator marks — and it is exactly the two states an exit code cannot express.
     */
    @Test
    public void onlyRunningAndLiveAreActive() {
        RunSessions sessions = new RunSessions();
        sessions.set(script("one.js"), RunState.RUNNING);
        sessions.set(script("two.js"), RunState.LIVE, 2);
        sessions.set(script("three.js"), RunState.COMPILED);
        sessions.set(script("four.js"), RunState.STOPPED);
        sessions.set(script("five.js"), RunState.FAILED);
        // FINISHED IS NOT ACTIVE, and it is the one most likely to be got wrong: it is the only
        // inactive state a script reaches by succeeding, so a rule written as "not an error" would
        // include it and leave the indicator marking scripts that ended normally minutes ago.
        sessions.set(script("six.js"), RunState.FINISHED);

        assertEquals(2, sessions.active().size());
        assertTrue(sessions.active().contains(script("one.js")));
        assertTrue(sessions.active().contains(script("two.js")));
        assertFalse(sessions.isActive(script("three.js")));
    }

    /**
     * <b>Stopping keeps the row; forgetting removes it.</b>
     *
     * <p>Not the same operation, and conflating them loses the thing M9.5 decided to keep: a stopped
     * script's transcript survives, and it still needs an owner in the rail for that transcript to be
     * attributable to anything.</p>
     */
    @Test
    public void stoppingKeepsTheRowAndForgettingDoesNot() {
        RunSessions sessions = new RunSessions();
        Resource script = script("one.js");

        sessions.set(script, RunState.STOPPED);
        assertEquals(RunState.STOPPED, sessions.stateOf(script));
        assertEquals(1, sessions.scripts().size());

        sessions.forget(script);
        assertNull(sessions.stateOf(script));
        assertTrue(sessions.scripts().isEmpty());
    }

    /**
     * <b>Newest first — the rail is read downward and the run you care about is the one that just
     * happened.</b>
     *
     * <p>Insertion order put it at the bottom, under everything already finished, and grew in the wrong
     * direction all session.</p>
     */
    @Test
    public void theNewestRunIsListedFirst() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
        RunSessions sessions = new RunSessions(clock::get);

        sessions.set(script("first.java"), RunState.FINISHED);
        clock.set(2_000L);
        sessions.set(script("second.java"), RunState.FINISHED);
        clock.set(3_000L);
        sessions.set(script("third.java"), RunState.LIVE, 1);

        assertEquals(java.util.List.of(script("third.java"), script("second.java"), script("first.java")),
                sessions.scripts());
    }

    /**
     * <b>And a re-run moves to the top, which reversing insertion order would not do.</b>
     *
     * <p>The case that decides the implementation. A script first run an hour ago and re-run just now is
     * the newest thing in the list, and its position in the map has not changed — only its clock has, and
     * only because a run beginning from a state that was not already active resets it.</p>
     */
    @Test
    public void aReRunMovesToTheTop() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
        RunSessions sessions = new RunSessions(clock::get);

        sessions.set(script("old.java"), RunState.FINISHED);
        clock.set(2_000L);
        sessions.set(script("new.java"), RunState.FINISHED);
        assertEquals("the newer one starts on top",
                script("new.java"), sessions.scripts().get(0));

        clock.set(3_000L);
        sessions.set(script("old.java"), RunState.RUNNING);
        assertEquals("re-running the older script did not bring it to the top",
                script("old.java"), sessions.scripts().get(0));
    }

    /**
     * <b>The clock is compared by subtraction, so a negative origin sorts correctly.</b>
     *
     * <p>{@code System.nanoTime()} has an arbitrary origin and may be negative — this class already
     * refuses a sentinel timestamp for that reason, and an ordinary {@code <} between two readings is
     * wrong across the point where it wraps.</p>
     */
    @Test
    public void orderingSurvivesANegativeClock() {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 1_000L);
        RunSessions sessions = new RunSessions(clock::get);

        sessions.set(script("before.java"), RunState.FINISHED);
        // Straight over the wrap, which is a real thing nanoTime does.
        clock.set(Long.MIN_VALUE + 1_000L);
        sessions.set(script("after.java"), RunState.FINISHED);

        assertEquals("the run after the wrap was sorted as though it were older",
                script("after.java"), sessions.scripts().get(0));
    }

    /** A script this workspace has never run has no state at all — not a default one. */
    @Test
    public void anUnknownScriptHasNoState() {
        RunSessions sessions = new RunSessions();
        assertNull(sessions.stateOf(script("never.js")));
        assertFalse(sessions.isActive(script("never.js")));
    }

    /** Forgetting something absent is not a change, so it does not wake the rail. */
    @Test
    public void forgettingAnAbsentScriptIsSilent() {
        RunSessions sessions = new RunSessions();
        AtomicInteger changes = new AtomicInteger();
        sessions.onDidChange.connect(r -> changes.incrementAndGet());
        sessions.forget(script("never.js"));
        assertEquals(0, changes.get());
    }

    /**
     * <b>A listener runs on whatever thread wrote the state — which is a SCRIPT thread.</b>
     *
     * <p>The premise the Stop button crash rested on, and the reason it is worth stating as a test rather
     * than a comment. {@code RunSessions} is written by the thread whose run just changed, and it emits
     * inline, so every {@code onDidChange} handler is off the UI thread by construction.</p>
     *
     * <p>{@code ScriptWorkbench} pushed the Stop button's enablement from one of these. {@code setEnabled}
     * ends in {@code invalidateStyleMatch()}, which added to {@code StyleEngine}'s dirty-match
     * {@code HashSet} while the UI thread was copying it — an {@code ArrayIndexOutOfBoundsException} out
     * of {@code HashMap.keysToArray}, thrown in {@code advanceFrame} with nothing about the Run panel
     * anywhere in the trace. Nothing about the listener looked threaded, which is exactly the problem.</p>
     *
     * <p>So the rule is <b>pull, not push</b>: {@code RunPanel.refreshActions} recomputes enablement every
     * frame from this same object, on the right thread, and a per-frame reader cannot race the frame it
     * reads in. Anything that genuinely must push has to hop through {@code JobScheduler}, whose
     * {@code onDone} is documented to run during the frame — which is what {@code RunIndicators} does.</p>
     */
    @Test
    public void aListenerRunsOnTheThreadThatWroteTheState() throws Exception {
        RunSessions sessions = new RunSessions();
        AtomicReference<Thread> ran = new AtomicReference<>();
        sessions.onDidChange.connect(r -> ran.set(Thread.currentThread()));

        Thread scriptThread = new Thread(() -> sessions.set(script("Main.java"), RunState.RUNNING, 0),
                "script-Main.java");
        scriptThread.start();
        scriptThread.join();

        assertSame("the emit is inline, so a listener is as off-thread as the writer",
                scriptThread, ran.get());
        assertNotSame("and it is never the thread that would be painting", Thread.currentThread(), ran.get());
    }
}
