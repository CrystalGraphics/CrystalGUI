package com.crystalgui.headless;

import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import org.junit.Test;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.1.9 — the undo half of the command system.
 *
 * <h3>Why this lives in headlessTest</h3>
 * <p>A dedicated server authors and validates documents, so the history mechanism has to run with no
 * GL context and no CrystalGraphics core on the classpath. The absence is the assertion: if this ever
 * reaches a paint-time type it fails here with {@code NoClassDefFoundError} rather than in
 * production.</p>
 *
 * <h3>What is actually being asserted</h3>
 * <p>Not "undo undoes" — the interesting properties are the ones that make a history <em>trustworthy</em>:
 * that a group unwinds in reverse, that a new edit destroys the redo branch, that coalescing is bounded
 * by a pause rather than by a count, and that re-entering the stack from inside an undo is refused
 * instead of quietly corrupting it.</p>
 */
public class UndoStackTest {

    /** A document that is just a string, so an assertion can read like the user's mental model. */
    private static final class Doc {
        String text = "";
    }

    /** Insert at the end — the shape of a typed character, and mergeable with the next one. */
    private record Type(Doc doc, String inserted) implements Edit {
        @Override public void apply() { doc.text += inserted; }
        @Override public void undo() { doc.text = doc.text.substring(0, doc.text.length() - inserted.length()); }
        @Override public String label() { return "typing"; }
        @Nullable
        @Override public Edit mergeWith(Edit next) {
            // Composition, not a bespoke merge rule: the merged edit inserts both and undoes both.
            return next instanceof Type t && t.doc() == doc ? new Type(doc, inserted + t.inserted()) : null;
        }
    }

    /** Deliberately unmergeable, so a test can prove the stack asks rather than assumes. */
    private record Erase(Doc doc, int count) implements Edit {
        @Override public void apply() { doc.text = doc.text.substring(0, doc.text.length() - count); }
        @Override public void undo() { throw new UnsupportedOperationException("not needed by these tests"); }
        @Override public String label() { return "erase"; }
    }

    private static Edit recording(List<String> log, String name) {
        return new Edit() {
            @Override public void apply() { log.add("do:" + name); }
            @Override public void undo() { log.add("undo:" + name); }
            @Override public String label() { return name; }
        };
    }

    // ── The basics ──────────────────────────────────────────────────────────

    @Test
    public void executeAppliesAndRecords() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();

        history.execute(new Type(doc, "a"));

