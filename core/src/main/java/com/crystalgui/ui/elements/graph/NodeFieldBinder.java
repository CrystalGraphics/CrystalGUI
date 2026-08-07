package com.crystalgui.ui.elements.graph;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.SetNodeFieldEdit;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.config.ConfigControl;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
            UIElement control = buildControl(field, document, nodeId, undo, onChange);
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

    /**
     * Builds one field's control, reading its current value from the document and wiring it to write
     * back through {@code undo} — the same thing {@link #attach} does per field, exposed so a caller can
     * REBUILD one later without duplicating the write path.
     *
     * <p>Rebuilding is a real need rather than a hypothetical: a shader graph's {@code dynamic} port
     * changes how many components it edits as the graph is rewired ({@code A} becomes three boxes when a
     * vec3 arrives), and a control cannot restructure itself — the widget kind and its arity are decided
     * at construction. Whoever knows the new shape passes a field describing it; everything about where
     * the value comes from and where it goes stays here, so there is only ever one writer.</p>
     *
     * @return the control, or {@code null} when nothing is registered for the field's kind
     */
    @Nullable
    public static UIElement buildControl(NodeField field, GraphDocument document, String nodeId,
                                         @Nullable UndoStack undo, @Nullable Runnable onChange) {
        return buildControl(field, document, nodeId, undo, onChange, null);
    }

    /**
     * As {@link #buildControl(NodeField, GraphDocument, String, UndoStack, Runnable)}, but starting from
     * {@code presetValue} instead of what the document currently holds.
     *
     * <p>For a rebuild that changes the control's SHAPE. A widget may infer its shape from the value it
     * is handed — {@code ShaderVectorFieldWidget} counts the components in {@code vecN(...)} to decide how
     * many boxes to draw — so re-shaping to three components while the document still holds the scalar
     * {@code 1.0} would build the wrong widget from the right intent. The document is left alone: the
     * stored literal is still valid for the port (a scalar promotes), and it is rewritten the moment the
     * user actually edits one of the new boxes.</p>
     */
    @Nullable
    public static UIElement buildControl(NodeField field, GraphDocument document, String nodeId,
                                         @Nullable UndoStack undo, @Nullable Runnable onChange,
                                         @Nullable String presetValue) {
        String current = presetValue != null ? presetValue : currentValue(document, nodeId, field);
        // What this binding last put INTO the document, so a change arriving from anywhere else can be
        // told apart from the echo of its own write.
        String[] lastWritten = { current };

        UIElement control = NodeFieldWidgets.create(field, current, value -> {
            lastWritten[0] = value;
            write(document, undo, nodeId, field, value, onChange);
        });
        bracketGestures(control, undo);
        followDocument(control, document, nodeId, field, lastWritten, onChange);
        return control;
    }

    /**
     * One control writing the same field on <b>several</b> nodes, as a single undo step.
     *
     * <p>What an inspector needs for a multi-selection, and the reason it is here rather than there:
     * everything about where a value comes from and where it goes stays in this class, so there is still
     * exactly one writer however many nodes are on the far end of it.</p>
     *
     * <p>The control shows {@code displayNodeId}'s value, which is what every inspector does with a
     * multi-selection — the write applies to all of them regardless of what they held, so the displayed
     * value is a starting point rather than a claim that they agree.</p>
     *
     * <p>A {@link CompositeEdit} rather than N pushes: N pushes is N presses of Ctrl+Z to undo one
     * action. It undoes in reverse, which costs nothing here (these edits are independent) but is the
     * behaviour the type guarantees and the reason not to hand-roll a loop.</p>
     *
     * @param nodeIds every node to write; ones that no longer exist are skipped at apply time
     */
    @Nullable
    public static UIElement buildMultiControl(NodeField field, GraphDocument document,
                                              List<String> nodeIds, String displayNodeId,
                                              @Nullable UndoStack undo, @Nullable Runnable onChange) {
        String current = currentValue(document, displayNodeId, field);
        UIElement control = NodeFieldWidgets.create(field, current, value -> {
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
        });
        bracketGestures(control, undo);
        return control;
    }

    /**
     * Makes the widget follow the document, not only drive it.
     *
     * <h3>This is what made undo look broken</h3>
     * <p>An {@code Edit} mutates the document directly — that is the whole point of the pattern. Nothing
     * carried the result back to the control that had been displaying the old value, and nothing re-ran
     * {@code onChange}, so undoing a field edit changed the document and <b>nothing visible happened</b>:
     * the number box kept showing the value that had just been undone, and the shader never recompiled.
     * Press Ctrl+Z, see nothing, press again, and watch some earlier action disappear instead — which is
     * exactly how it was reported, and why every investigation went looking at the undo stack. The stack
     * was correct the whole time.</p>
     *
     * <p>Only fires for a change this binding did not make: an edit written from here already left the
     * control holding that value, and re-applying it would fight a caret mid-type. Skipped entirely while
     * a gesture is live, so a scrub is not interrupted by its own per-frame writes.</p>
     */
    private static void followDocument(@Nullable UIElement control, GraphDocument document, String nodeId,
                                       NodeField field, String[] lastWritten, @Nullable Runnable onChange) {
        if (control == null) return;
        Connection following = document.onChanged.connect(() -> {
            if (control instanceof ConfigControl config && config.isInteracting()) return;
            String live = currentValue(document, nodeId, field);
            if (java.util.Objects.equals(live, lastWritten[0])) return;
            lastWritten[0] = live;
            NodeFieldWidgets.applyValue(field, control, live);
            // The other half: whatever recompiles or re-renders has to hear about it too, or the picture
            // stays as stale as the widget did.
            if (onChange != null) onChange.run();
        });
        // TRACKED, for the same reason SettingsConfigurator tracks its own: a GraphDocument lives as long
        // as the file is open, and the inspector rebuilds this control on every selection change — so an
        // untracked connection meant one dead listener per field per click, walked on every document
        // write for the rest of the session.
        if (control instanceof ConfigControl config) config.connections().add(following);
    }

    /**
     * Makes a continuous gesture — a scrub, a slider drag — <b>one</b> undo step.
     *
     * <p>{@link #write} records an edit per change, which is right for typing and picking and wrong for a
     * drag: a scrub emits a value every frame, so a two-second one would put ~120 entries on the stack and
     * leave Ctrl+Z useless. The values still have to arrive live, or the node preview would not recompile
     * until the button came up.</p>
     *
     * <p>Both at once is what a held merge run is for. {@code SetNodeFieldEdit.mergeWith} already collapses
     * consecutive writes to the same field into a single edit keeping the first {@code before} and the
     * last {@code after} — so the run costs one stack entry, not a composite of a hundred. Holding it is
     * what makes the collapse independent of how long the user lingered; see
     * {@link UndoStack#beginMergeRun()}.</p>
     */
    private static void bracketGestures(@Nullable UIElement control, @Nullable UndoStack undo) {
        if (undo == null || !(control instanceof ConfigControl config)) return;
        config.interacting.connect(active -> {
            if (Boolean.TRUE.equals(active)) undo.beginMergeRun();
            else undo.endMergeRun();
        });
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
