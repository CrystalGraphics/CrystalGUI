package com.crystalgui.text;

import com.crystalgui.core.signal.Signal;

import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();

    private long coalesceWindowMillis = DEFAULT_COALESCE_WINDOW_MILLIS;
    private LongSupplier clock = System::currentTimeMillis;
    private boolean coalescingBroken = true;

    public TextBuffer() {
        this(Rope.EMPTY);
    }

    public TextBuffer(CharSequence text) {
        this(Rope.of(text));
    }

    public TextBuffer(Rope document) {
        this.document = document == null ? Rope.EMPTY : document;
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
        // Any edit invalidates the redo branch. Keeping it would let redo replay a change against a
        // document it was never described against, which the length check above would then reject at
        // some arbitrary later point rather than here.
        redoStack.clear();

        long now = clock.getAsLong();
        Entry previous = undoStack.peek();
        if (previous != null && !coalescingBroken && canCoalesce(previous, change, now)) {
            undoStack.pop();
            undoStack.push(new Entry(
                    previous.forward.compose(change),
                    // The inverse of "a then b" is "invert b, then invert a" — the new inverse runs
                    // first, because it is the one expressed against the document we are now in.
                    inverse.compose(previous.inverse),
                    now));
        } else {
            undoStack.push(new Entry(change, inverse, now));
        }
        coalescingBroken = false;
        onChanged.emit(change);
    }

    /**
     * Ends the current undo step, so the next edit starts a new one.
     *
     * <p>Called by the view when the caret is moved deliberately, the selection changes, or the document
     * is saved — the moments a user would consider one thing finished and another begun. The buffer
     * cannot detect those itself, which is exactly why this is public rather than inferred.</p>
     */
    public void breakUndoCoalescing() {
        coalescingBroken = true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** @return false when there was nothing to undo */
    public boolean undo() {
        Entry entry = undoStack.poll();
        if (entry == null) return false;
        document = entry.inverse.apply(document);
        redoStack.push(entry);
        coalescingBroken = true;
        onChanged.emit(entry.inverse);
        return true;
    }

    /** @return false when there was nothing to redo */
    public boolean redo() {
        Entry entry = redoStack.poll();
        if (entry == null) return false;
        document = entry.forward.apply(document);
        undoStack.push(entry);
        coalescingBroken = true;
        onChanged.emit(entry.forward);
        return true;
    }

    /** Undo history depth — for a history panel, and for tests that assert coalescing happened. */
    public int undoDepth() {
        return undoStack.size();
    }

    // ── Coalescing policy ───────────────────────────────────────────────────────────────────────

    /**
     * Whether an edit continues the previous one.
     *
     * <p>Three conditions, and each rules out a case where merging would surprise:</p>
     * <ul>
     *   <li><b>Within the time window</b> — a pause means the user stopped and started again.</li>
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
    private boolean canCoalesce(Entry previous, ChangeSet next, long now) {
        if (now - previous.timeMillis > coalesceWindowMillis) return false;
        if (previous.forward.changes().size() != 1 || next.changes().size() != 1) return false;

        Change before = previous.forward.changes().get(0);
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
        this.coalesceWindowMillis = Math.max(0L, millis);
        return this;
    }

    /** Replaces the clock. For tests — see the class note on why this is not read directly. */
    public TextBuffer setClock(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        return this;
    }

    private static final class Entry {
        final ChangeSet forward;
        final ChangeSet inverse;
        final long timeMillis;

        Entry(ChangeSet forward, ChangeSet inverse, long timeMillis) {
            this.forward = forward;
            this.inverse = inverse;
            this.timeMillis = timeMillis;
        }
    }
}
