package com.crystalgui.language.run;

import com.crystalgui.language.run.console.RunSummary;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RunSummary} — the line that closes a run.
 *
 * <p>The console opened a run with a divider and closed it with nothing, which is only obviously wrong
 * once you look for the end of one script's output with another script's interleaved above it. The rules
 * worth pinning are which states get a line at all and which preposition each one takes; both are
 * decisions rather than derivations, and both read as trivia until they are wrong.</p>
 */
public class RunSummaryTest {

    private static long seconds(long value) {
        return TimeUnit.SECONDS.toNanos(value);
    }

    /** A one-shot that ran to completion lasted its duration — "in". */
    @Test
    public void aFinishedRunSaysHowLongItTook() {
        assertEquals("Main.java finished in 3 sec",
                RunSummary.of("Main.java", RunState.FINISHED, seconds(3)));
    }

    /**
     * <b>An interrupted run says "after", not "in".</b>
     *
     * <p>Not decoration. "finished in 3 sec" reports a duration; "stopped after 3 sec" reports how much
     * of one there was before somebody intervened. English carries the distinction for free, and the
     * alternative — one preposition for every state — quietly claims a killed script completed.</p>
     */
    @Test
    public void anInterruptedRunSaysHowFarItGot() {
        assertEquals("Main.java stopped after 3 sec",
                RunSummary.of("Main.java", RunState.STOPPED, seconds(3)));
        assertEquals("Main.java failed after 3 sec",
                RunSummary.of("Main.java", RunState.FAILED, seconds(3)));
    }

    /**
     * <b>A LIVE script gets no closing line, and this is the case the whole class exists around.</b>
     *
     * <p>A script that registered handlers and returned has not ended — it is sitting there waiting to
     * fire, which is exactly what {@link RunState#LIVE} was named to say. A boundary drawn under it would
     * claim the run was over, and the next handler's output would appear below the line that said so.</p>
     */
    @Test
    public void aRunThatHasNotEndedGetsNoLine() {
        assertNull("a live script was reported as finished",
                RunSummary.of("Main.java", RunState.LIVE, seconds(3)));
        assertNull("a running script was reported as finished",
                RunSummary.of("Main.java", RunState.RUNNING, seconds(3)));
        assertNull("compiling is not a run at all",
                RunSummary.of("Main.java", RunState.COMPILED, 0));
        assertNull(RunSummary.of("Main.java", null, 0));
    }

    /** Sub-second runs are the common case, and "0 sec" would read as "did not start". */
    @Test
    public void aVeryFastRunStillReadsAsHavingRun() {
        String line = RunSummary.of("Main.java", RunState.FINISHED, 1234);
        assertEquals("Main.java finished in <1 sec", line);
    }

    /** A run with no name still produces a line rather than one beginning with a blank. */
    @Test
    public void anUnnamedRunIsStillDescribed() {
        assertTrue(RunSummary.of(null, RunState.FINISHED, seconds(1)).startsWith("Script finished"));
        assertTrue(RunSummary.of("", RunState.FAILED, seconds(1)).startsWith("Script failed"));
    }
}
