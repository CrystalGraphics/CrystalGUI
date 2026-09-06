package com.crystalgui.widget.graph;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.PortRef;
import com.crystalgui.ui.dom.UIElement;
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

    /**
     * Puts a panel in the <b>viewport</b> rather than on the plane, so it does not pan or zoom with the
     * graph.
     *
     * <p>Where a blackboard or a preview lives. An overlay is marked internal, so it is not a node and
     * the graph's own commands step over it.</p>
     */
    void mountOverlay(UIElement panel);
}
