package com.crystalgui.document;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;

/**
 * The base a custom document extends — <b>{@code apply(Edit)} is the one door</b>.
 *
 * <p>Everything a model owes falls out of that single call: the history records the edit, the version
 * bumps, and {@link #onChanged()} fires. So a shader graph's {@code connect(from, to)} is
 * {@code apply(new ConnectEdit(this, from, to))} and it is undoable, it marks the tab dirty, it is
 * backed up on a quit and it is announced to every view — with none of those written in the graph.</p>
 *
 * <h3>Why an Edit and not a listener</h3>
 *
 * <p>The engine already draws this line: document state goes through {@code Edit}s and view state is
 * mutated directly, which is why Ctrl+Z undoes a connection and not a scroll. A model built any other
 * way has to answer "have I changed" some other way, and the only other way is to serialise itself and
 * compare — which is what the layer this replaces did, per change, for every open document.</p>
 */
public abstract class AbstractDocumentModel implements DocumentModel {

    private final UndoStack history = new UndoStack();
    private final Signal.Action onChanged = new Signal.Action();
    private int version;

    @Override
    public final int version() {
        return version;
    }

    @Override
    public final UndoStack history() {
        return history;
    }

    @Override
    public final Signal.Action onChanged() {
        return onChanged;
    }

    /**
     * Applies a change and records it — the one door.
     *
     * <p>{@code UndoStack.execute} runs the edit and pushes it, so the caller does not apply it first.
     * The announcement is last, after the version has moved, so a listener reading {@link #version()}
     * from inside it sees the version its change produced rather than the one it replaced.</p>
     */
    protected final void apply(Edit edit) {
        if (edit == null) return;
        history.execute(edit);
        changed();
    }

    /**
     * "Something changed" for a model that <b>cannot express its change as an {@link Edit}</b>.
     *
     * <p>An image with an external editor behind it, a model whose whole state is one opaque blob. It
     * costs the undo step — there is nothing to undo, by construction — and it is deliberately available
     * rather than forcing such a model to invent an {@code Edit} that answers {@code undo()} with a
     * shrug. A model that CAN express its changes uses {@link #apply} and gets undo for nothing.</p>
     */
    protected final void markChanged() {
        changed();
    }

    /**
     * A reload: the version moves, the announcement fires, and <b>the history is cleared</b>.
     *
     * <p>Subclasses call this from {@link #adopt} after replacing their content. Clearing rather than
     * merely not-pushing is the half that is easy to get wrong: an entry recorded before a reload holds
     * an inverse taken against content the reload has replaced, so applying it afterwards corrupts
     * rather than merely being stale. Both references clear here — {@code ITextModel.setValue} resets
     * Monaco's stack, IntelliJ's {@code reloadFromDisk} drops the document's history.</p>
     */
    protected final void adopted() {
        history.clear();
        changed();
    }

    private void changed() {
        version++;
        onChanged.emit();
    }
}
