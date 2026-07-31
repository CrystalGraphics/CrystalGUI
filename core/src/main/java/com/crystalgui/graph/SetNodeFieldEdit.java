package com.crystalgui.graph;

import com.crystalgui.core.undo.Edit;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Changes one editable value on a node, undoably.
 *
 * <h3>Why this exists rather than a direct write</h3>
 * <p>A field's value is <b>document state</b>: it changes what the node does and it must survive a save
 * and reload. This project's own rule is that document state goes through an {@link Edit} and view state
 * does not — so a dropdown that called {@code replaceNode} directly would be invisible to Ctrl+Z, which
 * is exactly how the {@code Space} dropdown first shipped.</p>
 *
 * <h3>Data, not closures</h3>
 * <p>Node id, field id, before and after. Nothing is captured that can go stale: the node is looked up by
 * id at apply time, so this keeps working across a delete-then-undo, where the widget it was created
 * against no longer exists.</p>
 *
 * <h3>Typing coalesces; picking does not</h3>
 * <p>{@link #mergeWith} joins consecutive edits to the <b>same field of the same node</b>, which is what
 * makes typing into a number box one undo step rather than one per keystroke. The stack owns the time
 * window, so this only has to decide whether the two are the same kind of thing.</p>
 */
public record SetNodeFieldEdit(GraphDocument document, String nodeId, String fieldId,
                               @Nullable String before, @Nullable String after) implements Edit {

    @Override
    public void apply() {
        write(after);
    }

    @Override
    public void undo() {
        write(before);
    }

    private void write(@Nullable String value) {
        NodeData live = document.node(nodeId);
        // Gone: the node was deleted by a later edit that has not been undone yet. Nothing to write, and
        // nothing wrong — undoing that deletion will restore the node with this value already in it.
        if (live == null) return;
        document.replaceNode(live.withProperty(fieldId, value));
    }

    /**
     * The value this edit records, read from the document rather than assumed.
     *
     * <p>Use this to construct one: the "before" has to be what is genuinely stored, including absent,
     * or undo writes a value the node never had.</p>
     */
    public static SetNodeFieldEdit of(GraphDocument document, String nodeId, String fieldId,
                                      @Nullable String newValue) {
        NodeData live = document.node(nodeId);
        String current = live == null ? null : live.properties().get(fieldId);
        return new SetNodeFieldEdit(document, nodeId, fieldId, current, newValue);
    }

    /** Whether this would actually change anything — a caller should not push a no-op onto the stack. */
    public boolean changesAnything() {
        return !Objects.equals(before, after);
    }

    @Override
    @Nullable
    public Edit mergeWith(Edit next) {
        if (!(next instanceof SetNodeFieldEdit later)) return null;
        if (!nodeId.equals(later.nodeId) || !fieldId.equals(later.fieldId)) return null;
        // Keeps THIS edit's before and the later one's after, so undoing the merged pair lands where the
        // first one started rather than in the middle of the run.
        return new SetNodeFieldEdit(document, nodeId, fieldId, before, later.after);
    }

    @Override
    public String label() {
        return "set " + fieldId;
    }
}
