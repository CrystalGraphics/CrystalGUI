package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfacePolicy;

/**
 * What an item is when the surface holds a laid-out UI tree rather than a graph of nodes.
 *
 * <p>Handed to the {@code SurfaceEditor} the builder is built on, so every gesture the engine has —
 * click, marquee, move — works on described elements without the engine knowing what one is.</p>
 *
 * <p>The press rule is the one that differs most from a graph: <b>everything inside a live artboard is
 * the surface's</b>, because the thing being designed must not react to being designed. A graph answers
 * the other way for a port's value field, which has to stay live.</p>
 */
public final class TreePolicy implements SurfacePolicy {

    private final Artboard artboard;

    public TreePolicy(Artboard artboard) {
        this.artboard = artboard;
    }

    /** The document's, so an edit made on the canvas and one made in the inspector share one history. */
    @Override
    public UndoStack history() {
        return artboard.model().history();
    }

    /** Anything inside the artboard is an item; the artboard itself and the plane are not. */
    @Override
    @Nullable
    public UIElement itemFor(@Nullable UIElement hit) {
        for (UIElement each = hit; each != null; each = parentOf(each)) {
            if (each == artboard) return null;
            if (artboard.contains(each)) return each;
        }
        return null;
    }

    @Override
    public PressOwner ownerOf(UIElement hit) {
        return artboard.contains(hit) ? PressOwner.SURFACE : PressOwner.TREE;
    }

    /**
     * Nothing yet.
     *
     * <p>Selection reads as handles drawn by an overlay rather than as a class on the element: a
     * described element must describe the same whether or not it happens to be picked, or the document
     * saves the selection.</p>
     */
    @Override
    public void markSelected(UIElement item, boolean selected) {
    }

    /** L4.5's, where a move writes an inset for an out-of-flow node and a reorder for an in-flow one. */
    @Override
    @Nullable
    public Edit moveEdit(List<Move> moves) {
        return null;
    }

    @Nullable
    private static UIElement parentOf(UIElement node) {
        return node.parent() instanceof UIElement parent ? parent : null;
    }
}
