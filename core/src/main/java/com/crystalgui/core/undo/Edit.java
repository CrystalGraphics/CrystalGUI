package com.crystalgui.core.undo;

import javax.annotation.Nullable;

/**
 * One undoable change to a document.
 *
 * <h3>An {@code Edit} is not a {@link com.crystalgui.core.command.Command}</h3>
 * <p>They are adjacent and easy to conflate, so: a {@code Command} is <b>what the user asked for</b> —
 * a named, id-addressable action that a key binding, a menu item and the command palette all point at.
 * An {@code Edit} is <b>what that did to the document</b>. One command may produce no edits (a
 * scroll), one edit (typing a character), or several ({@code paste over a selection}). The command
 * layer is about invocation and enablement; this one is about reversal.</p>
 *
 * <h3>Document state only</h3>
 * <p>The boundary this whole mechanism rests on: <b>document state goes through edits; view state is
 * mutated directly.</b> Anything a reload should give back is an edit — text, nodes, connections.
 * Anything that is purely how you are <em>looking</em> at the document is not: scroll offset,
 * selection, column widths, which tree nodes are expanded, pan and zoom. That is where VS Code,
 * Photoshop and Godot's {@code UndoRedo} all draw it, and it is why re-sorting a table is undoable in
 * none of them.</p>
 *
 * <p>Getting this wrong is not a crash, it is a UI that feels broken in a way users cannot describe:
 * press Ctrl+Z after a long edit and watch it scroll somewhere instead of undoing anything.</p>
 *
 * <h3>Implementations should be data</h3>
 * <p>Prefer a record carrying offsets, ids and values over a pair of closures. A {@code ChangeSet} is
 * the model: it is offsets and inserted text, so it survives serialization, and its inverse is derived
 * from the document rather than remembered. A stack of closures cannot be sent to a server, cannot be
 * shown in a history panel with a meaningful label, and can silently capture state that has since
 * moved on.</p>
 */
public interface Edit {

    /**
     * Applies the change — used both for the first application (via {@link UndoStack#execute}) and for
     * every redo after an undo.
     *
     * <p>Must be repeatable: apply → undo → apply has to land in the same state as apply alone. An edit
     * that consumes something on first use (a one-shot iterator, a mutable payload it clears) breaks
     * redo in a way that only shows up on the second press.</p>
     */
    void apply();

    /** Restores exactly the state {@link #apply()} found. */
    void undo();

    /** For a history panel, a tooltip, and "Undo <em>typing</em>" in a menu. */
    default String label() {
        return "edit";
    }

    /**
     * Merges {@code next} into this edit, or returns {@code null} to keep them as separate undo steps.
     *
     * <p><b>The stack owns the time window; the edit owns the intent.</b> {@link UndoStack} only offers
     * a merge when the two arrived close enough together to read as one gesture — so an implementation
     * decides whether they are <em>the same kind of thing</em> and never has to think about timing. A
     * run of typed characters merges; typing then deleting does not; two drags of the same node do not,
     * because the pause between them is the user deciding.</p>
     *
     * <p>Both edits have already been applied when this is called, so the returned edit must undo
     * <em>both</em> — which is exactly what {@code ChangeSet.compose} produces, and the reason coalescing
     * is a composition rather than a bespoke merge rule per edit type.</p>
     */
    @Nullable
    default Edit mergeWith(Edit next) {
        return null;
    }
}
