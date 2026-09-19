package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.NewNode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.surface.EdgePan;

/**
 * The canvas as a drop target for a node that is not in the document yet — a Library card dragged in.
 *
 * <pre>{@code
 * new NewNodeDrop(surface).installOn(surface);   // once, by the editor
 * }</pre>
 *
 * <p>Resolved as a reorder is ({@link DropResolver}, with nothing carried), drawn by the same
 * {@link DropIndicator} and panned by the same {@link EdgePan}; the drop is a {@link Placement}. Accepted only
 * in design mode and only where something may land.</p>
 */
final class NewNodeDrop {

    private final BuilderSurface ctx;

    private final EdgePan pan;

    private boolean hovering;

    /** The pointer as the last Over left it, in surface pixels — what a pan re-resolves at. */
    private float lastX;
    private float lastY;

    /** Bumped when a drag leaves or drops, so the hook of an earlier drag ends itself. */
    private int generation;

    NewNodeDrop(BuilderSurface ctx) {
        this.ctx = ctx;
        this.pan = new EdgePan(ctx.surface());
    }

    void installOn(UIElement target) {
        target.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof NewNode) || !ctx.isDesignMode()) return;
            float x = event.getPosition().x();
            float y = event.getPosition().y();
            DropResolver.Drop drop = resolve(x, y);
            ctx.dropIndicator().show(drop);
            pan.pointerAt(x, y);
            lastX = x;
            lastY = y;
            startHovering();
            if (drop != null) event.preventDefault();
        }, false, true);
        target.events.getGroup(DragEvent.Leave.class).attachListener((element, event) -> {
            if (event.getTarget() == target) stopHovering();
        }, false, false);
        target.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof NewNode created)) return;
            DropResolver.Drop drop = resolve(event.getPosition().x(), event.getPosition().y());
            stopHovering();
            if (drop != null) Placement.at(ctx, drop.target(), drop.index(), created.build().get());
        }, false, true);
    }

    @Nullable
    private DropResolver.Drop resolve(float rawX, float rawY) {
        return DropResolver.forPane(ctx).resolve(List.of(), rawX, rawY);
    }

    /** Starts the edge pan and a watch that clears the indicator however the drag ends — a release refused here sends nothing. */
    private void startHovering() {
        if (hovering) return;
        UIDocument window = ctx.document();
        if (window == null) return;
        hovering = true;
        int mine = ++generation;
        pan.start(() -> ctx.dropIndicator().show(resolve(lastX, lastY)));
        window.animation().every(ctx, delta -> {
            if (mine != generation) return false;
            if (window.input().mode(Drag.class) == null) {
                stopHovering();
                return false;
            }
            return true;
        });
    }

    private void stopHovering() {
        generation++;
        hovering = false;
        pan.stop();
        ctx.dropIndicator().clear();
    }
}
