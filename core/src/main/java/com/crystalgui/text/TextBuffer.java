package com.crystalgui.text;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;

import java.util.function.LongSupplier;

/**
 * A mutable document: a {@link Rope} you can edit, with undo.
 *
 * <p>The buffer holds no view state — no caret, no selection, no scroll position. Those belong to the
 * widget looking at it, and keeping them out is what lets two views share one document without
 * negotiating whose caret is whose. It is also the 6.1.9 boundary in its most concrete form: what is in
 * here is what undo restores.</p>
 *
 * <h3>Undo stores changes, not snapshots</h3>
 * <p>Each entry keeps the edit and its inverse, both {@link ChangeSet}s — plain data, so the stack
 * serialises and a server can hold one. Holding old {@link Rope}s instead would be cheaper (they share
 * structure) and would also make the history un-sendable, which is the thing 6.1.9 decided against.</p>
 *
 * <h3>Coalescing, and why the clock is injectable</h3>
 * <p>A run of keystrokes is one undo step, achieved by {@link ChangeSet#compose} rather than by a merge
 * rule per edit type. {@link #setClock} exists because the engine has been bitten by this before:
 * {@code TransitionEngine} reads {@code System.nanoTime()} directly and consequently cannot be stepped by
 * a test, so its behaviour is asserted indirectly forever. Time that decides behaviour is an input.</p>
 */
public final class TextBuffer {

    /** How long a pause breaks a run of typing into a second undo step. Matches the usual editor feel. */
    public static final long DEFAULT_COALESCE_WINDOW_MILLIS = 500L;

    /** Emitted after every applied edit, including undo and redo, with the change that was applied. */
    public final Signal.Value<ChangeSet> onChanged = new Signal.Value<>();

    private Rope document;

    /**
     * The history, shared with the rest of the engine rather than private to this class.
     *
     * <p>It used to be a pair of deques here. {@link UndoStack} is the same mechanism generalised, and
     * using it means a text document and a node graph have one notion of an undo step — which is what
     * lets {@code edit.undo} be a single command that finds the nearest {@code UndoScope} instead of
     * every document type shipping its own keystroke.</p>
     *
     * <p>The split is clean: <b>the stack owns the clock</b> (a pause breaks a run) and <b>the edit owns
     * the intent</b> (typing and deleting are different things, and a run must be contiguous). Neither
     * half changed meaning in the move.</p>
     */
    private final UndoStack history = new UndoStack().setMergeWindowMillis(DEFAULT_COALESCE_WINDOW_MILLIS);

    public TextBuffer() {
        this(Rope.EMPTY);
    }

    /**
     * Takes text in whatever line ending it arrived with, and normalises it.
     *
     * <p>See {@link LineEnding}: the buffer is always LF internally, because every offset in the engine
     * counts a break as ONE unit and a {@code 
} would make that sometimes two. The original ending
     * is remembered so {@link #textWithOriginalLineEndings()} can put it back, which is why editing a
     * Windows file does not silently convert it.</p>
     */
    public TextBuffer(CharSequence text) {
        this.lineEnding = LineEnding.detect(text == null ? "" : text);
        this.document = Rope.of(LineEnding.normalise(text == null ? "" : text));
    }

    public TextBuffer(Rope document) {
        this.document = document == null ? Rope.EMPTY : document;
    }

    private LineEnding lineEnding = LineEnding.LF;

    /** The ending this document arrived with — what a save should write back. */
    public LineEnding lineEnding() {
        return lineEnding;
    }

    public TextBuffer setLineEnding(LineEnding ending) {
        this.lineEnding = ending == null ? LineEnding.LF : ending;
        return this;
    }

    /** The document written back in the ending it came with. What a save writes; never what it edits. */
    public String textWithOriginalLineEndings() {
        return lineEnding.applyTo(document.toString());
    }

