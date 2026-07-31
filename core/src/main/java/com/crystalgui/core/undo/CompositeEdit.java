package com.crystalgui.core.undo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Several edits that undo as one step — what {@link UndoStack#beginTransaction} produces.
 *
 * <p>The case this exists for is everywhere in a graph editor: dragging forty selected nodes is one
 * user action and forty position changes; dropping a wire onto an occupied input is a disconnect and a
 * connect. Recording those as separate steps makes Ctrl+Z useless precisely when it matters, because
 * undoing "the last thing I did" would take forty presses and would half-undo the rewire.</p>
 *
 * <p><b>Undo runs in reverse order.</b> Not tidiness — a composite is a sequence of edits each of which
 * assumed the state the previous one left, so unwinding forwards asks the first edit to undo against a
 * document it never saw. The disconnect-then-connect pair is the smallest example: undoing the
 * disconnect first would restore an edge into an input that is still occupied.</p>
 */
public record CompositeEdit(List<Edit> edits, String label) implements Edit {

    public CompositeEdit {
        edits = List.copyOf(edits);
        if (edits.isEmpty()) throw new IllegalArgumentException("A composite edit needs at least one edit");
    }

    public static CompositeEdit of(String label, Edit... edits) {
        return new CompositeEdit(List.of(edits), label);
    }

    /** Flattens a single-element list rather than wrapping it — a transaction that turned out to
     * contain one edit should read as that edit in a history panel, not as a group of one. */
    static Edit collapse(List<Edit> edits, String label) {
        if (edits.size() == 1) return edits.get(0);
        return new CompositeEdit(edits, label);
    }

    @Override
    public void apply() {
        for (Edit edit : edits) edit.apply();
    }

    @Override
    public void undo() {
        List<Edit> reversed = new ArrayList<>(edits);
        Collections.reverse(reversed);
        for (Edit edit : reversed) edit.undo();
    }
}
