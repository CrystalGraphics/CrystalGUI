package com.crystalgui.ui.elements.graph;

import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.SetNodeFieldEdit;
import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;

/**
 * Puts a node type's editable fields onto a node widget, and writes changes back through the undo stack.
 *
 * <h3>Domain-agnostic on purpose</h3>
 * <p>Nothing here knows about shaders. A field is a declaration on {@link NodeType}, the widget comes from
 * {@link NodeFieldWidgets}, and the write is a {@link SetNodeFieldEdit} — so a dialogue graph, a state
 * machine and a material graph all get inline editors from the same code. The shader library reaches this
 * by <em>describing</em> its properties as fields, not by having its own control layer.</p>
 *
 * <h3>Two placements, one mechanism</h3>
 * <ul>
 *   <li><b>Body fields</b> go in the node's {@code __controls__} row, labelled.</li>
 *   <li><b>Port fields</b> become the port's {@link NodePort#getDefaultEditor()} — a floating widget
 *       {@code GraphView} places beside the port and shows only while it is unconnected, the behaviour
 *       {@code nodeport:blank} exists to express. This is why an unconnected {@code Value} can be typed
 *       into without any node needing a matching setting.</li>
 * </ul>
 */
public final class NodeFieldBinder {

    private NodeFieldBinder() {
    }

    /**
     * Builds and attaches every field the type declares.
     *
     * <p>Idempotent per widget only in the sense that the caller must not call it twice — controls are
     * internal children with no cheap "is one already there" query, so a second call silently doubles
     * them. Track attachment by node id.</p>
     *
     * @param undo     where changes are recorded; when null the field still edits, just not undoably
     * @param onChange run after a change is written, for a caller that needs to recompile or re-render
     */
    public static void attach(GraphNode widget, NodeType type, GraphDocument document,
                              @Nullable UndoStack undo, @Nullable Runnable onChange) {
        String nodeId = widget.getNodeId();
        if (nodeId == null) return;

        for (NodeField field : type.fields()) {
            String current = currentValue(document, nodeId, field);
            UIElement control = NodeFieldWidgets.create(field, current,
                    value -> write(document, undo, nodeId, field, value, onChange));
            if (control == null) continue;

            if (field.isPortField()) {
                NodePort port = widget.portNamed(field.portId());
                // A field naming a port the widget does not have is a declaration bug, but a silently
                // missing editor is worse than a missing one you can see — so it falls back to the body.
                if (port != null) {
                    port.setDefaultEditor(control);
                    continue;
                }
            }
            widget.addControl(field.label(), control);
        }
    }

    private static String currentValue(GraphDocument document, String nodeId, NodeField field) {
        NodeData live = document.node(nodeId);
        return field.resolve(live == null ? null : live.properties().get(field.id()));
    }

    private static void write(GraphDocument document, @Nullable UndoStack undo, String nodeId,
                              NodeField field, String value, @Nullable Runnable onChange) {
        SetNodeFieldEdit edit = SetNodeFieldEdit.of(document, nodeId, field.id(), value);
        // A no-op must not reach the stack: selecting the value that is already set would otherwise cost
        // the user an undo press that appears to do nothing.
        if (!edit.changesAnything()) return;

        if (undo != null) {
            undo.execute(edit);
        } else {
            edit.apply();
        }
        if (onChange != null) onChange.run();
    }
}
