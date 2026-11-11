package com.crystalgui.screen;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.ui.dom.UIElement;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The desktop on a sequence: every entry of the screen still works from the host's thread, while the tree refuses that
 * thread outside them.
 */
public class CgUiScreenLockstepTest {

    @After
    public void tearDown() {
        CgUiScreen.dispose();
        System.clearProperty("crystalgui.ui.sequence");
        System.clearProperty("crystalgui.ui.async");
    }

    private static CgSystemInput.Keyboard.Event f12() {
        return new CgSystemInput.Keyboard.Event((char) 0, CgKeyCodes.KEY_F12, true, false, 0L);
    }

    /** Sequenced, no entry reads the tree on the host's thread: each answers from the last commit or posts. */
    @Test
    public void sequencedNoEntryReadsTheTree() {
        System.setProperty("crystalgui.ui.async", "true");
        CgUiScreen.install();
        CgUiScreen.shown();
        UiSequence sequence = CgUiScreen.viewport().ask(UiSequence::current);
        assertNotNull(sequence);

        CgUiScreen.tick();
        assertNotNull(CgUiScreen.mode());
        assertNull("no commit yet, so no caret to place", CgUiScreen.textInputArea());
        assertTrue("a key to a document recording on its own is answered ours at once",
                CgUiScreen.keyboard().consumeKeyboardEvent(f12()));
        CgUiScreen.hidden();
        CgUiScreen.shown();
        sequence.runNow(() -> { });
    }

    @Test
    public void inLockstepEveryEntryRunsOnTheSequenceAndNothingElseReachesTheTree() {
        System.setProperty("crystalgui.ui.async", "false");
        System.setProperty("crystalgui.ui.sequence", "true");
        CgUiScreen.install();
        CgUiScreen.shown();
        UiSequence sequence = CgUiScreen.viewport().ask(UiSequence::current);
        assertNotNull("the desktop's document was not given a sequence", sequence);

        CgUiScreen.tick();
        assertNotNull(CgUiScreen.mode());
        assertFalse(CgUiScreen.keyboard().consumeKeyboardEvent(f12()));
        assertNull(CgUiScreen.textInputArea());
        CgUiScreen.hidden();
        CgUiScreen.shown();

        try {
            CgUiScreen.document().append(new UIElement());
            fail("the host's thread reached the tree outside the sequence");
        } catch (IllegalStateException expected) {
            // what the lockstep exists to make visible
        }
    }
}
