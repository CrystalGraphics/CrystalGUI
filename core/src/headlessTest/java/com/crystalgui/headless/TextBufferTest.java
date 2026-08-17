package com.crystalgui.headless;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Selection;
import com.crystalgui.text.TextBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * P6.1.6 — the document buffer and its undo history.
 *
 * <h3>The undo tests drive a clock</h3>
 * <p>Coalescing is time-dependent, and {@link TextBuffer#setClock} exists so a test can decide what time
 * it is rather than sleeping or asserting around the behaviour. The engine already has one component that
 * got this wrong — {@code TransitionEngine} reads {@code System.nanoTime()} directly, so no test can step
 * it and its suite asserts inputs instead of results. Not repeating that is the reason for the seam.</p>
 */
public class TextBufferTest {

    private long now = 1_000L;

    private TextBuffer buffer(String text) {
        TextBuffer buffer = new TextBuffer(text);
        buffer.setClock(() -> now);
        return buffer;
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void editsTheDocument() {
        TextBuffer buffer = buffer("hello world");
        buffer.insert(5, ",");
        assertEquals("hello, world", buffer.toString());
        buffer.delete(0, 6);
        assertEquals(" world", buffer.toString());
    }

    @Test
    public void anEditDescribedAgainstADifferentDocumentIsRefused() {
        TextBuffer buffer = buffer("hello");
        try {
            buffer.edit(ChangeSet.replace(999, 0, 1, "x"));
            fail("a change set carries the length it was described against; a mismatch is a bug upstream");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("length"));
        }
    }

    @Test
    public void everyAppliedEditIsAnnounced() {
        TextBuffer buffer = buffer("abc");
        List<Integer> lengths = new ArrayList<>();
        buffer.onChanged.connect(change -> lengths.add(change.lengthAfter()));

        buffer.insert(3, "def");
        buffer.undo();
        buffer.redo();

        assertEquals("undo and redo are edits too, and a view must hear about them",
                List.of(6, 3, 6), lengths);
    }

    // ── The carets an undo restores ─────────────────────────────────────────────────────────────

    /**
     * <b>Undo puts the carets back where the edit was made</b>, and redo puts them where it left them.
     *
     * <p>Reported from the harness as "undo/redo don't put the caret back". The redo half is derived
     * rather than recorded — the same carets carried through the change — so the two cannot disagree.
     */
    @Test
    public void undoAndRedoRestoreTheCaretsTheEditWasMadeAt() {
        TextBuffer buffer = buffer("hello");
        List<List<Selection>> restored = new ArrayList<>();
        buffer.onSelectionsRestored.connect(restored::add);

        buffer.edit(ChangeSet.replace(5, 5, 5, " world"), List.of(Selection.caret(5)));
        buffer.undo();
        assertEquals("undo answers with where the caret stood before the edit",
                List.of(List.of(Selection.caret(5))), restored);

        buffer.redo();
        assertEquals("redo answers with where that caret ended up", 2, restored.size());
        assertEquals(Selection.caret(11), restored.get(1).get(0));
    }

    /** A view that records none still undoes; nothing is announced, and nothing breaks. */
    @Test
    public void anEditWithNoRecordedCaretsAnnouncesNothing() {
        TextBuffer buffer = buffer("hello");
        List<List<Selection>> restored = new ArrayList<>();
        buffer.onSelectionsRestored.connect(restored::add);

        buffer.insert(5, "!");
        buffer.undo();

        assertEquals("hello", buffer.toString());
        assertTrue(restored.isEmpty());
    }

    /**
     * <b>A merged run of typing undoes to where the run STARTED.</b> Taking the later entry's carets
     * would land the caret in the middle of text the undo has just removed.
     */
    @Test
    public void aMergedRunKeepsTheFirstStepsCarets() {
        TextBuffer buffer = buffer("");
        List<List<Selection>> restored = new ArrayList<>();
        buffer.onSelectionsRestored.connect(restored::add);

        buffer.edit(ChangeSet.replace(0, 0, 0, "a"), List.of(Selection.caret(0)));
        buffer.edit(ChangeSet.replace(1, 1, 1, "b"), List.of(Selection.caret(1)));
        buffer.undo();

        assertEquals("", buffer.toString());
        assertEquals(List.of(List.of(Selection.caret(0))), restored);
    }

    // ── Undo ────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void undoRestoresTheDocumentAndRedoReappliesIt() {
        TextBuffer buffer = buffer("hello");
        buffer.replace(0, 5, "goodbye");
        assertEquals("goodbye", buffer.toString());

        assertTrue(buffer.undo());
        assertEquals("hello", buffer.toString());
        assertTrue(buffer.redo());
        assertEquals("goodbye", buffer.toString());
    }

    @Test
    public void undoAndRedoReportWhenThereIsNothingToDo() {
        TextBuffer buffer = buffer("x");
        assertFalse(buffer.undo());
        assertFalse(buffer.redo());
        assertFalse(buffer.canUndo());
    }

    /**
     * <b>A new edit discards the redo branch.</b> Keeping it would leave a change described against a
     * document that no longer exists anywhere in the history — the length check would eventually catch it,
     * but at whatever unrelated moment the user next pressed redo.
     */
    @Test
    public void editingAfterUndoDiscardsTheRedoBranch() {
        TextBuffer buffer = buffer("a");
        buffer.insert(1, "b");
        buffer.undo();
        assertTrue(buffer.canRedo());

        buffer.insert(1, "c");
        assertFalse("the branch that redo would have replayed no longer exists", buffer.canRedo());
        assertEquals("ac", buffer.toString());
    }

    /** Many edits undone all the way back, one at a time. */
    @Test
    public void unwindingTheWholeHistoryReturnsTheOriginal() {
        TextBuffer buffer = buffer("start");
        buffer.setCoalesceWindowMillis(0);
        Random random = new Random(8080L);

        for (int i = 0; i < 60; i++) {
            now += 1000;
            int from = random.nextInt(buffer.length() + 1);
            int to = Math.min(buffer.length(), from + random.nextInt(4));
            buffer.replace(from, to, random.nextBoolean() ? "z" : "");
        }
        while (buffer.undo()) { /* all the way back */ }
        assertEquals("start", buffer.toString());
    }

    // ── Coalescing ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void aRunOfTypingIsOneUndoStep() {
        TextBuffer buffer = buffer("");
        for (char c : "hello".toCharArray()) {
            now += 50;
            buffer.insert(buffer.length(), String.valueOf(c));
        }
        assertEquals("hello", buffer.toString());
        assertEquals("five keystrokes, one step", 1, buffer.undoDepth());

        buffer.undo();
        assertEquals("", buffer.toString());
    }

    @Test
    public void aPauseStartsANewUndoStep() {
        TextBuffer buffer = buffer("");
        buffer.insert(0, "a");
        now += TextBuffer.DEFAULT_COALESCE_WINDOW_MILLIS + 1;
        buffer.insert(1, "b");

        assertEquals(2, buffer.undoDepth());
        buffer.undo();
        assertEquals("a", buffer.toString());
    }

    /**
     * <b>Deleting does not join a run of typing.</b> Merged, one undo would both restore what was deleted
     * and remove what was typed — a single step that moves the document in two directions at once, which
     * no user has ever asked for.
     */
    @Test
    public void backspaceEndsARunOfTyping() {
        TextBuffer buffer = buffer("");
        buffer.insert(0, "a");
        now += 10;
        buffer.insert(1, "b");
        now += 10;
        buffer.delete(1, 2);

        assertEquals("a", buffer.toString());
        assertEquals("typing is one step, deleting is another", 2, buffer.undoDepth());
        buffer.undo();
        assertEquals("ab", buffer.toString());
    }

    @Test
    public void aRunOfBackspacesIsOneStep() {
        TextBuffer buffer = buffer("hello");
        for (int i = 0; i < 3; i++) {
            now += 20;
            buffer.delete(buffer.length() - 1, buffer.length());
        }
        assertEquals("he", buffer.toString());
        assertEquals(1, buffer.undoDepth());
        buffer.undo();
        assertEquals("hello", buffer.toString());
    }

    /**
     * <b>Typing somewhere else is a separate step even within the time window.</b> Contiguity is what
     * makes a run a run; without it, a click and a keystroke elsewhere would silently join whatever was
     * typed a moment earlier.
     */
    @Test
    public void typingAtAnotherPlaceStartsANewStep() {
        TextBuffer buffer = buffer("one two");
        buffer.insert(3, "X");
        now += 10;
        buffer.insert(0, "Y");

        assertEquals(2, buffer.undoDepth());
    }

    /** A newline is a boundary users can see, so undo works in lines rather than through them. */
    @Test
    public void aNewlineBreaksTheRun() {
        TextBuffer buffer = buffer("");
        buffer.insert(0, "a");
        now += 10;
        buffer.insert(1, "\n");
        now += 10;
        buffer.insert(2, "b");

        assertTrue("the newline is its own step", buffer.undoDepth() >= 2);
        buffer.undo();
        assertFalse("and undoing does not take the earlier line with it", buffer.toString().isEmpty());
    }

    /**
     * The view breaks the run explicitly for the things the buffer cannot see — a deliberate caret move,
     * a selection change, a save.
     */
    @Test
    public void theViewCanEndAnUndoStepItself() {
        TextBuffer buffer = buffer("");
        buffer.insert(0, "a");
        buffer.breakUndoCoalescing();
        now += 10;
        buffer.insert(1, "b");

        assertEquals(2, buffer.undoDepth());
    }

    /**
     * <b>A coalesced step still undoes exactly.</b> The composed inverse has to run the new edit's
     * reversal first — the one expressed against the document we are actually in. Getting that order
     * backwards produces an inverse that applies cleanly and leaves the wrong text, which no length check
     * would catch.
     */
    @Test
    public void aCoalescedStepUndoesToExactlyTheRightText() {
        TextBuffer buffer = buffer("int x = ;");
        int caret = 8;
        for (char c : "42".toCharArray()) {
            now += 20;
            buffer.insert(caret++, String.valueOf(c));
        }
        assertEquals("int x = 42;", buffer.toString());
        assertEquals(1, buffer.undoDepth());

        buffer.undo();
        assertEquals("int x = ;", buffer.toString());
        buffer.redo();
        assertEquals("int x = 42;", buffer.toString());
    }

    /** Undo/redo must survive being driven hard, not just once. */
    @Test
    public void undoAndRedoAreStableUnderRepetition() {
        TextBuffer buffer = buffer("base");
        buffer.setCoalesceWindowMillis(0);
        for (int i = 0; i < 20; i++) {
            now += 1000;
            buffer.insert(buffer.length(), "." + i);
        }
        String full = buffer.toString();

        for (int round = 0; round < 5; round++) {
            while (buffer.undo()) { /* unwind */ }
            assertEquals("round " + round, "base", buffer.toString());
            while (buffer.redo()) { /* rewind */ }
            assertEquals("round " + round, full, buffer.toString());
        }
    }

    // ── The version spine ───────────────────────────────────────────────────────────────────────

    @Test
    public void everyEditAdvancesTheVersion() {
        TextBuffer buffer = buffer("");
        int start = buffer.version();

        buffer.insert(0, "a");
        buffer.insert(1, "b");

        assertEquals("each applied edit is one version", start + 2, buffer.version());
    }

    @Test
    public void anEmptyOrRejectedEditDoesNotAdvanceTheVersion() {
        TextBuffer buffer = buffer("hello");
        int before = buffer.version();

        buffer.edit(ChangeSet.empty(buffer.length()));

        assertEquals("a no-op must not invalidate everything computed against the document",
                before, buffer.version());
    }

    @Test
    public void undoAndRedoAdvanceTheVersionToo() {
        // The one that matters. Undo moves the text as surely as typing does, so anything computed
        // against the document beforehand is equally stale -- a version that only advanced on forward
        // edits would let a diagnostic list survive Ctrl+Z and be re-attached to text it never described.
        TextBuffer buffer = buffer("");
        buffer.setCoalesceWindowMillis(0);
        now += 1000;
        buffer.insert(0, "typed");

        int afterTyping = buffer.version();
        buffer.undo();
        assertTrue("undo is a change", buffer.version() > afterTyping);

        int afterUndo = buffer.version();
        buffer.redo();
        assertTrue("so is redo", buffer.version() > afterUndo);
    }

    @Test
    public void theVersionNeverRepeatsWhenTheTextReturnsToAnEarlierValue() {
        // Why this is a counter and not a hash of the content: typing a character and deleting it leaves
        // identical text, and a result computed against the text in between is still describing a
        // document that no longer exists. Identity of content is the wrong question.
        TextBuffer buffer = buffer("x");
        int start = buffer.version();

        buffer.insert(1, "y");
        buffer.delete(1, 2);

        assertEquals("the text is back where it started", "x", buffer.toString());
        assertTrue("the version is not", buffer.version() > start);
    }

    @Test
    public void aListenerSeesTheVersionItsOwnChangeProduced() {
        // Bumped before the signal, so onChanged can stamp a snapshot with the version it belongs to --
        // reading the pre-edit version there would stamp every job one behind, and the symptom is every
        // result being discarded as stale forever.
        TextBuffer buffer = buffer("");
        List<Integer> seen = new ArrayList<>();
        buffer.onChanged.connect(change -> seen.add(buffer.version()));

        buffer.insert(0, "a");

        assertEquals(1, seen.size());
        assertEquals("the listener must see the new version, not the one it replaced",
                buffer.version(), (int) seen.get(0));
    }
}
