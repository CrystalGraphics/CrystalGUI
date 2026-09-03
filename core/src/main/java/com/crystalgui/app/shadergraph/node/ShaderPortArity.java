package com.crystalgui.app.shadergraph.node;

import com.crystalgui.app.shadergraph.ShaderGraphBridge;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.graph.node.NodeFieldBinder;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.graph.port.PortType;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows a concrete width AND colour on every {@code dynamic} port — Unity's {@code A(1) B(1) Out(1)} in
 * the scalar colour, becoming {@code (3)} and green the moment a vec3 is wired in.
 *
 * <h3>Why the label cannot come from the port's type</h3>
 * <p>{@link com.crystalgui.graph.port.PortType#arity()} is a property of the TYPE, so every port
 * sharing it necessarily reports the same number — and a {@code dynamic} port has no width at all until
 * something is connected, so the only honest answer it can give is 0 (no suffix). That is why an unwired
 * {@code Multiply} read a bare {@code A B Out} here while Unity's reads {@code A(1) B(1) Out(1)}. The
 * number is per-port and changes as the graph is rewired, so it is resolved here and pushed onto the port
 * via {@link NodePort#setResolvedArity}.</p>
 *
 * <h3>The rule is the compiler's, mirrored — not a second opinion</h3>
 * <p>{@code CgShaderType.resolveDynamic} decides a node's dynamic width by taking the <b>narrowest
 * non-scalar</b> type reaching <em>any</em> of its dynamic ports, and applying that one answer to all of
 * them together — so {@code Add(vec4, vec2)} is a vec2 node with the vec4 truncated, and
 * {@code Add(float, vec3)} is a vec3 because a scalar promotes and never decides the width. Unwired, it
 * falls back to {@code float}. This walks the same rule over the editor's own arities, so the label a
 * user reads and the GLSL that eventually gets emitted cannot disagree — which is the entire reason it
 * mirrors rather than invents.</p>
 *
 * <h3>A fixpoint, because a chain's width flows downstream</h3>
 * <p>A node's resolved width feeds its downstream neighbours, so one pass in whatever order the edges
 * happen to be stored would leave a chain reporting stale numbers until the next edit. A topological
 * sort would work; iterating to a fixpoint is simpler and cannot be defeated by a cycle. Each pass
 * recomputes every node from scratch rather than accumulating — with a narrowest rule an accumulated
 * minimum could never recover when an edge is removed. The pass count is bounded explicitly: a graph
 * that somehow failed to settle must stop, not spin.</p>
 */
public final class ShaderPortArity {

    /** {@code float} — what an unconnected dynamic port reports, matching the compiler's own fallback
     * ("a float is the identity of the promotion order"). */
    private static final int DEFAULT_ARITY = 1;

    /** A chain can only propagate a width one node per pass, so this bounds the fixpoint. Generous for
     * any real graph, and a hard stop for one that somehow will not settle. */
    private static final int MAX_PASSES = 8;

    private ShaderPortArity() {
    }

    /**
     * Keeps {@code view}'s dynamic port labels in step with its wiring, from now on.
     *
     * <p>Resolves once immediately — a graph loaded with edges already in it must read correctly before
     * anything is touched — then again on every connection change.</p>
     */
    public static void install(GraphView view) {
        install(view, null);
    }

    /**
     * As {@link #install(GraphView)}, but also rebuilding a dynamic port's inline editor to match the
     * width it resolves to — {@code B} becoming three boxes when a vec3 lands on {@code A}.
     *
     * @param onChange run after a rebuilt editor writes a value, for a caller that needs to recompile
     */
    public static void install(GraphView view, @Nullable Runnable onChange) {
        view.onConnectionsChanged.connect(() -> resolve(view, onChange));
        resolve(view, onChange);
    }

    /** Recomputes every dynamic port's displayed width. Cheap and idempotent; safe to call at any time. */
    public static void resolve(GraphView view) {
        resolve(view, null);
    }

    /** @param onChange run after a rebuilt inline editor writes a value; null to leave editors alone */
    public static void resolve(GraphView view, @Nullable Runnable onChange) {
        GraphDocument document = view.getDocument();
        Map<String, GraphNode> byId = new HashMap<>();
        for (GraphNode node : view.nodes()) {
            if (node.getNodeId() != null) byId.put(node.getNodeId(), node);
        }
        if (byId.isEmpty()) return;

        // nodeId -> the width its dynamic ports currently report. Seeded at float — an unwired dynamic
        // node's answer — and re-derived from the edges on each pass below.
        Map<String, Integer> resolved = new HashMap<>();
        for (String id : byId.keySet()) resolved.put(id, DEFAULT_ARITY);

        List<EdgeData> edges = document.edges();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            // Recomputed from scratch each pass rather than accumulated, because the rule is NARROWEST:
            // an accumulating min could never recover if an edge went away, and a node's answer depends
            // on upstream answers that are themselves still settling.
            Map<String, Integer> next = new HashMap<>();
            for (EdgeData edge : edges) {
                GraphNode target = byId.get(edge.to().nodeId());
                GraphNode source = byId.get(edge.from().nodeId());
                if (target == null || source == null) continue;

                NodePort inPort = target.portNamed(edge.to().portId());
                NodePort outPort = source.portNamed(edge.from().portId());
                // Only a DYNAMIC input takes its width from upstream. A concretely-typed input keeps its
                // own — wiring a float into a vec2 slot promotes the value, it does not resize the slot.
                if (inPort == null || outPort == null || !isDynamic(inPort)) continue;

                int incoming = isDynamic(outPort)
                        ? resolved.getOrDefault(edge.from().nodeId(), DEFAULT_ARITY)
                        : outPort.getType().arity();
                // A scalar never decides the width (it promotes), and a type with no meaningful arity —
                // a texture, a sampler — says nothing about it either. Same two exclusions
                // CgShaderType.resolveDynamic makes.
                if (incoming <= DEFAULT_ARITY) continue;

                String targetId = edge.to().nodeId();
                Integer sofar = next.get(targetId);
                if (sofar == null || incoming < sofar) next.put(targetId, incoming);
            }
            for (String id : byId.keySet()) {
                int arity = next.getOrDefault(id, DEFAULT_ARITY);
                if (resolved.get(id) != arity) {
                    resolved.put(id, arity);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        for (Map.Entry<String, GraphNode> entry : byId.entrySet()) {
            int arity = resolved.getOrDefault(entry.getKey(), DEFAULT_ARITY);
            for (NodePort port : entry.getValue().getPorts()) {
                // Applied to inputs AND outputs together, which is the half of the compiler's rule that
                // makes Add(float, vec3) read (3) on every one of its ports rather than (1) on the float
                // input it happens to still be fed by.
                if (!isDynamic(port)) continue;
                boolean widthChanged = port.setResolvedArity(arity);
                // Colour follows the same resolution the label does. A dynamic port is otherwise stuck on
                // the flat "unknown" grey `type-dynamic` paints, where Unity shows the resolved type's own
                // colour — the scalar one before anything is wired in. The WIRE follows for free: it reads
                // the dot's computed border-colour back out of the cascade, so re-classing the port is the
                // whole change. See NodePort.setResolvedTypeClass.
                port.setResolvedTypeClass(typeClassFor(arity));
                // Only on a real change: rebuilding every pass would throw away a control the user may
                // be mid-edit in, and setResolvedArity is the one thing that knows the width moved.
                if (widthChanged && onChange != null) {
                    rebuildInlineEditor(view, entry.getKey(), port, arity, onChange);
                }
            }
        }
    }

    /**
     * Replaces a dynamic input's inline editor with one of {@code arity} components — Unity's {@code B}
     * turning into {@code X 2  Y 2  Z 2} when a vec3 lands on {@code A}.
     *
     * <p>A control cannot restructure itself: how many boxes a {@code VectorControl} draws is fixed when
     * it is built. So the editor is rebuilt and handed to the port, and
     * {@link NodePort#onDefaultEditorChanged} carries it the rest of the way — {@code PortDefaultEditor}
     * already swaps a control in place, which is the machinery this reuses rather than duplicates.</p>
     *
     * <p>The stored literal is re-shaped to match, <b>splatting a scalar across the new components</b>
     * rather than zero-filling: Unity shows {@code X 2 Y 2 Z 2} after a {@code 2} was widened, and
     * keeping the value the user typed on every axis is what makes the widening feel like a change of
     * shape rather than a reset.</p>
     */
    private static void rebuildInlineEditor(GraphView view, String nodeId, NodePort port, int arity,
                                            Runnable onChange) {
        if (!port.getDirection().isInput()) return;
        // No editor means the port never had a document-declared field — nothing to re-shape.
        if (port.getDefaultEditor() == null) return;

        NodeData data = view.getDocument().node(nodeId);
        String stored = data == null ? null : data.properties().get(port.getPortId());
        NodeField field = fieldAtArity(port.getPortId(), stored, arity);

        // The literal is passed as the PRESET rather than left to the document: a widget infers its
        // shape from the value it is handed, so a stored scalar would build two boxes for a vec3.
        UIElement rebuilt = NodeFieldBinder.buildControl(field, view.getDocument(), nodeId,
                view.undoStack(), onChange, field.defaultValue());
        if (rebuilt != null) port.setDefaultEditor(rebuilt);
    }

    /**
     * The field a dynamic port <b>actually edits right now</b>, at a given resolved width.
     *
     * <p>Extracted so the node's inline editor and the inspector's row cannot disagree about it. They
     * did: the node rebuilt {@code B} into {@code X Y} when a vec2 arrived, and the inspector went on
     * showing one box holding {@code 1}, because it built from the <em>declared</em> field and the
     * declaration says nothing about width — that is what {@code dynamic} means. Two places deciding the
     * same thing from different inputs is a disagreement waiting to be noticed by a user rather than a
     * compiler.</p>
     *
     * <p>The returned field carries the re-shaped literal as its default, so a caller passes
     * {@code field.defaultValue()} straight back as the preset.</p>
     */
    public static NodeField fieldAtArity(String portId, @Nullable String stored, int arity) {
        String literal = literalAtArity(stored, arity);
        return arity <= 1
                ? new NodeField(portId, portId, NodeField.Kind.NUMBER, List.of(), literal, portId)
                : new NodeField(portId, portId, NodeField.Kind.VECTOR, List.of(), literal, portId);
    }

    /**
     * As {@link #fieldAtArity}, reading the width off the port itself and keeping {@code declared}'s
     * label — what an inspector wants, since a row is labelled and an inline editor is not.
     *
     * <p>Returns {@code declared} unchanged for anything that is not a dynamic port, so a concretely
     * typed {@code vec3} input is never second-guessed.</p>
     */
    public static NodeField fieldFor(NodeField declared, NodePort port, @Nullable String stored) {
        if (port == null || !port.getDirection().isInput()) return declared;
        if (!ShaderGraphBridge.DYNAMIC_TYPE.equals(port.getType().id())) return declared;
        NodeField reshaped = fieldAtArity(declared.id(), stored, port.displayedArity());
        return new NodeField(declared.id(), declared.label(), reshaped.kind(), reshaped.options(),
                reshaped.defaultValue(), declared.portId());
    }

    /**
     * {@code stored} re-shaped to {@code arity} components — a bare number at 1, {@code vecN(...)} above.
     *
     * <p>A single existing component is splatted across all of them; anything beyond what was stored is
     * zero. Reading the components back out rather than starting from the port's declared default is what
     * preserves a value the user typed while the port was narrower.</p>
     */
    static String literalAtArity(@Nullable String stored, int arity) {
        double[] parsed = componentsOf(stored);
        if (arity <= 1) return ShaderVectorFieldWidget.formatScalar(parsed.length > 0 ? parsed[0] : 0d);
        double[] out = new double[arity];
        for (int i = 0; i < arity; i++) {
            if (i < parsed.length) out[i] = parsed[i];
            else if (parsed.length == 1) out[i] = parsed[0];   // splat, per the note above
        }
        return ShaderVectorFieldWidget.format(out);
    }

    /** The numbers in {@code vecN(a, b, ...)} or a bare scalar; empty when it is neither. */
    private static double[] componentsOf(@Nullable String stored) {
        if (stored == null || stored.isBlank()) return new double[0];
        if (stored.indexOf('(') >= 0) return ShaderVectorFieldWidget.parse(stored);
        try {
            return new double[] { Double.parseDouble(stored.trim()) };
        } catch (NumberFormatException notANumber) {
            return new double[0];
        }
    }

    /**
     * The {@code type-*} class a resolved width is drawn through, or {@code null} to leave the port on its
     * declared type.
     *
     * <p>Named from the GLSL type rather than the number so it lands on the palette {@code graph.css}
     * already defines for concrete ports — a resolved vec3 is exactly as green as a declared one, which is
     * the point: a user reading the graph should not be able to tell which ports were declared and which
     * were inferred.</p>
     */
    @Nullable
    private static String typeClassFor(int arity) {
        String glsl = switch (arity) {
            case 1 -> "float";
            case 2 -> "vec2";
            case 3 -> "vec3";
            case 4 -> "vec4";
            default -> null;
        };
        return glsl == null ? null : PortType.CSS_CLASS_PREFIX + glsl;
    }

    private static boolean isDynamic(NodePort port) {
        return ShaderGraphBridge.DYNAMIC_TYPE.equals(port.getType().id());
    }
}
