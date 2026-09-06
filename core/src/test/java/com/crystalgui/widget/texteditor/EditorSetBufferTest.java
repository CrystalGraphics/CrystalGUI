package com.crystalgui.widget.texteditor;

import com.crystalgui.text.TextBuffer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/fs-rewrite.md} F1.6, D4 — <b>an editor is a view of a document, not the document</b>.
 *
 * <p>It was constructed around one buffer it built itself and could never be pointed at another, which
 * is why the workbench's store held a {@code TextEditor} per file rather than a document per file: the
 * widget WAS the document. Two panes onto one file were two buffers, two undo stacks and two parse
 * trees over the same bytes, and neither knew about the other.</p>
 */
public class EditorSetBufferTest extends EditorTestBase {

    @Test
    public void showingAnotherBufferShowsItsText() {
        build("first\n");
        TextBuffer second = new TextBuffer("second\nand more\n");

        editor.setBuffer(second);
        settle();

        assertSame(second, editor.buffer());
        assertEquals("second\nand more\n", editor.getText());
    }

    /**
     * <b>The subscription is dropped.</b> A view still listening to the document it used to show
     * reprojects, re-measures and re-tokenises on every keystroke somebody makes in another tab — and
     * on a shared buffer it would clamp that tab's carets too.
     */
    @Test
    public void editingTheBufferItLeftDoesNotReachIt() {
        build("first\n");
        TextBuffer first = editor.buffer();
        TextBuffer second = new TextBuffer("second\n");

        editor.setBuffer(second);
        settle();
        first.insert(0, "typed into the OLD document ");
        settle();

        assertEquals("second\n", editor.getText());
    }

    /** And the new one is live. */
    @Test
    public void editingTheBufferItMovedToDoesReachIt() {
        build("first\n");
        TextBuffer second = new TextBuffer("second\n");
        editor.setBuffer(second);
        settle();

        second.insert(0, "x");
        settle();

        assertEquals("xsecond\n", editor.getText());
    }

    /**
     * View state describes the document being LEFT.
     *
     * <p>A caret at offset 4000 in a file of 300 characters is not a caret. The clamp elsewhere would
     * catch the crash; it would not stop the caret landing somewhere nobody put it.
     */
    @Test
    public void viewStateIsResetAndTheCaretDoesNotPointPastTheNewDocument() {
        build("a long first document with plenty of room\n");
        editor.setCaret(editor.getText().length());
        settle();
        assertTrue(editor.getCaret() > 5);

        editor.setBuffer(new TextBuffer("hi\n"));
        settle();

        assertEquals(0, editor.getCaret());
        assertEquals(0f, editor.scrollTop(), 0.001f);
    }

    /** The history is the document's, so it travels with it rather than with the view. */
    @Test
    public void theUndoStackIsTheBuffersAndFollowsIt() {
        build("first\n");
        TextBuffer second = new TextBuffer("second\n");

        editor.setBuffer(second);
        settle();

        assertSame(second.history(), editor.undoStack());
        second.insert(0, "x");
        assertTrue(editor.undoStack().canUndo());
    }

    /**
     * The model is not the view's to dispose.
     *
     * <p>Swapping away drops the subscriptions and nothing else: the document may still be open in
     * another pane, in the Problems panel, or in an index. Its lifetime is a reference count's.
     */
    @Test
    public void swappingAwayDoesNotDisposeTheDocument() {
        build("first\n");
        TextBuffer first = editor.buffer();

        editor.setBuffer(new TextBuffer("second\n"));
        settle();

        first.insert(0, "still usable ");
        assertEquals("still usable first\n", first.toString());
        assertTrue(first.history().canUndo());
    }

    @Test
    public void settingTheSameBufferIsANoOp() {
        build("first\n");
        TextBuffer same = editor.buffer();
        editor.setCaret(3);
        settle();

        editor.setBuffer(same);

        assertEquals("a no-op must not reset the caret", 3, editor.getCaret());
    }

    /** Two views over one document share the model, and each keeps its own caret. */
    @Test
    public void twoEditorsOverOneBufferShareTheDocumentAndNotTheCaret() {
        build("shared text here\n");
        TextBuffer shared = editor.buffer();
        TextEditor other = new TextEditor("");
        other.layout(l -> l.width(300).height(120));
        document.append(other);
        other.setBuffer(shared);
        settle();

        editor.setCaret(3);
        other.setCaret(9);
        settle();

        assertSame(shared, other.buffer());
        assertNotSame("the caret is the VIEW's", editor.getCaret(), other.getCaret());

        shared.insert(0, "x");
        settle();
        assertEquals("both see the edit", editor.getText(), other.getText());
    }
}
