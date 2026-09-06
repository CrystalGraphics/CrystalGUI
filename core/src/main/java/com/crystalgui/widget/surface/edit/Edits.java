package com.crystalgui.widget.surface.edit;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;

/**
 * The one door every change to a surface goes through.
 *
 * <pre>{@code
 * ctx.edits().apply(new MoveNodeEdit(node, from, to));      // do it and record it
 * ctx.edits().gesture("move", () -> {                        // many changes, one undo step
 *     for (Move each : moves) ctx.edits().record(policy.moveEdit(each));
 * });
 * }</pre>
 *
 * <p>{@link #apply} performs the edit and records it; {@link #record} only records, for a change a
 * gesture already made as it went — a drag moves the node on every frame and pushes one edit at the end.
 * A tool that mutates without going through either is a change the user cannot undo, which is the whole
 * reason this is a service rather than a convention.</p>
 *
 * <p>{@link #gesture} is what {@code I5} is checked against: one pointer gesture is one undo step,
 * however many items it moved. It ends the transaction even when the body throws, so a failed edit
 * cannot leave the history open.</p>
 */
public final class Edits {

    private final UndoStack history;

    /** Fires when a gesture opens — a drag beginning, a multi-step command starting. */
    public final Signal.Action onDidBeginGesture = new Signal.Action();

    /** Fires when it closes, committed or aborted. */
    public final Signal.Action onDidEndGesture = new Signal.Action();

    public Edits(UndoStack history) {
        this.history = history;
    }

    /** The stack underneath, for a command that undoes or redoes. */
    public UndoStack history() {
        return history;
    }

    /** Performs {@code edit} and records it. Null is a no-op, so a policy may decline to record. */
    public void apply(@Nullable Edit edit) {
        if (edit != null) history.execute(edit);
    }

    /** Records a change that has already been made — what a drag pushes when it ends. */
    public void record(@Nullable Edit edit) {
        if (edit != null) history.push(edit);
    }

    /**
     * Runs {@code body} as one undo step.
     *
     * <p>Forty nodes moved by one drag is one Ctrl+Z. The transaction is closed on the way out however
     * the body ended.</p>
     */
    public void gesture(String label, Runnable body) {
        begin(label);
        try {
            body.run();
        } finally {
            end();
        }
    }

    /** Opens a step by hand. Prefer {@link #gesture}, which cannot forget to close it. */
    public void begin(String label) {
        history.beginTransaction(label);
        onDidBeginGesture.emit();
    }

    public void end() {
        history.endTransaction();
        onDidEndGesture.emit();
    }

    /** Throws the step away — a drag cancelled with Escape. */
    public void abort() {
        history.abortTransaction();
        onDidEndGesture.emit();
    }

    public boolean inGesture() {
        return history.isInTransaction();
    }

    /** How long two edits may merge into one step. @see UndoStack#setMergeWindowMillis */
    public Edits setMergeWindowMillis(long millis) {
        history.setMergeWindowMillis(millis);
        return this;
    }
}
