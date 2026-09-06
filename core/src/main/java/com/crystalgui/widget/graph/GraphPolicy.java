package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfacePolicy;

/**
 * What a graph means by the engine's questions.
 *
 * <p>An item is a node; a press on a node's chrome is the surface's and one inside it is the tree's, or
 * a port's value editor could not be typed in; and a move writes one position edit per node, composed
 * into one step.</p>
 *
 * <p>Built by {@link GraphView#createPolicy()} while the view's constructor is still running, so it must
 * answer questions later and read nothing now.</p>
 */
final class GraphPolicy implements SurfacePolicy {

    private final GraphView view;

    GraphPolicy(GraphView view) {
        this.view = view;
    }

    @Override
    @Nullable
    public UIElement itemFor(@Nullable UIElement hit) {
        for (UIElement each = hit; each != null; each = each.parentElement()) {
            if (each instanceof GraphNode node && node.parent() == view.content()) return node;
        }
        return null;
    }

    @Override
    public PressOwner ownerOf(UIElement hit) {
        // A NODE'S CHROME IS THE SURFACE'S AND WHAT IS INSIDE IT IS NOT. A port's default-value editor
        // has to keep taking clicks, or a field inside a node cannot be typed in at all.
        return hit instanceof GraphNode ? PressOwner.SURFACE : PressOwner.TREE;
    }

    @Override
    public void markSelected(UIElement item, boolean selected) {
        if (item instanceof GraphNode node) node.setSelected(selected);
    }

    /**
     * Deleting is the graph's own: nodes go with the wires that touched them.
     *
     * <p>{@code deleteSelection} already does exactly that as one transaction, so this returns null and
     * the command path stays where the edge cases already live.</p>
     */
    @Override
    @Nullable
    public Edit deleteEdit(List<UIElement> items) {
        return null;
    }

    @Override
    @Nullable
    public Edit moveEdit(List<Move> moves) {
        List<Edit> each = new ArrayList<>(moves.size());
        for (Move move : moves) {
            if (!(move.item() instanceof GraphNode node) || node.getNodeId() == null) continue;
            each.add(new GraphEdits.MoveNode(view, node.getNodeId(),
                    move.fromX(), move.fromY(), move.toX(), move.toY()));
        }
        return each.isEmpty() ? null : CompositeEdit.of("move", each.toArray(new Edit[0]));
    }
}
