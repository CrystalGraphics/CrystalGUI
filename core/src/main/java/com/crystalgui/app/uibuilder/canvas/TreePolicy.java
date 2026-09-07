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

    /**
     * Anything inside the artboard is an item; the artboard itself and the plane are not.
     *
     * <p><b>Outward through the COMPOSED tree</b>, which is the one paint and hit-testing walk. A widget's
     * insides are a shadow tree, and a {@code ShadowRoot}'s light parent is null by design — so a walk up
     * light parents from a slider's track reached the shadow root and stopped, and answered null. The
     * slider was hoverable over its own padding and dead over every part of it that draws: the track, the
     * fill, the knob. A checkbox was hoverable down its left and right margins only.</p>
     *
     * <p>The {@code contains} test is what keeps this precise rather than merely outward: it is the LIGHT
     * tree's, so the walk stops at the first node the document actually owns. A caller's content slotted
     * into a widget is such a node and answers as itself on the first step, before any host is
     * considered.</p>
     */
    @Override
    @Nullable
    public UIElement itemFor(@Nullable UIElement hit) {
        for (UIElement each = hit; each != null; each = parentOf(each)) {
            if (each == artboard) return null;
            if (artboard.contains(each)) return each;
        }
        return null;
    }

    /**
     * <b>Outward through the composed tree, exactly as {@link #itemFor} is</b> — the two answer about the
     * same hit and must agree about it.
     *
     * <p>They did not. This asked the LIGHT tree alone, so a press landing on a slider's track or a
     * checkbox's mark — a shadow part, which no light walk from the artboard reaches — was reported as
     * the widget's own business and the surface declined it. Hovering had already been taught to cross
     * the boundary, which left the canvas in the state that reads as most broken: the outline follows
     * the pointer over a widget and clicking there selects nothing.</p>
     *
     * <p>The artboard itself is SURFACE, and that is not an edge case: a press on blank page is how you
     * deselect and where a marquee starts, so answering TREE there would take both away.</p>
     */
    @Override
    public PressOwner ownerOf(UIElement hit) {
        for (UIElement each = hit; each != null; each = each.composedParent()) {
            if (artboard.contains(each)) return PressOwner.SURFACE;
        }
        return PressOwner.TREE;
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

    /**
     * No. A node in a UI document is placed by its parent's layout, not by a coordinate.
     *
     * <p>Dragging one means <b>reorder or reparent</b> — L4.6's gesture, with an insertion marker — and
     * the plane-move the engine offers has nothing to write. It was engaged anyway, which left the
     * engine's {@code __moving__} class on a node of the document: encoded into the file, shown in the
     * inspector's class list, and enough to mark the tab dirty.</p>
     */
    @Override
    public boolean movesItems() {
        return false;
    }

    @Nullable
    /** @see #itemFor the note on crossing a shadow boundary */
    private static UIElement parentOf(UIElement node) {
        return node.composedParent();
    }
}
