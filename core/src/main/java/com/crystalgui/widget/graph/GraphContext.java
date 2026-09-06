package com.crystalgui.widget.graph;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.graph.PortRef;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * What a graph offers a feature written against it — the engine's surface, plus the graph's own.
 *
 * <pre>{@code
 * public final class MyExtension implements SurfaceExtension {
 *     public String id() { return "mymod:my-feature"; }
 *
 *     public Disposable activate(SurfaceContext surface) {
 *         if (!(surface instanceof GraphContext graph)) return () -> { };
 *         Connection c = graph.connectionsChanged().connect(this::recompute);
 *         return c::disconnect;
 *     }
 * }
 * }</pre>
 *
 * <p><b>A feature reaches the graph through this and never through {@code GraphView}.</b> That is what
 * makes a feature package free of the widget: the shader graph's previews, blackboard and inspector
 * sections are written against this interface, so none of them can reach a method the graph did not
 * mean to offer — and none of them can be the reason the widget cannot change.</p>
 *
 * <p>Everything here already exists on the view; the interface names it rather than inventing a parallel
 * vocabulary, so a reader moving between the two is reading the same words.</p>
 */
public interface GraphContext extends SurfaceContext {

    /** The graph being edited. Mutating it is how a feature changes the graph — through {@link #edits()}
     * when the change should be undoable, which is nearly always. */
    GraphDocument getDocument();

    /** What is selected, as nodes and wires rather than as bare elements. */
    GraphSelection getSelection();

    /** This graph's history. One per document, so two panes onto one file share it. */
    UndoStack undoStack();

    /** Fires after any edge is added or removed, including during a load. Listeners re-read the document
     * rather than taking a payload, so a spare emit is a no-op and a missing one is a stale panel. */
    Signal.Action connectionsChanged();

    /** Every node currently on the plane, in insertion order. */
    List<GraphNode> nodes();

    /** The widget projecting {@code nodeId}, or null. */
    @Nullable
    GraphNode widgetFor(String nodeId);

    /** The port a {@link PortRef} names, or null if the node or the port is not on screen. */
    @Nullable
    NodePort portFor(PortRef ref);

    /**
     * Applies the document's pending changes to the widgets in place.
     *
     * <p>What a feature calls after editing the document directly — a server sync, a generated node.
     * Idempotent, so calling it when nothing changed costs a check.
     *
     * @return how many individual changes were applied
     */
    int syncFromDocument();

    /** The type library this graph creates nodes from, or null if the consumer set none. */
    @Nullable
    NodeTypeRegistry getNodeLibrary();

    /** The factory turning a type into a widget, or null if the consumer set none. */
    @Nullable
    NodeWidgetFactory getNodeFactory();

    /**
     * Gives this graph a library to create nodes from and a factory to build their widgets.
     *
     * <p>Both belong to the consumer: the library is what a shader graph and a dialogue graph disagree
     * about. With neither set the graph still works — you simply cannot add a node from inside it.</p>
     */
    void useNodeLibrary(NodeTypeRegistry types, NodeWidgetFactory factory, TypeCompatibility rule);

    /**
     * Puts an already-built node on the plane at a world point, binding it to the document.
     *
     * <p>What a feature that MAKES a node calls — a property dropped from a blackboard, a node arriving
     * from a server. Record a {@link GraphEdits.AddNode} alongside it, or the node cannot be undone.</p>
     */
    void placeNode(GraphNode node, float worldX, float worldY);

    /**
     * Runs {@code hook} every frame, <b>owned by the surface</b>.
     *
     * <p>Which is what stops it outliving the surface: a hook registered against a one-way ticker goes
     * on running invisibly after the graph is closed. A feature that schedules work uses this rather
     * than reaching for the window.</p>
     */
    void everyFrame(Animation.Hook hook);

    /**
     * The surface's own box, or <b>null</b> when it has not been laid out yet.
     *
     * <p>Null is not an error and is the common case on the frame a graph is built — a feature that culls
     * against the viewport asks, gets null, and skips that frame. There is no other way to ask: a box is
     * destroyed and rebuilt whenever its subtree is hidden or restructured.</p>
     */
    @Nullable
    Box viewportBox();

    /**
     * Puts a panel in the <b>viewport</b> rather than on the plane, so it does not pan or zoom with the
     * graph.
     *
     * <p>Where a blackboard or a preview lives. An overlay is marked internal, so it is not a node and
     * the graph's own commands step over it.</p>
     */
    void mountOverlay(UIElement panel);
}
