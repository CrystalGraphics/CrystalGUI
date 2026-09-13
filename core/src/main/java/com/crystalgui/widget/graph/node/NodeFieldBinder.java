package com.crystalgui.widget.graph.node;

import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.SetNodeFieldEdit;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.NodePort;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts a node type's editable fields onto a node widget, each bound to its value in the document.
 *
 * <pre>{@code
 * NodeFieldBinder.attach(nodeWidget, type, document, undoStack, preview::recompile);
 * }</pre>
 *
 * <p>A field's value is a {@link Property} over the document ({@link #property}): an edit is a
 * {@link SetNodeFieldEdit} on the undo stack, a scrub is one of them however long it lasts, and an undo —
 * or any other change to the document — reaches the editor showing the field and re-runs
 * {@code onChange}, so a preview recompiles for Ctrl+Z as it does for typing.</p>
 *
 * <h3>Domain-agnostic on purpose</h3>
 * <p>Nothing here knows about shaders. A field is a declaration on {@link NodeType}, the editor comes from
 * {@link NodeFieldWidgets}, and the write is a {@link SetNodeFieldEdit} — so a dialogue graph, a state
 * machine and a material graph all get inline editors from the same code.</p>
 *
 * <h3>Two placements, one mechanism</h3>
 * <ul>
 *   <li><b>Body fields</b> go in the node's {@code __controls__} row, labelled.</li>
 *   <li><b>Port fields</b> become the port's {@link NodePort#getDefaultEditor()} — a floating editor
 *       shown only while the port is unconnected.</li>
 * </ul>
 */
public final class NodeFieldBinder {

    private NodeFieldBinder() {
    }

    /**
     * Builds and attaches every field the type declares.
     *
     * <p>The caller must not call it twice for one widget — a second call doubles the controls. Track
     * attachment by node id.</p>
     *
     * @param undo     where edits are recorded; when null a field still edits, just not undoably
     * @param onChange run after the field's value changes, for a caller that recompiles or re-renders
     */
    public static void attach(GraphNode widget, NodeType type, GraphDocument document,
                              @Nullable UndoStack undo, @Nullable Runnable onChange) {
        String nodeId = widget.getNodeId();
        if (nodeId == null) return;

        for (NodeField field : type.fields()) {
            ConfigControl control = buildControl(field, document, nodeId, undo, onChange);
            if (control == null) continue;

            if (field.isPortField()) {
                NodePort port = widget.portNamed(field.portId());
                // A field naming a port the widget does not have is a declaration bug, but a silently
                // missing editor is worse than a misplaced one you can see — so it falls back to the body.
                if (port != null) {
                    port.setDefaultEditor(control);
                    continue;
                }
            }
            widget.addControl(field.label(), control);
        }
    }

    /**
     * A field's stored text in the document, as a property — the value every editor of it binds to.
     *
     * <pre>{@code
     * Property<String> stored = NodeFieldBinder.property(field, document, nodeId, undo, preview::recompile);
     * }</pre>
     *
     * <p>Setting it records a {@link SetNodeFieldEdit} (nothing, for a value already there); it follows the
     * document's own change signal; and {@code onChange} runs whenever the value moves, whoever moved it.</p>
     */
    public static Property<String> property(NodeField field, GraphDocument document, String nodeId,
                                            @Nullable UndoStack undo, @Nullable Runnable onChange) {
        Property<String> stored = Property.<String>derived(() -> currentValue(document, nodeId, field),
                        value -> write(document, undo, nodeId, field, value))
                .announcedBy(refresh -> document.onChanged.connect(refresh))
                .editedIn(undo);
        if (onChange != null) stored.changed.connect((was, now) -> onChange.run());
        return stored;
    }

    /**
     * Builds one field's editor, bound to its value in the document.
     *
     * <p>Exposed so a caller can REBUILD one later — a shader graph's dynamic port changes how many
     * components it edits as the graph is rewired, and a control cannot restructure itself.</p>
     *
     * @return the control, or {@code null} when nothing is registered for the field's kind
     */
    @Nullable
    public static ConfigControl buildControl(NodeField field, GraphDocument document, String nodeId,
                                             @Nullable UndoStack undo, @Nullable Runnable onChange) {
        return NodeFieldWidgets.create(field, property(field, document, nodeId, undo, onChange));
    }

