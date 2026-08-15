package com.crystalgui.language.run;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.6 — turning a frame's bare {@code RunTest.java} into a file to open.
 *
 * <p>The half of a click that can be tested without a window, and the half where a failure is silent: a
 * link that resolves to nothing simply does not navigate, which is indistinguishable from the click never
 * having been detected. That ambiguity cost a round of diagnosis, so it is pinned here.</p>
 */
public class LinkResolutionTest {

    private static Resource script(String name) {
        return Resource.of(CgPath.of("workspace", "src/" + name));
    }

    private static ConsoleFilter.Link link(String fileName, int line) {
        return new ConsoleFilter.Link(0, 5, fileName, line);
    }

    private static RunConsole.Line plainLine() {
        RunConsole console = new RunConsole().attach(new com.crystalgui.text.TextBuffer());
        console.append(RunMessage.of("RunTest.java", RunLevel.ERROR, "\tat X.y(RunTest.java:311)"));
        console.drain();
        return console.lineAt(0);
    }

    /**
     * <b>The case a real stack trace is in.</b>
     *
     * <p>{@code printStackTrace} output reaches the console through the stderr route, so those lines carry
     * no origin {@code Resource} — {@link RunMessage#of} leaves it null. Resolution therefore rests
     * entirely on the session list, and if that were the only candidate consulted the whole feature would
     * work for a trace and for nothing else, or the reverse.</p>
     */
    @Test
    public void aFrameResolvesAgainstTheKnownScriptsWhenTheLineHasNoOrigin() {
        RunSessions sessions = new RunSessions();
        Resource runTest = script("RunTest.java");
        sessions.set(runTest, RunState.RUNNING);

        RunConsole.Line line = plainLine();
        assertNull("the fixture must have no origin, or this tests the wrong branch", line.file());

        assertEquals(runTest, RunPanels.resolve(line, link("RunTest.java", 311), sessions));
    }

    /**
     * <b>A FINISHED script is still resolvable.</b>
     *
     * <p>The transcript outlives the run — that is 9.5's first decision — so every frame in it would stop
     * navigating the moment the script ended if resolution only consulted live sessions. The most useful
     * trace is the one from the run that just died.</p>
     */
    @Test
    public void aFinishedScriptStillResolves() {
        RunSessions sessions = new RunSessions();
        Resource runTest = script("RunTest.java");
        sessions.set(runTest, RunState.RUNNING);
        sessions.set(runTest, RunState.FINISHED);

        assertEquals(runTest, RunPanels.resolve(plainLine(), link("RunTest.java", 311), sessions));
    }

    /** The line's own origin is preferred, and is the cheap answer for output the script printed itself. */
    @Test
    public void theLinesOwnOriginWinsWhenItMatches() {
        RunSessions sessions = new RunSessions();
        Resource other = script("Other.java");
        sessions.set(other, RunState.RUNNING);

        RunConsole console = new RunConsole().attach(new com.crystalgui.text.TextBuffer());
        Resource origin = script("Main.java");
        console.append(RunMessage.at("Main.java", origin, 12, RunLevel.OUT, "at X.y(Main.java:12)"));
        console.drain();

        assertEquals(origin, RunPanels.resolve(console.lineAt(0), link("Main.java", 12), sessions));
    }

    /**
     * <b>A JDK frame resolves to nothing, and that is the answer.</b>
     *
     * <p>Most frames in a real trace are platform or engine code. Guessing — opening any workspace file
     * with a matching name — would navigate confidently to the wrong place, which is worse than a frame
     * that does not move. IntelliJ links only what is in the project for the same reason.</p>
     */
    @Test
    public void aFrameNamingSomethingUnknownResolvesToNothing() {
        RunSessions sessions = new RunSessions();
        sessions.set(script("RunTest.java"), RunState.RUNNING);

        assertNull(RunPanels.resolve(plainLine(), link("Thread.java", 1583), sessions));
        assertNull(RunPanels.resolve(plainLine(), link("Method.java", 580), sessions));
    }

    /** Nothing has run yet: no origin and no sessions is not an exception. */
    @Test
    public void nothingKnownResolvesToNothing() {
        assertNull(RunPanels.resolve(plainLine(), link("RunTest.java", 1), new RunSessions()));
    }

    /**
     * <b>The resolved resource must be openable</b> — the caller refuses anything that is not
     * {@code isProject()}, and a resource built from a scheme and a string carries no path at all.
     */
    @Test
    public void theResolvedResourceCarriesAPath() {
        RunSessions sessions = new RunSessions();
        sessions.set(script("RunTest.java"), RunState.RUNNING);

        Resource found = RunPanels.resolve(plainLine(), link("RunTest.java", 311), sessions);
        assertNotNull(found);
        assertTrue("a resource with no path is silently skipped by the caller", found.isProject());
        assertNotNull(found.asPath());
    }
}
