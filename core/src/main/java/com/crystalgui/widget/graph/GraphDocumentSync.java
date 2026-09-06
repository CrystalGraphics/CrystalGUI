package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphChangeset;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphIds;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.PortRef;
import com.crystalgui.graph.PortSpec;

/**
 * The projection between a {@link GraphDocument} and the widgets showing it, in both directions.
 *
 * <pre>{@code
 * sync.load(fromDisk);       // replace what the view shows
 * sync.applyPending();       // a server, a command or a paste changed the document
 * sync.widgetFor(nodeId);    // and back the other way
 * }</pre>
 *
 * <p><b>There is no node on the plane the document does not know about.</b> That is the property every
 * later feature — save, duplicate, a server sending a graph — rests on, and it is why a widget added by
 * hand gets {@link NodeData} derived from its own ports rather than being left unbound.</p>
 */
final class GraphDocumentSync {

    private final GraphView view;

    /** Every bound node, by document id. The index {@link #widgetFor} and {@link #portFor} answer from. */
    private final Map<String, GraphNode> widgetsById = new LinkedHashMap<>();

    GraphDocumentSync(GraphView view) {
        this.view = view;
    }

    /** Adds a widget and binds it, deriving its {@link NodeData} if it arrived without any. */
    void addGraphNode(GraphNode widget, float worldX, float worldY) {
        attachNode(widget, dataFor(widget, worldX, worldY));
    }

    /** Position is document data — a reload has to give a moved node back where it was left. */
    void noteMoved(GraphNode widget, float worldX, float worldY) {
        if (widget.getNodeId() != null) view.document.moveNode(widget.getNodeId(), worldX, worldY);
        markSynced();
    }

    /** The {@link NodeData} for a widget: its own if it already has one, otherwise derived from it. */
    private NodeData dataFor(GraphNode widget, float worldX, float worldY) {
        String existing = widget.getNodeId();
        if (existing != null) {
            NodeData known = view.document.node(existing);
            if (known != null) return known.movedTo(worldX, worldY);
        }
        List<PortSpec> ports = new ArrayList<>();
        for (NodePort port : widget.getPorts()) {
            ports.add(new PortSpec(port.getPortId(), port.getDirection(), port.getType().id()));
        }
        String typeId = widget.getTypeId() != null ? widget.getTypeId() : GraphView.WIDGET_AUTHORED_TYPE;
        // A widget-authored node stores its own title, because there is no library type to take one from
        // and reloading it as "crystalgui:widget" is true but useless. Its controls and preview still
        // cannot come back — those are Java the document never saw — which is the honest limit of
        // building a node as a widget rather than registering a type for it.
        Map<String, String> properties = widget.getTypeId() != null
                ? Map.of()
                : Map.of(NodeWidgetFactory.TITLE_PROPERTY, widget.getTitle());
        return new NodeData(existing != null ? existing : GraphIds.generate(),
                typeId, worldX, worldY, ports, properties);
    }

    /**
     * The view has just made this change itself, so the changeset must not report it again.
     *
     * <p>Without this the two directions fight, and they fight <em>quietly</em>. A changeset records the
     * NET change since it was last drained, so an add the view had already applied sat pending — and a
     * later remove of that same node cancelled the add instead of recording a removal. The changeset then
     * said "nothing happened" while the view still held the widget, so {@link #applyPending()} left a
     * node on screen that the document no longer had.</p>
     */
    void markSynced() {
        view.document.changeset().clear();
    }

    /** Puts node DATA into the document without a widget — what a paste and the create menu do first,
     * so the widget they build afterwards adopts the stored ports and properties instead of deriving a
     * second set from itself. */
    void addNodeData(NodeData data) {
        view.document.addNode(data);
    }

    /** Puts a node into both the document and the tree. The one path; {@link GraphEdits.AddNode} uses it
     * too, which is what makes an undone delete restore the SAME id rather than a new one. */
    void attachNode(GraphNode widget, NodeData data) {
        if (!view.document.hasNode(data.id())) view.document.addNode(data);
        widget.bindToDocument(data.id(), data.typeId());
        widgetsById.put(data.id(), widget);
        view.addNodeDirect(widget, data.x(), data.y());
        view.watchPortsOf(widget);
        markSynced();
    }

    /** Removes a node from both. */
    void detachNode(GraphNode widget) {
        String id = widget.getNodeId();
        if (id != null) {
            view.document.removeNode(id);
            widgetsById.remove(id);
        }
        // A port's default editor is a SEPARATE plane child, not a descendant of the node — removing the
        // node does not take it with it. Forgotten explicitly, or a deleted node's floating field is
        // orphaned on screen forever, pointing at a port that no longer exists anywhere.
        view.forgetPortsOf(widget);
        view.content().remove(widget);
        markSynced();
    }