    /**
     * As {@link #buildControl(NodeField, GraphDocument, String, UndoStack, Runnable)}, showing
     * {@code presetValue} until the document's own value moves or the field is edited.
     *
     * <p>For a rebuild that changes the control's SHAPE. A factory infers the shape from the value it is
     * handed — {@code vecN(...)}'s component count — so re-shaping to three components while the document
     * still holds the scalar {@code 1.0} would build the wrong editor from the right intent. The document
     * is left alone: the stored scalar is still valid for the port, and is rewritten on the first edit.</p>
     */
    @Nullable
    public static ConfigControl buildControl(NodeField field, GraphDocument document, String nodeId,
                                             @Nullable UndoStack undo, @Nullable Runnable onChange,
                                             @Nullable String presetValue) {
        Property<String> stored = property(field, document, nodeId, undo, onChange);
        return NodeFieldWidgets.create(field, presetValue == null ? stored : preset(stored, presetValue));
    }

    /** {@code stored}, showing {@code value} instead until it moves or is written. */
    private static Property<String> preset(Property<String> stored, String value) {
        String[] shown = {value};
        stored.refresh();
        return Property.<String>derived(() -> shown[0] != null ? shown[0] : stored.get(),
                        next -> {
                            shown[0] = null;
                            stored.set(next);
                        })
                .announcedBy(refresh -> {
                    Connection moved = stored.changed.connect((was, now) -> {
                        shown[0] = null;
                        refresh.run();
                    });
                    Connection source = stored.watchSource();
                    return () -> {
                        moved.disconnect();
                        source.disconnect();
                    };
                })
                .editedIn(stored.history());
    }

    /**
     * One editor writing the same field on <b>several</b> nodes, as a single undo step.
     *
     * <p>It shows {@code displayNodeId}'s value, which is what every inspector does with a multi-selection
     * — the write applies to all of them regardless, so the displayed value is a starting point rather
     * than a claim that they agree. A {@link CompositeEdit} rather than N pushes, so one Ctrl+Z undoes it.</p>
     *
     * @param nodeIds every node to write; ones that no longer exist are skipped at apply time
     */
    @Nullable
    public static ConfigControl buildMultiControl(NodeField field, GraphDocument document,
                                                  List<String> nodeIds, String displayNodeId,
                                                  @Nullable UndoStack undo, @Nullable Runnable onChange) {
        Property<String> shared = Property.<String>derived(
                        () -> currentValue(document, displayNodeId, field),
                        value -> {
                            List<Edit> edits = new ArrayList<>();
                            for (String nodeId : nodeIds) {
                                SetNodeFieldEdit edit = SetNodeFieldEdit.of(document, nodeId, field.id(), value);
                                if (edit.changesAnything()) edits.add(edit);
                            }
                            if (edits.isEmpty()) return;
                            Edit combined = edits.size() == 1 ? edits.get(0)
                                    : new CompositeEdit(edits, "set " + field.id() + " on " + edits.size() + " nodes");
                            if (undo != null) undo.execute(combined);
                            else combined.apply();
                            if (onChange != null) onChange.run();
                        })
                .announcedBy(refresh -> document.onChanged.connect(refresh))
                .editedIn(undo);
        return NodeFieldWidgets.create(field, shared);
    }

    private static String currentValue(GraphDocument document, String nodeId, NodeField field) {
        NodeData live = document.node(nodeId);
        return field.resolve(live == null ? null : live.properties().get(field.id()));
    }

    private static void write(GraphDocument document, @Nullable UndoStack undo, String nodeId,
                              NodeField field, String value) {
        SetNodeFieldEdit edit = SetNodeFieldEdit.of(document, nodeId, field.id(), value);
        // A no-op must not reach the stack: selecting the value that is already set would otherwise cost
        // the user an undo press that appears to do nothing.
        if (!edit.changesAnything()) return;
        if (undo != null) undo.execute(edit);
        else edit.apply();
    }
}
