package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.app.uibuilder.document.TreeDropRules;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.dnd.SortPlacement;

/**
 * Where a drop at the pointer would land in the document — which container, at which child index.
 *
 * <pre>{@code
 * DropResolver resolver = DropResolver.forPane(ctx);                  // document nodes in, document nodes out
 * DropResolver.Drop drop = resolver.resolve(sources, rawX, rawY);
 * if (drop != null) document.applyAll("move", TreeMoves.move(drop.target(), drop.index(), sources));
 * }</pre>
 *
 * <p>GrapesJS's {@code DropLocationDeterminer} (BSD-3-Clause), ported for the builder's tree. The node under
 * the pointer is found first, skipping what is being carried; then the walk goes outward to the first node
 * whose <b>drop area</b> holds the pointer and that may take the sources — so a pointer in a container's
 * edge band means beside it, and one further in means into it. The index is then the placement among that
 * container's own in-flow children.</p>
 *
 * <ul>
 *   <li>Rects are layout boxes measured in {@code space}, the overlay the drop is drawn on — so the band is
 *       in screen pixels at any zoom.</li>
 *   <li>The carried nodes keep their cells, so pointing at one's own cell resolves to where it already is,
 *       which {@code TreeMoves} leaves alone.</li>
 *   <li>The root's drop area is its whole box: it has nothing to be beside.</li>
 * </ul>
 */
public final class DropResolver {

    private final UIElement root;

    private final UIElement space;

    /** The pane a drop is measured in, whose drawn nodes are translated to the document's and back; null for none. */
    @Nullable
    private final BuilderContext pane;

    /** A drop over {@code root}, a laid-out tree, answered in its own nodes. */
    public DropResolver(UIElement root, UIElement space) {
        this(root, space, null);
    }

    private DropResolver(UIElement root, UIElement space, @Nullable BuilderContext pane) {
        this.root = root;
        this.space = space;
        this.pane = pane;
    }

    /**
     * A drop onto the document shown in {@code ctx}'s pane: sources and targets are DOCUMENT nodes, and the
     * measuring happens on what the pane draws — the document's own tree is never laid out.
     */
    public static DropResolver forPane(BuilderContext ctx) {
        return new DropResolver(ctx.artboard().shownRoot(), ctx.dropIndicator(), ctx);
    }

    /**
     * A drop: into {@code target} at child {@code index}.
     *
     * @param against the child and side the index was placed against, or null for an empty container
     * @param column  whether {@code target}'s flow is a column, which is the way a line between two
     *                children runs across
     * @param targetRect {@code target}'s layout box in the space
     */
    public record Drop(UIElement target, int index, @Nullable Against against, boolean column,
                       float[] targetRect) {
    }

    /** The child a drop was placed against, with its rect in the space. */
    public record Against(UIElement child, SortPlacement.Side side, float[] rect) {
    }

    /** The drop at raw pointer pixels, or null where nothing may land — outside the document. */
    @Nullable
    public Drop resolve(Collection<UIElement> sources, float rawX, float rawY) {
        return inDocument(resolveDrawn(drawn(sources), rawX, rawY));
    }

    @Nullable
    private Drop resolveDrawn(Collection<UIElement> sources, float rawX, float rawY) {
        Vector2f at = space.toLocal(rawX, rawY);
        for (UIElement node = hoveredAt(root, sources, rawX, rawY); node != null;
             node = node == root ? null : node.parentElement()) {
            float[] rect = CanvasRects.ofLayout(node, space);
            if (rect == null) continue;
            float[] area = node == root ? rect : SortPlacement.dropArea(rect[0], rect[1], rect[2], rect[3]);
            if (!SortPlacement.contains(area, at.x, at.y)) continue;
            if (!TreeDropRules.canMove(root, sources, node)) continue;
            return dropInto(node, rect, at.x, at.y);
        }
        return null;
    }

