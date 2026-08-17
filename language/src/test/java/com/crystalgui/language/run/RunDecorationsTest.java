package com.crystalgui.language.run;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.view.RunDecorations;
import com.crystalgui.ui.elements.workbench.decoration.FileDecoration;
import org.junit.Test;

import static org.junit.Assert.*;

/** M9.5 §9.5.5 — the running indicator, and how it composes with the marks already on a row. */
public class RunDecorationsTest {

    /**
     * Built FROM A CgPath, which is the only way a resource carries one.
     *
     * <p>{@code Resource.of("project", "src/one.js")} looks equivalent, compares equal, and answers null
     * to {@code asPath()} — so a decoration provider, which is {@code CgPath}-keyed, would never see it.
     * Worth spelling out here because the first version of this test did it the other way and every
     * assertion failed with "nothing decorated", which reads as the provider being broken.</p>
     */
    private static Resource script(String name) {
        return Resource.of(path(name));
    }

    private static CgPath path(String name) {
        return CgPath.of("workspace", "src/" + name);
    }

    /** A live script is marked; one that merely compiled is not. */
    @Test
    public void onlyActiveScriptsAreMarked() {
        RunSessions sessions = new RunSessions();
        RunDecorations decorations = new RunDecorations(sessions);

        assertNull("never run", decorations.decorationFor(path("one.js")));

        sessions.set(script("one.js"), RunState.RUNNING);
        assertNotNull(decorations.decorationFor(path("one.js")));

        sessions.set(script("one.js"), RunState.COMPILED);
        assertNull("compiled is not running", decorations.decorationFor(path("one.js")));
    }

    /**
     * <b>A script that ended stops being marked</b> — including the one that ended by succeeding.
     *
     * <p>The rule most likely to be written as "has been run", which would leave every script anybody
     * tried marked for the rest of the session. An indicator that marks everything says nothing.</p>
     */
    @Test
    public void everyEndedStateIsUnmarked() {
        RunSessions sessions = new RunSessions();
        RunDecorations decorations = new RunDecorations(sessions);

        for (RunState ended : new RunState[]{RunState.FINISHED, RunState.STOPPED, RunState.FAILED}) {
            sessions.set(script("one.js"), ended);
            assertNull(ended + " should not be marked", decorations.decorationFor(path("one.js")));
        }
    }

    /**
     * <b>It states a colour and never a letter</b>, so the dirty mark survives beside it.
     *
     * <p>The whole reason this composes rather than competes. A file that is edited and running at the
     * same time is the case that matters most — its text is no longer what is running — and claiming the
     * letter would hide the unsaved edit to say something the colour already says.</p>
     */
    @Test
    public void itClaimsTheColourAndLeavesTheLetterAlone() {
        RunSessions sessions = new RunSessions();
        sessions.set(script("one.js"), RunState.RUNNING);
        FileDecoration decoration = new RunDecorations(sessions).decorationFor(path("one.js"));

        assertEquals(RunDecorations.RUNNING_CLASS, decoration.styleClass());
        assertNull("the letter belongs to whoever says the file is modified", decoration.letter());
        assertTrue("a folder should show that something under it is live", decoration.bubble());
    }

    /**
     * Weighted so it out-states the dirty colour and loses to a problem.
     *
     * <p>Both halves matter: below modified and the indicator never shows on a file being edited, which
     * is most of them; above warning and a file with an error reads as fine because it happens to be
     * running.</p>
     */
    @Test
    public void itOutweighsModifiedAndLosesToAProblem() {
        assertTrue(RunDecorations.WEIGHT_RUNNING > FileDecoration.WEIGHT_MODIFIED);
        assertTrue(RunDecorations.WEIGHT_RUNNING < FileDecoration.WEIGHT_WARNING);
        assertTrue(RunDecorations.WEIGHT_RUNNING < FileDecoration.WEIGHT_ERROR);
    }

    /** The tooltip carries the handler count, which is the only thing that says it will fire again. */
    @Test
    public void theTooltipNamesWhatLiveMeans() {
        RunSessions sessions = new RunSessions();
        RunDecorations decorations = new RunDecorations(sessions);

        sessions.set(script("one.js"), RunState.RUNNING);
        assertEquals("Running", decorations.decorationFor(path("one.js")).tooltip());

        sessions.set(script("one.js"), RunState.LIVE, 1);
        assertTrue(decorations.decorationFor(path("one.js")).tooltip().contains("1 handler"));

        sessions.set(script("one.js"), RunState.LIVE, 3);
        assertTrue(decorations.decorationFor(path("one.js")).tooltip().contains("3 handlers"));
    }

    /** Folder bubbling only walks the live ones, which is what keeps it cheap per row. */
    @Test
    public void onlyActiveScriptsAreEnumeratedForBubbling() {
        RunSessions sessions = new RunSessions();
        RunDecorations decorations = new RunDecorations(sessions);

        sessions.set(script("live.js"), RunState.LIVE, 2);
        sessions.set(script("done.js"), RunState.FINISHED);
        sessions.set(script("broke.js"), RunState.FAILED);

        assertEquals(1, decorations.decorated().size());
        assertTrue(decorations.decorated().contains(path("live.js")));
    }
}