        assertEquals("a", doc.text);
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());
        assertEquals("typing", history.undoLabel());
    }

    @Test
    public void undoThenRedoReturnsToWhereItWas() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();
        history.execute(new Type(doc, "a"));
        history.breakMergeRun();
        history.execute(new Type(doc, "b"));

        assertTrue(history.undo());
        assertEquals("a", doc.text);
        assertTrue(history.undo());
        assertEquals("", doc.text);
        assertFalse("nothing left to undo", history.undo());

        assertTrue(history.redo());
        assertTrue(history.redo());
        assertEquals("ab", doc.text);
        assertFalse(history.redo());
    }

    /** {@link UndoStack#push} is for a document that already applied its own change — the shape
     * {@code TextBuffer} has. Applying it a second time would double every edit. */
    @Test
    public void pushRecordsAnAlreadyAppliedEdit() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();

        Edit typed = new Type(doc, "hi");
        typed.apply();          // the document did this itself
        history.push(typed);

        assertEquals("hi", doc.text);
        history.undo();
        assertEquals("", doc.text);
    }

    // ── The properties that make a history trustworthy ──────────────────────

    /**
     * <b>A new edit destroys the redo branch.</b>
     *
     * <p>The redone future belonged to a branch that no longer exists. Keeping it would offer to redo
     * changes that were overwritten, against a document that has moved on.</p>
     */
    @Test
    public void editingAfterAnUndoDropsTheRedoBranch() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();
        history.execute(new Type(doc, "a"));
        history.undo();
        assertTrue(history.canRedo());

        history.execute(new Type(doc, "z"));

        assertFalse("the branch it belonged to is gone", history.canRedo());
        assertEquals("z", doc.text);
    }

    /**
     * <b>A group undoes in reverse.</b>
     *
     * <p>Each edit in a group assumed the state the previous one left, so unwinding forwards asks the
     * first to undo against a document it never saw. The disconnect-then-connect pair is the smallest
     * real example.</p>
     */
    @Test
    public void aTransactionIsOneStepAndUnwindsBackwards() {
        List<String> log = new ArrayList<>();
        UndoStack history = new UndoStack();

        history.beginTransaction("move nodes");
        history.execute(recording(log, "first"));
        history.execute(recording(log, "second"));
        history.execute(recording(log, "third"));
        history.endTransaction();

        assertEquals(1, history.undoDepth());
        assertEquals("move nodes", history.undoLabel());
        assertEquals(List.of("do:first", "do:second", "do:third"), log);

        log.clear();
        history.undo();
        assertEquals(List.of("undo:third", "undo:second", "undo:first"), log);
    }

    @Test
    public void anEmptyTransactionRecordsNothing() {
        UndoStack history = new UndoStack();
        history.beginTransaction("drag that went nowhere");
        history.endTransaction();

        assertFalse("a step that undoes nothing costs the user a keypress that appears to do nothing",
                history.canUndo());
    }

    @Test
    public void nestedTransactionsCollapseIntoTheOuterStep() {
        List<String> log = new ArrayList<>();
        UndoStack history = new UndoStack();

        history.beginTransaction("outer");
        history.execute(recording(log, "a"));
        history.beginTransaction("inner");
        history.execute(recording(log, "b"));
        history.execute(recording(log, "c"));
        history.endTransaction();
        history.endTransaction();

        assertEquals("one step, however many groups deep", 1, history.undoDepth());
        log.clear();
        history.undo();
        assertEquals(List.of("undo:c", "undo:b", "undo:a"), log);
    }

    /** A cancelled gesture unwinds what it already did — the edits happened, so "abandon" has to mean
     * reversing them. */
    @Test
    public void abortingATransactionUndoesItsEdits() {
        List<String> log = new ArrayList<>();
        UndoStack history = new UndoStack();

        history.beginTransaction("drag");
        history.execute(recording(log, "a"));
        history.execute(recording(log, "b"));
        history.abortTransaction();

        assertEquals(List.of("do:a", "do:b", "undo:b", "undo:a"), log);
        assertFalse("and leaves no trace in the history", history.canUndo());
    }

    /** A single-edit transaction reads as that edit, not as a group of one — a history panel should
     * say "typing", not "group". */
    @Test
    public void aTransactionOfOneCollapsesToTheEditItself() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();
        history.beginTransaction("group");
        history.execute(new Type(doc, "a"));
        history.endTransaction();

        assertEquals("typing", history.undoLabel());
    }

    // ── Coalescing ──────────────────────────────────────────────────────────

    /**
     * <b>A run of typing is one step, and the rule is a pause.</b>
     *
     * <p>The stack measures the pause and the edit decides whether the two are the same kind of thing —
     * neither half can implement the rule alone, which is why the window lives here and
     * {@code mergeWith} lives on the edit.</p>
     */
    @Test
    public void typingWithinTheWindowCoalescesIntoOneStep() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();

        history.execute(new Type(doc, "h"));
        history.execute(new Type(doc, "e"));
        history.execute(new Type(doc, "y"));

        assertEquals("hey", doc.text);
        assertEquals("one run of typing is one step", 1, history.undoDepth());
        history.undo();
        assertEquals("and it undoes the whole run", "", doc.text);
    }

    @Test
    public void aPauseBreaksTheRun() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack().setMergeWindowMillis(0);

        history.execute(new Type(doc, "a"));
        history.execute(new Type(doc, "b"));

        assertEquals("outside the window they are separate steps", 2, history.undoDepth());
        history.undo();
        assertEquals("a", doc.text);
    }

    /** Moving the caret breaks the run even when the next keystroke is immediate — the classic editor
     * rule, and the reason the stack exposes it rather than only measuring time. */
    @Test
    public void breakMergeRunEndsTheRunExplicitly() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();

        history.execute(new Type(doc, "a"));
        history.breakMergeRun();
        history.execute(new Type(doc, "b"));

        assertEquals(2, history.undoDepth());
    }

    /** The stack offers; the edit refuses. Two different kinds of change never merge however fast they
     * arrive. */
    @Test
    public void unrelatedEditsDoNotMergeEvenWithinTheWindow() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();

        history.execute(new Type(doc, "abc"));
        history.execute(new Erase(doc, 1));

        assertEquals(2, history.undoDepth());
        assertEquals("erase", history.undoLabel());
    }

    /** An undone step must not absorb whatever is typed next — they are unrelated by definition. */
    @Test
    public void anUndoEndsTheMergeRun() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();

        history.execute(new Type(doc, "a"));
        history.undo();
        history.execute(new Type(doc, "b"));

        assertEquals(1, history.undoDepth());
        history.undo();
        assertEquals("", doc.text);
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    /**
     * <b>Re-entering the stack from inside an undo is refused.</b>
     *
     * <p>A listener that reacts to a document change by editing it again would push onto a stack that is
     * mid-unwind, leaving a history that no longer describes the document. Throwing puts the exception
     * at the call site rather than leaving a corruption to be found three actions later.</p>
     */
    @Test
    public void editingFromInsideAnUndoThrows() {
        UndoStack history = new UndoStack();
        List<String> log = new ArrayList<>();
        boolean[] threw = {false};

        history.execute(new Edit() {
            @Override public void apply() { log.add("do"); }
            @Override public void undo() {
                try {
                    history.execute(recording(log, "reentrant"));
                } catch (IllegalStateException expected) {
                    threw[0] = true;
                }
            }
        });
        history.undo();

        assertTrue("a re-entrant edit must be refused, not recorded", threw[0]);
        assertFalse(history.isApplying());
    }

    @Test
    public void undoDuringAnOpenTransactionThrows() {
        UndoStack history = new UndoStack();
        history.beginTransaction("open");
        try {
            history.undo();
            fail("undo must not unwind steps an open group still owns");
        } catch (IllegalStateException expected) {
            // the guard
        }
    }

    @Test
    public void theLimitDropsTheOldestStepsFirst() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack().setLimit(3).setMergeWindowMillis(0);
        for (int i = 0; i < 10; i++) history.execute(new Type(doc, "x"));

        assertEquals(3, history.undoDepth());
        assertTrue("the newest steps are the ones kept", history.undo());
    }

    @Test
    public void clearForgetsBothDirections() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();
        history.execute(new Type(doc, "a"));
        history.undo();

        history.clear();

        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }

    @Test
    public void theChangedSignalFiresForEveryHistoryChange() {
        Doc doc = new Doc();
        UndoStack history = new UndoStack();
        int[] fired = {0};
        history.onChanged.connect(() -> fired[0]++);

        history.execute(new Type(doc, "a"));   // 1
        history.undo();                        // 2
        history.redo();                        // 3
        history.clear();                       // 4

        assertEquals(4, fired[0]);
    }

    @Test
    public void aCompositeEditCanBeBuiltDirectly() {
        List<String> log = new ArrayList<>();
        UndoStack history = new UndoStack();

        history.execute(CompositeEdit.of("rewire", recording(log, "disconnect"), recording(log, "connect")));
        log.clear();
        history.undo();

        assertEquals(List.of("undo:connect", "undo:disconnect"), log);
    }
}
