package com.crystalgui.core.undo;

import com.crystalgui.core.signal.Signal;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The undo/redo history for <b>one document</b>.
 *
 * <pre>{@code
 * UndoStack history = new UndoStack();
 * history.execute(new MoveNode(node, from, to));   // applies it and records it
 * history.undo();
 * history.redo();
 *
 * history.beginTransaction("move nodes");          // forty moves, one Ctrl+Z
 * for (GraphNode n : selection) history.execute(new MoveNode(n, ...));
 * history.endTransaction();
 * }</pre>
 *
 * <h3>Per document, deliberately not per window</h3>
 * <p>A window can hold several documents — two editor tabs, a graph and its inspector — and their
 * histories must not braid together. Undoing in one tab must never reach into another, which is how
 * every editor with tabs behaves and is impossible to retrofit once a shared stack exists. So this is a
 * plain object a document owner creates and exposes; there is deliberately no
 * {@code UIWindow.getUndoStack()} for a widget to reach for by default.</p>
 *
 * <h3>Coalescing: the stack owns the clock, the edit owns the intent</h3>
 * <p>A run of typing is one undo step, and the rule that makes it so is a pause — not a character
 * count, not a word boundary. This class measures the pause ({@link #DEFAULT_MERGE_WINDOW_MILLIS},
 * matching {@code TextBuffer}'s own) and offers the previous edit a merge; the edit decides whether the
 * two are the same kind of thing. Neither half can implement the rule alone, and splitting it this way
 * means an edit type never contains a timestamp.</p>
 *
 * <h3>Re-entrancy is refused, not tolerated</h3>
 * <p>Applying an edit fires whatever the document notifies, and a listener that responds by executing
 * another edit would push onto a stack that is mid-unwind — leaving history that does not match the
 * document and cannot be unwound. That is a bug in the listener, so it throws rather than silently
 * dropping the edit: a corrupted history discovered three actions later is far more expensive than an
 * exception at the call site.</p>
 */
public final class UndoStack {

    /** Matches {@code TextBuffer.DEFAULT_COALESCE_WINDOW_MILLIS}. The two are independent mechanisms
     * today, and a user pressing Ctrl+Z should not be able to tell — so they agree on the number. */
    public static final long DEFAULT_MERGE_WINDOW_MILLIS = 500L;

    /** How many steps are kept. Deep enough that no one reaches the end by working, shallow enough that
     * a long session cannot pin arbitrary amounts of superseded document state in memory. */
    public static final int DEFAULT_LIMIT = 256;

    private final Deque<Edit> undoStack = new ArrayDeque<>();
    private final Deque<Edit> redoStack = new ArrayDeque<>();

    @Getter
    private long mergeWindowMillis = DEFAULT_MERGE_WINDOW_MILLIS;

    @Getter
    private int limit = DEFAULT_LIMIT;

    /** When the top of the undo stack was pushed, in {@link #clock} milliseconds. Only meaningful while
     * {@link #mergeRunOpen}. */
    private long lastPushMillis;

    /**
     * Where "now" comes from, in milliseconds.
     *
     * <p>Injectable because <b>time that decides behaviour is an input</b> — the same reason
     * {@code TextBuffer} takes one, and the same complaint its javadoc makes about
     * {@code TransitionEngine} reading {@code System.nanoTime()} directly and therefore being
     * untestable forever. A test that wants to prove a pause breaks a typing run should step the clock,
     * not sleep and hope.</p>
     */
    private LongSupplier clock = System::currentTimeMillis;

    /**
     * Whether the top of the stack is still eligible to absorb the next edit.
     *
     * <p>A flag rather than a sentinel timestamp, because the obvious sentinel does not work:
     * {@code System.nanoTime()} has an arbitrary origin and may be negative, so
     * {@code nanoTime() - Long.MIN_VALUE} <b>overflows</b> and comes back as a small elapsed time —
     * i.e. "never merge again" evaluated as "merge immediately". That shipped here for exactly one test
     * run, and it is the kind of thing that would otherwise present as undo occasionally swallowing two
     * actions on some machines and not others.</p>
     */
    private boolean mergeRunOpen;

    /** Nesting depth of {@link #beginMergeRun()}. Counted rather than boolean so a gesture that calls
     * another gesture's helper cannot end the outer one's run early. */
    private int mergeRunHolds;

    /** Open transactions, outermost first. Nesting is supported so a command that groups edits can call
     * another that groups its own without either knowing about the other. */
    private final List<Transaction> transactions = new ArrayList<>();

    /** True while {@link #undo()} or {@link #redo()} is running. @see #assertNotApplying */
    @Getter
    private boolean applying;

    /**
     * Fires after anything that changes what undo or redo would do — an execute, an undo, a redo, a
     * clear, a commit. One signal rather than several because every consumer (an enabled/disabled
     * toolbar button, a history panel, a dirty marker) re-reads the whole state anyway.
     */
    public final Signal.Action onChanged = new Signal.Action();

    private record Transaction(String label, List<Edit> edits) {
    }

    // ── Recording ───────────────────────────────────────────────────────────

    /**
     * Applies {@code edit} and records it.
     *
     * <p>The usual entry point: the caller describes the change and the stack performs it, so there is
     * one order of operations rather than one per call site.</p>
     */
    public void execute(Edit edit) {
        assertNotApplying("execute");
        edit.apply();
        push(edit);
    }

    /**
     * Records an edit that the caller has <b>already applied</b>.
     *
     * <p>For a document that performs its own mutation and reports it afterwards — {@code TextBuffer}
     * applies a {@code ChangeSet} and emits it, and asking it to hand the change to a stack to be
     * re-applied would mean applying it twice. Both entry points exist because both shapes are
     * legitimate; what would not be legitimate is a stack that guesses which happened.</p>
     */
    public void push(Edit edit) {
        assertNotApplying("push");
        if (!transactions.isEmpty()) {
            transactions.get(transactions.size() - 1).edits().add(edit);
            return;
        }

        // Anything that could have been redone is unreachable the moment a new edit lands — the branch
        // it belonged to no longer exists. Every editor does this; keeping it would offer to redo a
        // future that was overwritten.
        redoStack.clear();

        if (!mergeIntoTop(edit)) {
            undoStack.push(edit);
            trimToLimit();
        }
        lastPushMillis = clock.getAsLong();
        mergeRunOpen = true;
        onChanged.emit();
    }

    /** @return whether {@code edit} was absorbed into the current top of the stack. */
    private boolean mergeIntoTop(Edit edit) {
        Edit top = undoStack.peek();
        if (top == null || !mergeRunOpen) return false;
        // Zero means OFF, not "merge only within the same millisecond" — two immediate pushes are
        // routinely 0ms apart, so a `>` comparison against 0 would coalesce them.
        if (mergeWindowMillis <= 0L && !isMergeRunHeld()) return false;
        // A held run ignores the clock entirely. @see #beginMergeRun
        if (!isMergeRunHeld() && clock.getAsLong() - lastPushMillis > mergeWindowMillis) return false;
        Edit merged = top.mergeWith(edit);
        if (merged == null) return false;
        undoStack.pop();
        undoStack.push(merged);
        return true;
    }

    private void trimToLimit() {
        while (undoStack.size() > limit) undoStack.removeLast();
    }

    // ── Transactions ────────────────────────────────────────────────────────

    /**
     * Starts grouping every subsequent edit into one undo step, until the matching
     * {@link #endTransaction()}.
     *
     * <p>Edits still apply immediately — a transaction changes how the <em>history</em> records them,
     * not when they take effect. Deferring application would mean the screen lagging behind the drag
     * that is producing the edits.</p>
     */
    public void beginTransaction(String label) {
        assertNotApplying("beginTransaction");
        transactions.add(new Transaction(label, new ArrayList<>()));
    }

    /**
     * Closes the innermost transaction, pushing its edits as a single step.
     *
     * <p>An empty transaction pushes nothing, which is the case worth having: a drag that ended where
     * it started, a delete over an empty selection. A history full of steps that undo nothing is worse
     * than no grouping at all, because every one of them costs the user a Ctrl+Z that appears to do
     * nothing.</p>
     */
    public void endTransaction() {
        if (transactions.isEmpty()) throw new IllegalStateException("endTransaction without beginTransaction");
        Transaction done = transactions.remove(transactions.size() - 1);
        if (done.edits().isEmpty()) return;
        Edit collapsed = CompositeEdit.collapse(done.edits(), done.label());
        // Nested: hand it to the enclosing transaction rather than to the stack, or the outer group
        // would lose the inner one's edits.
        if (!transactions.isEmpty()) {
            transactions.get(transactions.size() - 1).edits().add(collapsed);
            return;
        }
        redoStack.clear();
        undoStack.push(collapsed);
        trimToLimit();
        // A committed group never merges with what came before it: the user drew a boundary by taking
        // the action, and silently dissolving it into the previous step would undo more than they did.
        mergeRunOpen = false;
        onChanged.emit();
    }

    /**
     * Abandons the innermost transaction, undoing everything it collected.
     *
     * <p>For a gesture the user cancelled — Escape during a drag. The edits already happened, so
     * "abandon" has to mean unwinding them, in reverse, exactly as an undo would.</p>
     */
    public void abortTransaction() {
        if (transactions.isEmpty()) throw new IllegalStateException("abortTransaction without beginTransaction");
        Transaction done = transactions.remove(transactions.size() - 1);
        if (done.edits().isEmpty()) return;
        applying = true;
        try {
            CompositeEdit.collapse(done.edits(), done.label()).undo();
        } finally {
            applying = false;
        }
    }

    public boolean isInTransaction() {
        return !transactions.isEmpty();
    }

    // ── Undo and redo ───────────────────────────────────────────────────────

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** The label of the step Ctrl+Z would undo, or {@code null}. For a menu item reading "Undo move". */
    @Nullable
    public String undoLabel() {
        Edit top = undoStack.peek();
        return top == null ? null : top.label();
    }

    @Nullable
    public String redoLabel() {
        Edit top = redoStack.peek();
        return top == null ? null : top.label();
    }

    /** @return whether anything was undone. */
    public boolean undo() {
        assertNotApplying("undo");
        // Mid-transaction it would unwind steps the open group still believes it owns, leaving the
        // group to commit edits that have already been reversed.
        if (!transactions.isEmpty()) throw new IllegalStateException("undo during an open transaction");
        if (undoStack.isEmpty()) return false;
        Edit edit = undoStack.pop();
        applying = true;
        try {
            edit.undo();
        } finally {
            applying = false;
        }
        redoStack.push(edit);
        // A step that was just undone must not absorb the next edit — they are unrelated by definition.
        mergeRunOpen = false;
        onChanged.emit();
        return true;
    }

    /** @return whether anything was redone. */
    public boolean redo() {
        assertNotApplying("redo");
        if (!transactions.isEmpty()) throw new IllegalStateException("redo during an open transaction");
        if (redoStack.isEmpty()) return false;
        Edit edit = redoStack.pop();
        applying = true;
        try {
            edit.apply();
        } finally {
            applying = false;
        }
        undoStack.push(edit);
        mergeRunOpen = false;
        onChanged.emit();
        return true;
    }

    // ── Housekeeping ────────────────────────────────────────────────────────

    /** Forgets everything. For loading a new document into the same view — the old history describes a
     * document that is no longer there, and offering to undo into it is worse than offering nothing. */
    public void clear() {
        boolean had = !undoStack.isEmpty() || !redoStack.isEmpty();
        undoStack.clear();
        redoStack.clear();
        transactions.clear();
        mergeRunOpen = false;
        if (had) onChanged.emit();
    }

    public int undoDepth() {
        return undoStack.size();
    }

    public int redoDepth() {
        return redoStack.size();
    }

    public UndoStack setMergeWindowMillis(long millis) {
        this.mergeWindowMillis = Math.max(0L, millis);
        return this;
    }

    /** @param clock milliseconds from any origin; only differences are read. */
    public UndoStack setClock(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        return this;
    }

    public UndoStack setLimit(int steps) {
        this.limit = Math.max(1, steps);
        trimToLimit();
        return this;
    }

    /** Ends the current merge run, so the next edit starts a fresh step even if it arrives immediately.
     * What a caret move, a focus change or a selection change should call — the classic editor rule that
     * moving the cursor breaks the typing run. */
    public void breakMergeRun() {
        mergeRunHolds = 0;
        mergeRunOpen = false;
    }

    /**
     * Holds the merge run open for a <b>continuous gesture</b>, until the matching {@link #endMergeRun()}.
     *
     * <h3>Why the clock is not enough</h3>
     * <p>The time window exists to answer "did the user pause?", which is the right question for typing
     * and the wrong one for a drag. A slider drag, a colour-picker drag or a
     * {@linkplain com.crystalgui.ui.input.DragScrub scrub} emits a value per frame and can legitimately
     * sit still for several seconds while the user studies what it did to a preview — and then produces
     * three undo steps for one gesture, on a machine slower than the one it was tested on. The gesture
     * knows its own boundaries exactly; the clock is guessing at them.</p>
     *
     * <h3>Why not a transaction</h3>
     * <p>{@link #beginTransaction} would also give one undo step, but it gives it as a
     * {@code CompositeEdit} of however many frames the drag lasted — hundreds of edits pinning hundreds of
     * superseded values, unwound one at a time. Merging collapses them into a <b>single</b> edit as they
     * arrive, because {@code mergeWith} already keeps the first edit's {@code before} and the last one's
     * {@code after}. A transaction is for grouping <em>different</em> edits; this is for one edit
     * restated.</p>
     *
     * <p>Edits still have to agree to merge — an edit type whose {@code mergeWith} returns null is
     * unaffected by this, and correctly so.</p>
     */
    public void beginMergeRun() {
        // A gesture starts its OWN step. Closing the current run first is the whole of that, and leaving
        // it out is subtly wrong in a way that reads as undo being broken rather than as merging being
        // over-eager:
        //
        // `mergeRunOpen` is true after ANY push, so a gesture beginning right after an ordinary edit
        // inherited that run. If the previous edit touched the SAME node and field — typing a value and
        // then scrubbing it, which is the natural order — `SetNodeFieldEdit.mergeWith` accepted, and the
        // whole drag was absorbed into the earlier entry. Ctrl+Z then reverted BOTH, landing on the value
        // from before the typing: "undo does not undo the scrub, it undoes something earlier".
        //
        // Only when the run is not already held, or a nested begin would break its own outer gesture.
        if (mergeRunHolds == 0) mergeRunOpen = false;
        mergeRunHolds++;
    }

    /** Ends a {@link #beginMergeRun()} and closes the run, so the next gesture is a separate undo step. */
    public void endMergeRun() {
        if (mergeRunHolds > 0) mergeRunHolds--;
        if (mergeRunHolds == 0) mergeRunOpen = false;
    }

    /** True while a gesture is holding the merge run open. */
    public boolean isMergeRunHeld() {
        return mergeRunHolds > 0;
    }

    private void assertNotApplying(String operation) {
        if (applying) {
            throw new IllegalStateException(operation + "() called from inside an undo or redo — a listener "
                    + "reacting to a document change by editing it again would corrupt the history");
        }
    }
}
