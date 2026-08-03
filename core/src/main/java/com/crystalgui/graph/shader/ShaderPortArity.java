package com.crystalgui.graph.shader;

import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.graph.PortType;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows a concrete width AND colour on every {@code dynamic} port — Unity's {@code A(1) B(1) Out(1)} in
 * the scalar colour, becoming {@code (3)} and green the moment a vec3 is wired in.
 *
 * <h3>Why the label cannot come from the port's type</h3>
 * <p>{@link com.crystalgui.ui.elements.graph.PortType#arity()} is a property of the TYPE, so every port
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
        view.onConnectionsChanged.connect(() -> resolve(view));
        resolve(view);
    }

    /** Recomputes every dynamic port's displayed width. Cheap and idempotent; safe to call at any time. */
    public static void resolve(GraphView view) {
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
                port.setResolvedArity(arity);
                // Colour follows the same resolution the label does. A dynamic port is otherwise stuck on
                // the flat "unknown" grey `type-dynamic` paints, where Unity shows the resolved type's own
                // colour — the scalar one before anything is wired in. The WIRE follows for free: it reads
                // the dot's computed border-colour back out of the cascade, so re-classing the port is the
                // whole change. See NodePort.setResolvedTypeClass.
                port.setResolvedTypeClass(typeClassFor(arity));
            }
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
