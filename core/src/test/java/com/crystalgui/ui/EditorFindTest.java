package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Find and replace — the query, the matches, stepping between them, and the keys that drive it.
 *
 * <p>Sits beside {@code EditorFind}. The two ways of selecting a match are the thing worth testing here
 * and they are easy to conflate: stepping anchors on the caret and centres what it had to scroll to,
 * while a freshly typed query anchors on the first visible line and must not move the document.</p>
 */
public class EditorFindTest extends EditorTestBase {

    // ── Find and replace ─────────────────────────────────────────

    @Test
    public void findReportsEveryMatch() {
        build("cat dog cat bird cat");
        assertEquals(3, editor.find("cat", true));
        assertEquals(3, editor.matchCount());
    }

    @Test
    public void findIsCaseInsensitiveWhenAsked() {
        build("Cat cat CAT");
        assertEquals(1, editor.find("cat", true));
        assertEquals(3, editor.find("cat", false));
    }

    /** Overlapping hits are separate hits — "aa" occurs twice in "aaa", as every editor reports. */
    @Test
    public void overlappingMatchesAreCountedSeparately() {
        build("aaa");
        assertEquals(2, editor.find("aa", true));
    }

    @Test
    public void findNextWalksTheMatchesAndWraps() {
        build("cat dog cat");
        editor.find("cat", true);
        editor.setCaret(0);

        assertTrue(editor.findNext());
        assertEquals(8, editor.getSelectionStart());
        assertTrue("and wraps back to the first", editor.findNext());
        assertEquals(0, editor.getSelectionStart());
    }

    @Test
    public void replaceCurrentReplacesTheSelectedMatch() {
        build("cat dog cat");
        editor.find("cat", true);
        editor.setCaret(0);
        editor.findNext();

        assertTrue(editor.replaceCurrent("fish"));
        assertEquals("cat dog fish", editor.getText());
    }

    /**
     * <b>Replace-all is ONE edit.</b> A loop of replacements would invalidate every later offset after
     * the first, and would take one undo press per match to reverse.
     */
    @Test
    public void replaceAllIsASingleUndoStep() {
        build("cat dog cat bird cat");
        editor.find("cat", true);

        assertEquals(3, editor.replaceAll("fish"));
        assertEquals("fish dog fish bird fish", editor.getText());

        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("one undo reverses the lot", "cat dog cat bird cat", editor.getText());
    }

    @Test
    public void replacingWithALongerStringKeepsLaterMatchesCorrect() {
        build("a a a");
        editor.find("a", true);
        editor.replaceAll("xyz");
        assertEquals("xyz xyz xyz", editor.getText());
    }

    @Test
    public void searchHitsAreHighlighted() {
        build("find me here");
        editor.find("me", true);
        settle();
        editor.updateWindow();
        settle();

        assertTrue(lineHasHighlight(0, "search"));
    }

    // ── 6.1.7b: search keys ──────────────────────────────────────

    @Test
    public void f3WalksTheMatches() {
        build("cat dog cat");
        editor.find("cat", true);
        editor.setCaret(0);

        key(CgKeyCodes.KEY_F3);
        assertEquals(8, editor.getSelectionStart());
        key(CgKeyCodes.KEY_F3, CgModifiers.SHIFT);
        assertEquals(0, editor.getSelectionStart());
    }

    @Test
    public void ctrlF3SearchesTheWordUnderTheCaret() {
        build("alpha beta alpha");
        editor.setCaret(1);

        key(CgKeyCodes.KEY_F3, CgModifiers.CTRL);

        assertEquals(2, editor.matchCount());
        assertEquals("alpha", editor.getSelectedText());
    }
}