    /**
     * The drop into {@code target} at child {@code index}, drawn against the child it lands beside — what a place
     * chosen by keyboard shows, as a pointer's is. Null when {@code target} has no layout box.
     */
    @Nullable
    public Drop at(UIElement target, int index) {
        UIElement drawnTarget = pane == null ? target : pane.shown(target);
        return drawnTarget == null ? null : inDocument(atDrawn(drawnTarget, index));
    }

    @Nullable
    private Drop atDrawn(UIElement target, int index) {
        float[] targetRect = CanvasRects.ofLayout(target, space);
        if (targetRect == null) return null;
        SortPlacement.Flow flow = SortPlacement.Flow.of(target);
        List<UIElement> children = target.children();
        int clamped = Math.max(0, Math.min(index, children.size()));
        // THE CHILD AT THE INDEX, the new node going before it; past the end, the last child, going after it.
        UIElement beside = clamped < children.size() ? children.get(clamped) : children.isEmpty() ? null : children.get(clamped - 1);
        float[] rect = beside == null || !SortPlacement.inFlow(beside) ? null : CanvasRects.ofLayout(beside, space);
        if (rect == null) return new Drop(target, clamped, null, flow.column(), targetRect);
        SortPlacement.Side side = clamped < children.size() ? SortPlacement.Side.BEFORE : SortPlacement.Side.AFTER;
        return new Drop(target, clamped, new Against(beside, side, rect), flow.column(), targetRect);
    }

    private Drop dropInto(UIElement target, float[] targetRect, float x, float y) {
        SortPlacement.Flow flow = SortPlacement.Flow.of(target);
        List<UIElement> children = target.children();
        List<SortPlacement.Cell> cells = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            UIElement child = children.get(i);
            if (!SortPlacement.inFlow(child)) continue;
            float[] rect = CanvasRects.ofLayout(child, space);
            if (rect == null) continue;
            cells.add(new SortPlacement.Cell(rect[0], rect[1], rect[2], rect[3], flow.column(), i));
        }
        if (cells.isEmpty()) return new Drop(target, 0, null, flow.column(), targetRect);
        // VISUAL ORDER, which a reversed flow draws backwards. @see SortPlacement
        if (flow.reversed()) Collections.reverse(cells);
        SortPlacement.Found found = SortPlacement.find(cells, x, y);
        SortPlacement.Cell cell = found.cell();
        Against against = new Against(children.get(cell.index()), found.side(),
                new float[] {cell.x(), cell.y(), cell.width(), cell.height()});
        return new Drop(target, found.index(flow.reversed()), against, flow.column(), targetRect);
    }

    /** The pane's drawn copies of {@code sources}; the sources themselves with no pane. */
    private Collection<UIElement> drawn(Collection<UIElement> sources) {
        if (pane == null) return sources;
        List<UIElement> out = new ArrayList<>(sources.size());
        for (UIElement source : sources) {
            UIElement shown = pane.shown(source);
            if (shown != null) out.add(shown);
        }
        return out;
    }

    /** {@code drop} with its nodes the document's rather than the pane's; the rects are the pane's either way. */
    @Nullable
    private Drop inDocument(@Nullable Drop drop) {
        if (drop == null || pane == null) return drop;
        UIElement target = pane.sourceOf(drop.target());
        if (target == null) return null;
        Against against = drop.against();
        if (against != null) {
            UIElement child = pane.sourceOf(against.child());
            against = child == null ? null : new Against(child, against.side(), against.rect());
        }
        return new Drop(target, drop.index(), against, drop.column(), drop.targetRect());
    }

    /**
     * The deepest document node whose layout box holds the raw point, never one being carried or inside
     * one. Later children first, which is paint order. Null when the point is off the document.
     */
    @Nullable
    static UIElement hoveredAt(UIElement node, Collection<UIElement> skipped, float rawX, float rawY) {
        if (skipped.contains(node) || !node.isDisplayed() || !CanvasRects.layoutContains(node, rawX, rawY)) {
            return null;
        }
        List<UIElement> children = node.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            UIElement found = hoveredAt(children.get(i), skipped, rawX, rawY);
            if (found != null) return found;
        }
        return node;
    }
}