    /** Replaces the whole document, re-detecting the line ending — i.e. loading a file. */
    public void load(CharSequence text) {
        String incoming = text == null ? "" : text.toString();
        this.lineEnding = LineEnding.detect(incoming);
        replace(0, length(), LineEnding.normalise(incoming));
        breakUndoCoalescing();
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    public Rope document() {
        return document;
    }

    public int length() {
        return document.length();
    }

    public int lineCount() {
        return document.lineCount();
    }

    public String line(int row) {
        return document.line(row);
    }

    public TextPoint offsetToPoint(int offset) {
        return document.offsetToPoint(offset);
    }

    public int pointToOffset(TextPoint point) {
        return document.pointToOffset(point);
    }

    @Override
    public String toString() {
        return document.toString();
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    public void replace(int from, int to, String text) {
        edit(ChangeSet.replace(document.length(), from, to, text));
    }

    public void insert(int offset, String text) {
        replace(offset, offset, text);
    }

    public void delete(int from, int to) {
        replace(from, to, "");
    }

    /**
     * Applies an edit and records it for undo, coalescing it into the previous step when it reads as a
     * continuation of the same typing.
     */
    public void edit(ChangeSet change) {
        if (change == null || change.isEmpty()) return;
        if (change.lengthBefore() != document.length()) {
            throw new IllegalArgumentException("edit applies to a document of length "
                    + change.lengthBefore() + ", but this one is " + document.length());
        }

        ChangeSet inverse = change.invert(document);
        document = change.apply(document);
        // Applied here, then recorded — which is what UndoStack.push is for. Handing the stack an
        // unapplied edit would mean applying it twice. Push also clears the redo branch: keeping it
        // would let redo replay a change against a document it was never described against, which the
        // length check above would then reject at some arbitrary later point rather than here.
        history.push(new ChangeSetEdit(this, change, inverse));
        onChanged.emit(change);
    }

    /**
     * One recorded edit: the change, and its inverse taken against the document it applied to.
     *
     * <p>Data rather than a closure — two {@code ChangeSet}s — so it survives serialization and cannot
     * capture a document that has since moved on. {@link #apply()} is what redo runs, and it is valid to
     * run again because undo has put the document back to the state the change was described
     * against.</p>
     */
    private record ChangeSetEdit(TextBuffer buffer, ChangeSet forward, ChangeSet inverse) implements Edit {
        @Override
        public void apply() {
            buffer.document = forward.apply(buffer.document);
            buffer.onChanged.emit(forward);
        }

        @Override
        public void undo() {
            buffer.document = inverse.apply(buffer.document);
            buffer.onChanged.emit(inverse);
        }

        @Override
        public String label() {
            return "typing";
        }

        @Override
        public Edit mergeWith(Edit next) {
            if (!(next instanceof ChangeSetEdit other) || other.buffer() != buffer) return null;
            if (!continues(forward, other.forward())) return null;
            return new ChangeSetEdit(buffer, forward.compose(other.forward()),
                    // The inverse of "a then b" is "invert b, then invert a" — the new inverse runs
                    // first, because it is the one expressed against the document we are now in.
                    other.inverse().compose(inverse));
        }
    }

    /**
     * Ends the current undo step, so the next edit starts a new one.
     *
     * <p>Called by the view when the caret is moved deliberately, the selection changes, or the document
     * is saved — the moments a user would consider one thing finished and another begun. The buffer
     * cannot detect those itself, which is exactly why this is public rather than inferred.</p>
     */
    public void breakUndoCoalescing() {
        history.breakMergeRun();
    }

    /** This document's history — what {@code TextEditor} hands to {@code UndoScope}, so a menu item and
     * the command palette reach the same stack the keystroke does. */
    public UndoStack history() {
        return history;
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /** @return false when there was nothing to undo */
    public boolean undo() {
        return history.undo();
    }

    /** @return false when there was nothing to redo */
    public boolean redo() {
        return history.redo();
    }

    /** Undo history depth — for a history panel, and for tests that assert coalescing happened. */
    public int undoDepth() {
        return history.undoDepth();
    }

    // ── Coalescing policy ───────────────────────────────────────────────────────────────────────

    /**
     * Whether an edit continues the previous one.
     *
     * <p>Two conditions here, plus one the stack applies before ever asking: it only offers a merge when
     * the two edits arrived within its window, because a pause means the user stopped and started again.
     * What is left is intent, which only this class can judge:</p>
     * <ul>
     *   <li><b>Same kind</b> — typing and deleting are different intents, so a backspace ends a run of
     *       typing rather than joining it. Otherwise one undo both restores what was deleted and removes
     *       what was typed, which is never what was wanted.</li>
     *   <li><b>Contiguous</b> — the new edit begins exactly where the previous one ended. A click
     *       elsewhere and a keystroke there is a separate step even if it lands inside the window.</li>
     * </ul>
     *
     * <p>A newline also breaks the run: a line is the unit people expect undo to work in, and it is the
     * one boundary that is worth special-casing because it is the one users can see.</p>
     */
    private static boolean continues(ChangeSet previous, ChangeSet next) {
        if (previous.changes().size() != 1 || next.changes().size() != 1) return false;

        Change before = previous.changes().get(0);
        Change after = next.changes().get(0);
        if (after.insert().indexOf('\n') >= 0) return false;
        if (kindOf(before) != kindOf(after)) return false;

        if (kindOf(after) == Kind.INSERT) {
            // The previous insertion ended where this one begins — i.e. still typing forwards.
            return after.from() == before.from() + before.inserted();
        }
        // Backspacing: each deletion ends where the previous one began.
        return after.to() == before.from();
    }

    private enum Kind { INSERT, DELETE, OTHER }

    private static Kind kindOf(Change change) {
        if (change.removed() == 0 && change.inserted() > 0) return Kind.INSERT;
        if (change.inserted() == 0 && change.removed() > 0) return Kind.DELETE;
        return Kind.OTHER;
    }

    public TextBuffer setCoalesceWindowMillis(long millis) {
        history.setMergeWindowMillis(millis);
        return this;
    }

    /** Replaces the clock. For tests — see the class note on why this is not read directly. */
    public TextBuffer setClock(LongSupplier clock) {
        history.setClock(clock);
        return this;
    }

}
