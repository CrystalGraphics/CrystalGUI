package com.crystalgui.widget.graph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.dom.UIElement;

/**
 * The floating default editors on a graph's input ports — one per port, for that port's whole life.
 *
 * <p>A port whose value is not wired carries a small control on the plane beside its dot. This owns
 * every one of them: when a port acquires one, whether it is shown, where it sits, and when it goes.</p>
 *
 * <pre>{@code
 * ports.watchAll(node);   // a node joined the view
 * ports.reposition();     // once a frame, after layout
 * ports.forget(port);     // the port's node left
 * }</pre>
 *
 * <p>Reached from a {@link GraphView}, never built by anything else.</p>
 */
final class GraphPorts {

    private final GraphView view;

    private final Map<NodePort, PortDefaultEditor> editors = new LinkedHashMap<>();

    /** Ports already given an editor. What keeps {@link #watch} idempotent. */
    private final Set<NodePort> watched = new LinkedHashSet<>();

    GraphPorts(GraphView view) {
        this.view = view;
    }

    /**
     * Puts every mounted editor back over its port.
     *
     * <p>Per-frame for real: an editor is positioned in world space off its port's live layout, so it
     * moves whenever the plane pans, zooms or reflows, and there is no announcement for "the geometry
     * under me settled".</p>
     */
    void reposition() {
        for (PortDefaultEditor editor : editors.values()) {
            if (editor.isMounted()) editor.reposition();
        }
    }

    /** Gives every input port on {@code node} an editor, once. */
    void watchAll(GraphNode node) {
        for (NodePort port : node.getInputPorts()) watch(port);
    }

    /**
     * Gives one input port its editor, once.
     *
     * <p>A port becomes visible to a view at exactly two moments and this is called from both: a node is
     * registered with ports already on it, or {@link GraphNode#addPort} adds one to a node already in a
     * view. It used to be a per-frame scan over every port of every node — O(nodes x ports) for an answer
     * that changes a handful of times in a session.</p>
     *
     * <p>Ports are never removed from a node, only whole nodes through {@link #forget}, so the watch set
     * needs no pruning beyond that.</p>
     */
    void watch(NodePort port) {
        if (port == null || !port.getDirection().isInput()) return;
        if (!watched.add(port)) return;
        PortDefaultEditor editor = new PortDefaultEditor(port, view);
        editors.put(port, editor);
        port.onBlankChanged.connect(() -> refresh(port));
        port.onDefaultEditorChanged.connect(() -> refresh(port));
        refresh(port);
    }

    /**
     * Brings a port's editor back in step with the port — the control it wraps, and whether it belongs
     * on the plane at all.
     *
     * <p><b>The editor is swapped in place, never rebuilt.</b> The control can arrive after the widget
     * exists, and rebuilding on that change put one control in two Taffy parents at once: laid out under
     * both, the wrong pass winning, so a vector editor's X/Y fields drew hundreds of pixels from their own
     * frame. {@link PortDefaultEditor#syncControl} detaches explicitly instead, so there is only ever one
     * owner.</p>
     */
    private void refresh(NodePort port) {
        PortDefaultEditor editor = editors.get(port);
        if (editor == null) return;
        editor.syncControl();
        // Nothing to show yet: mounting an empty box draws a stray frame beside the port until the
        // binder catches up.
        editor.setMounted(editor.hasControl() && port.isBlank());
    }

    /**
     * Draws the stub joining a port's floating editor to its own dot.
     *
     * <p>Called from {@link GraphNode#paintDecoration}, because that is the only hook running after a
     * node's children and before its own outline — which is what makes "over the body, under the ring"
     * possible at all. No sibling element, however z-ordered, can land between two steps of one other
     * element's atomic paint call.</p>
     */
    void paintStub(CgUiPaintContext ctx, NodePort port, UIElement space) {
        PortDefaultEditor editor = editors.get(port);
        if (editor != null && editor.isMounted()) editor.paintStub(ctx, space);
    }

    /** Drops a port's editor from the plane and forgets it — a floating box is nobody's descendant, so
     * removing the port's node cannot reach it. */
    void forget(NodePort port) {
        PortDefaultEditor editor = editors.remove(port);
        if (editor != null) editor.setMounted(false);
        watched.remove(port);
    }

    /** The editor tracked for {@code port}, or null if it never had one. Tests reach the mechanism here
     * rather than through pixels. */
    @Nullable
    PortDefaultEditor editorFor(NodePort port) {
        return editors.get(port);
    }
}
