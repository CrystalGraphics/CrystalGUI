package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.ClipboardActions;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphIds;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.PortRef;
import com.crystalgui.widget.surface.edit.Clipboard;

/**
 * What copying and pasting <em>mean</em> in a graph.
 *
 * <p>A fragment here is a detached {@link GraphDocument}: the selected nodes and every wire between
 * them. The engine holds what was copied and owns the commands — this only says what a graph puts in
 * and takes out.</p>
 *
 * <pre>{@code
 * GraphDocument clip = clipboard.copySelection();   // null when nothing is selected
 * clipboard.pasteAt(clip, worldX, worldY);          // corner of the group lands on the point
 * clipboard.duplicateSelection(16f, 16f);           // never touches the clipboard
 * }</pre>
 */
final class GraphClipboard {

    private final GraphView view;

    GraphClipboard(GraphView view) {
        this.view = view;
    }

    /**
     * The selected nodes and every wire <b>between</b> them, as a detached document.
     *
     * <p>Wires to nodes outside the selection are dropped, which is {@link GraphDocument#copyOf}'s own
     * rule and the right one: an edge needs both ends, and half an edge is not a thing a paste could
     * restore. Copying two ends of a chain without its middle gives you the two ends.</p>
     *
     * @return null when nothing is selected, so a caller can leave the clipboard alone rather than
     *         emptying it — copying nothing should not lose what you copied a minute ago
     */
    @Nullable
    GraphDocument copySelection() {
        List<String> ids = new ArrayList<>();
        for (GraphNode node : view.getSelection().nodes()) {
            if (node.getNodeId() != null) ids.add(node.getNodeId());
        }
        if (ids.isEmpty()) return null;
        return view.document.copyOf(ids, 0f, 0f);
    }

    /**
     * Adds a copy of {@code clip} at an offset, as ONE undo step, and selects what arrived.
     *
     * <p>Fresh ids for everything, so pasting the same clipboard repeatedly is legal — the clipboard is
     * a template, not a handle on the nodes it came from. The edges are remapped through the same table,
     * which is what keeps a pasted subgraph wired to itself rather than back to the original.</p>
     *
     * <p>Selecting the result is what makes paste-then-drag work, and it is also how you can tell what
     * arrived when it landed on top of something else.</p>
     */
    List<GraphNode> paste(@Nullable GraphDocument clip, float offsetX, float offsetY) {
        if (clip == null || clip.nodeCount() == 0) return List.of();

        Map<String, String> remap = new LinkedHashMap<>();
        List<GraphNode> pasted = new ArrayList<>();
        NodeWidgetFactory factory = view.getNodeFactory() != null
                ? view.getNodeFactory() : NodeWidgetFactory.of(view.getNodeLibrary()).build();

        view.edits.begin("paste");
        try {
            for (NodeData source : clip.nodes()) {
                String id = GraphIds.generate();
                remap.put(source.id(), id);

                NodeData placed = source.withId(id).movedTo(source.x() + offsetX, source.y() + offsetY);
                NodeType type = view.getNodeLibrary() != null
                        ? view.getNodeLibrary().get(placed.typeId()) : null;
                GraphNode widget = factory.create(type, placed);
                // Bound and registered BEFORE the add, so addNode adopts the stored ports and properties
                // rather than deriving a second set from the widget -- which is how a node's instance
                // state gets silently dropped. See dataFor.
                widget.bindToDocument(placed.id(), placed.typeId());
                view.document.addNode(placed);
                view.addNode(widget, placed.x(), placed.y());

                NodeData stored = view.document.node(id);
                if (stored != null) view.edits.record(new GraphEdits.AddNode(view, widget, stored));
                pasted.add(widget);
            }
            for (EdgeData edge : clip.edges()) {
                String from = remap.get(edge.from().nodeId());
                String to = remap.get(edge.to().nodeId());
                if (from == null || to == null) continue;
                NodePort out = view.portFor(new PortRef(from, edge.from().portId()));
                NodePort in = view.portFor(new PortRef(to, edge.to().portId()));
                if (out != null && in != null) view.connect(out, in);
            }
        } finally {
            view.edits.end();
        }

        view.getSelection().replaceWith(pasted);
        return pasted;
    }

    /**
     * Adds a copy of {@code clip} with its top-left corner at a world point.
     *
     * <p>What "paste at the cursor" means, and the anchor is deliberate: the group's <b>bounding box
     * corner</b> lands on the point, so everything pasted appears down and right of the pointer and the
     * whole of it is where you were looking. Anchoring on the centre instead scatters half the group
     * behind the cursor, and anchoring on the first node makes the result depend on which node happened
     * to be copied first — invisible from the outside, and different every time.</p>
     *
     * <p>Relative positions inside the group are preserved, because only one offset is applied to all of
     * them: a pasted subgraph keeps its shape.</p>
     */
    List<GraphNode> pasteAt(@Nullable GraphDocument clip, float worldX, float worldY) {
        if (clip == null || clip.nodeCount() == 0) return List.of();

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        for (NodeData data : clip.nodes()) {
            minX = Math.min(minX, data.x());
            minY = Math.min(minY, data.y());
        }
        return paste(clip, worldX - minX, worldY - minY);
    }

    /**
     * Copies the selection and pastes it at an offset — one gesture, one undo step.
     *
     * <p>Deliberately does <b>not</b> touch the clipboard. Duplicating is not copying: a user who
     * duplicated something would otherwise lose whatever they had copied earlier, which every editor
     * that gets this right keeps separate.</p>
     */
    List<GraphNode> duplicateSelection(float offsetX, float offsetY) {
        return paste(copySelection(), offsetX, offsetY);
    }

    /** The engine's seam onto the four methods above. */
    final Clipboard<GraphDocument> asClipboard = new Clipboard<GraphDocument>() {
        @Override
        public Class<GraphDocument> type() {
            return GraphDocument.class;
        }

        @Override
        @Nullable
        public GraphDocument copy() {
            return copySelection();
        }

        @Override
        public void paste(GraphDocument clip, float worldX, float worldY) {
            pasteAt(clip, worldX, worldY);
        }

        @Override
        public void pasteBy(GraphDocument clip, float offsetX, float offsetY) {
            GraphClipboard.this.paste(clip, offsetX, offsetY);
        }

        @Override
        public boolean isEmpty(GraphDocument clip) {
            return clip == null || clip.nodeCount() == 0;
        }
    };

    /**
     * Cut, copy and paste as the engine's own actions.
     *
     * <p>Routed through {@link GraphCommands} rather than calling the four methods above directly, so
     * there is one definition of what cutting a selection means — the undo grouping and the paste offset
     * already live in the commands.</p>
     */
    final ClipboardActions asActions = new ClipboardActions() {
        @Override public boolean canCut() { return isEnabled(GraphCommands.CUT); }

        @Override public void cut() { run(GraphCommands.CUT); }

        @Override public boolean canCopy() { return isEnabled(GraphCommands.COPY); }

        @Override public void copy() { run(GraphCommands.COPY); }

        @Override public boolean canPaste() { return isEnabled(GraphCommands.PASTE); }

        @Override public void paste() { run(GraphCommands.PASTE); }

        private boolean isEnabled(String id) {
            Command command = CommandRegistry.global().get(id);
            return command != null && command.isEnabled(CommandContext.of(view));
        }

        private void run(String id) {
            CommandRegistry.global().run(id, CommandContext.of(view));
        }
    };
}