    /**
     * Re-derives a bound node's declared ports from its widget.
     *
     * <p>Called when a node gains ports after joining the view. Only for widget-authored nodes: one built
     * from a library type already has the ports its type declared, and re-deriving them would throw away
     * anything the type knew that the widget does not.</p>
     */
    void syncPorts(GraphNode widget) {
        String id = widget.getNodeId();
        if (id == null) return;
        NodeData current = view.document.node(id);
        // The DOCUMENT decides whether this is widget-authored, not the widget. Binding sets the widget's
        // typeId to WIDGET_AUTHORED_TYPE, so a "typeId == null" test here rejected precisely the nodes it
        // existed to serve — every one of them, silently.
        if (current == null || !GraphView.WIDGET_AUTHORED_TYPE.equals(current.typeId())) return;
        List<PortSpec> ports = new ArrayList<>();
        for (NodePort port : widget.getPorts()) {
            ports.add(new PortSpec(port.getPortId(), port.getDirection(), port.getType().id()));
        }
        view.document.replaceNode(new NodeData(id, current.typeId(), current.x(), current.y(),
                ports, current.properties()));
        markSynced();
    }

    /** The widget projecting {@code nodeId}, or null. */
    @Nullable
    GraphNode widgetFor(String nodeId) {
        return widgetsById.get(nodeId);
    }

    /** The port a {@link PortRef} names, or null if the node or the port is not on screen. */
    @Nullable
    NodePort portFor(PortRef ref) {
        GraphNode widget = widgetsById.get(ref.nodeId());
        if (widget == null) return null;
        for (NodePort port : widget.getPorts()) {
            if (port.getPortId().equals(ref.portId())) return port;
        }
        return null;
    }

    /**
     * Replaces everything the view is showing with {@code source} — opening a file, or receiving a graph.
     *
     * <h3>It copies the CONTENTS in; it does not adopt the object</h3>
     * <p>This used to end in {@code this.document = source}, and that is the one line that made a
     * per-file editor impossible. A host wires its panels to {@code getDocument()} once, at construction —
     * {@code ShaderGraphEditor} hands the same instance to its Main Preview, its Blackboard and its own
     * {@code onChanged} listener. Swapping the field left every one of them bound to an <b>orphan</b>: the
     * board would go on listing the previous graph's properties and write its edits into a document nobody
     * was showing, with both halves individually working and no error anywhere.</p>
     *
     * <p>So a view owns one document for its whole life, and loading changes what is in it. The cost is
     * the mirror-image trap, which is the lesser one and at least has an obvious right answer: a caller
     * that holds {@code source} afterwards is holding a spent template. Mutate {@code getDocument()}.</p>
     *
     * <h3>Through the changeset, not a second rebuild routine</h3>
     * <p>Clearing and repopulating produces exactly the changeset {@link #applyPending()} already
     * consumes, so the widget work is the same path a paste or a server sync takes — retiring floating
     * port editors, pruning the selection, emitting {@code onConnectionsChanged} once. The hand-rolled
     * rebuild this replaced had to remember each of those separately, and a fourth thing added to the view
     * later would have had to be remembered in both places.</p>
     *
     * <h3>Loading is not an edit, so the undo stack is CLEARED</h3>
     * <p>Not appended to, and not left alone. Appending would make the first {@code Ctrl+Z} after an open
     * unpick the file a node at a time — the file is the starting state, not something the user did.
     * Leaving the old history is worse: those entries describe a graph that is no longer here, so undoing
     * one applies an edit to nodes that never existed in this document.</p>
     *
     * <p>Edges are <b>restored</b> rather than reconnected, for the reason {@code GraphCodecs} gives:
     * re-validating on load silently drops every wire whose types this build has no rule for — the
     * "opened without the plugin" case the whole model is arranged to survive. And nodes whose type is not
     * in the library still appear, because {@link NodeWidgetFactory} builds them from the ports the
     * document stored, which is why the document stores them.</p>
     */
    void load(GraphDocument source) {
        view.document.clear();
        // The DOCUMENT layer alone, mirroring what the codec writes — the user and workspace layers come
        // from other files entirely and are not this graph's to carry.
        view.document.settings().replaceLayer(SettingsLayer.DOCUMENT,
                source.settings().layer(SettingsLayer.DOCUMENT).asMap());
        for (GraphProperty property : source.properties()) view.document.addProperty(property);
        for (NodeData node : source.nodes()) view.document.addNode(node);
        for (EdgeData edge : source.edges()) view.document.restoreEdge(edge);

        applyPending();
        view.edits.history().clear();
        // ONE emit at the end, and it is not belt and braces. `restoreEdge` deliberately only records in
        // the changeset, and `GraphDocument.clear()` empties the property list AFTER its last removeNode
        // — so loading a graph with no nodes, or one whose last act is an edge, would tell nothing
        // downstream that anything had happened and the Blackboard would still be listing the previous
        // file's properties. Listeners re-read the document rather than taking a payload, so a spare emit
        // is a no-op and a missing one is a stale panel.
        view.document.onChanged.emit();
    }

