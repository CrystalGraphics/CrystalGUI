package com.crystalgui.widget.texteditor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** An input method's run in progress: shown in the document, underlined, and replaced by its commit. */
public class EditorCompositionTest extends EditorTestBase {

    @Test
    public void theRunIsShownUnderlinedAndTheCommitReplacesIt() {
        build("ab");
        editor.setCaret(2);
        assertTrue(input.consumeComposition("に", 1));
        input.consumeComposition("にほ", 2);
        settle();
        assertEquals("abにほ", editor.getText());
        assertEquals(4, editor.getCaret());
        assertTrue("the run is published for the underline",
                lineHasHighlight(0, TextEditor.COMPOSITION_HIGHLIGHT));

        type("日本");
        settle();
        assertEquals("ab日本", editor.getText());
        assertFalse(lineHasHighlight(0, TextEditor.COMPOSITION_HIGHLIGHT));
        input.consumeComposition("", 0);
        assertEquals("ab日本", editor.getText());
    }

    @Test
    public void anEndBeforeTheCommitStillLeavesOnlyTheCommit() {
        build("");
        input.consumeComposition("にほ", 2);
        input.consumeComposition("", 0);
        assertEquals("", editor.getText());
        type("日本");
        assertEquals("日本", editor.getText());
    }

    @Test
    public void aCompositionReplacesTheSelection() {
        build("abc");
        editor.setSelection(0, 3);
        input.consumeComposition("に", 1);
        assertEquals("に", editor.getText());
    }
}
