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