    /**
     * Applies the document's pending changes to the widgets <b>in place</b>, and clears them.
     *
     * <p>The other direction from everything above: this is how a change made to the document by something
     * that is not the view — a server, a command, a paste — reaches the screen. Mutations made
     * <em>through</em> the view already updated both sides, and this is idempotent, so calling it
     * afterwards is harmless.</p>
     *
     * <p><b>In place, never a rebuild</b>, and that is the whole reason a changeset exists rather than a
     * "something changed" flag. Rebuilding detaches the element under the pointer: a drag's source would
     * go stale on its first update and every later frame would feed it garbage. Untouched nodes here keep
     * their widget, and therefore their drag, their focus and their scroll position.</p>
     *
     * @return how many individual changes were applied
     */
    int applyPending() {
        GraphChangeset pending = view.document.changeset();
        if (pending.isEmpty()) return 0;

        // Snapshot EVERYTHING, then clear, then apply — because applying re-enters. `CanvasView.addNode`
        // calls `moveNode` polymorphically, which reaches the view's override, which writes through to the
        // document and drains the changeset. Reading the lists as it went meant adding the first node
        // wiped the pending edges, and the wires simply never appeared.
        List<String> removedNodes = List.copyOf(pending.removedNodes());
        List<String> addedNodes = List.copyOf(pending.addedNodes());
        List<String> movedNodes = List.copyOf(pending.movedNodes());
        List<EdgeData> removedEdges = List.copyOf(pending.removedEdges());
        List<EdgeData> addedEdges = List.copyOf(pending.addedEdges());
        pending.clear();
        int applied = 0;

        for (String id : removedNodes) {
            GraphNode widget = widgetsById.remove(id);
            if (widget == null) continue;
            // Same reason detachNode forgets them: a floating default editor is not a descendant of its
            // node, so the removal below never reaches it. Missing here left every port's box and dot
            // permanently orphaned on the plane whenever a removal arrived through the changeset instead
            // of through removeNode directly — undo of an add, a server sync, or a delete-then-recreate.
            view.forgetPortsOf(widget);
            view.content().remove(widget);
            applied++;
        }
        for (String id : addedNodes) {
            NodeData data = view.document.node(id);
            if (data == null || widgetsById.containsKey(id)) continue;
            NodeWidgetFactory factory = view.getNodeFactory() != null
                    ? view.getNodeFactory() : NodeWidgetFactory.of(view.getNodeLibrary()).build();
            NodeType type = view.getNodeLibrary() != null
                    ? view.getNodeLibrary().get(data.typeId()) : null;
            GraphNode widget = factory.create(type, data);
            widget.bindToDocument(data.id(), data.typeId());
            widgetsById.put(id, widget);
            view.addNodeDirect(widget, data.x(), data.y());
            view.watchPortsOf(widget);
            applied++;
        }
        for (String id : movedNodes) {
            GraphNode widget = widgetsById.get(id);
            NodeData data = view.document.node(id);
            // Direct, not through the override: the position is already what the document says, and going
            // back through it would write it straight back with no effect but a second changeset entry.
            if (widget != null && data != null) {
                view.moveNodeDirect(widget, data.x(), data.y());
                applied++;
            }
        }
        for (EdgeData edge : removedEdges) {
            NodePort from = portFor(edge.from());
            NodePort to = portFor(edge.to());
            if (view.connections.removeIf(c -> c.from() == from && c.to() == to)) applied++;
            if (from != null && to != null) view.refreshWireCounts(from, to);
        }
        for (EdgeData edge : addedEdges) {
            int before = view.connections.size();
            linkWidgets(edge);
            if (view.connections.size() != before) applied++;
        }

        view.getSelection().prune(view);
        if (applied > 0) view.onConnectionsChanged.emit();
        return applied;
    }

    /**
     * Puts an edge back into the document and builds its view-side connection.
     *
     * <p>RESTORED rather than reconnected: an undo must put back exactly the edge that was there, and
     * re-running validation at that point can only ever refuse it — the graph it was legal in is
     * precisely the graph the undo is restoring.</p>
     */
    void restoreEdge(EdgeData edge) {
        view.document.restoreEdge(edge);
        linkWidgets(edge);
        markSynced();
    }

    /** Takes an edge out of the document. The view-side connection list is the wires' own. */
    void removeEdge(EdgeData edge) {
        view.document.disconnect(edge);
        markSynced();
    }

    /** Builds the view-side {@link GraphConnection} for a document edge. Silent when either end is
     * missing: a document may legitimately outrun its widgets mid-load. */
    void linkWidgets(EdgeData edge) {
        NodePort from = portFor(edge.from());
        NodePort to = portFor(edge.to());
        if (from == null || to == null) return;
        GraphConnection connection = new GraphConnection(from, to);
        if (!view.connections.contains(connection)) view.connections.add(connection);
        view.refreshWireCounts(from, to);
    }
}
