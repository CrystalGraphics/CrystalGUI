package com.crystalgui.widget.surface.mode;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.canvas.WorldRect;
import com.crystalgui.widget.surface.Surface;
import com.crystalgui.widget.surface.select.Picking;
import com.crystalgui.widget.surface.select.SurfaceSelection;
import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * The rubber band: press on empty plane, drag, and everything the rectangle <b>touches</b> is selected.
 *
 * <p>Owned by the Select tool; nothing else starts one. Shift adds to what was selected before the band
 * began, Alt takes away, and both are recomputed from that baseline every frame rather than accumulated
 * — a band that only ever grows feels broken the first time you overshoot.</p>
 *
 * <p>Touching rather than enclosing: an item bigger than the viewport would be unselectable by any band
 * the user could draw at the zoom they are working at.</p>
 */
public final class Marquee {

    /** On the band, so a theme decides what it looks like. */
    public static final String BAND_CLASS = "__marquee__";

    private final Surface surface;
    private final Picking picking;
    private final SurfaceSelection selection;

    private final UIElement band = new UIElement();

    private boolean active;
    private float startX, startY;

    /** What was selected when the band began, so Shift and Alt have something to work from. */
    private List<UIElement> baseline = List.of();

    public Marquee(Surface surface, Picking picking, SurfaceSelection selection) {
        this.surface = surface;
        this.picking = picking;
        this.selection = selection;
        band.addClass(BAND_CLASS);
        // NOT HIT-TESTABLE: the band follows the pointer, so a band that could be hit would be under the
        // pointer for the whole gesture and take the release.
        band.setHitTest(false);
        StyleGroup.defaultPipeline(band.getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));
        surface.addOverlay(band);
    }

    public boolean isActive() {
        return active;
    }

    /** The band element, for a test or a theme that wants to find it. */
    public UIElement element() {
        return band;
    }

    /**
     * Starts a band at a raw pointer position.
     *
     * @param additive    keep what was selected and add — Shift
     * @param subtractive keep what was selected and take away — Alt
     */
    public void begin(float rawX, float rawY, boolean additive, boolean subtractive) {
        if (!additive && !subtractive) selection.clear();
        baseline = selection.items();

        Vector2f local = surface.toViewportPoint(rawX, rawY);
        startX = local.x();
        startY = local.y();
        active = true;

        Drag.start(surface.element(), rawX, rawY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                update(mx, my, additive, subtractive);
            }

            @Override
            public void onDragEnd(float mx, float my) {
                end();
            }

            @Override
            public void onDragCancel() {
                // Escape mid-band puts back what was selected before it started, rather than leaving
                // whatever the half-drawn rectangle happened to be over.
                selection.replaceWith(baseline);
                end();
            }
        });
    }

    private void update(float localX, float localY, boolean additive, boolean subtractive) {
        float x = Math.min(startX, localX);
        float y = Math.min(startY, localY);
        float w = Math.abs(localX - startX);
        float h = Math.abs(localY - startY);

        // ALREADY RELATIVE TO THE SURFACE'S OWN BOX. A drag reports the pointer in the source's space,
        // whose origin is zero — subtracting the box position shifts the band by however far the surface
        // sits inside its parent.
        StyleGroup.inlinePipeline(band.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.FLEX).left(x).top(y).width(w).height(h));

        Vector2f from = surface.viewportToWorld(x, y);
        Vector2f to = surface.viewportToWorld(x + w, y + h);
        List<UIElement> inside = picking.touching(WorldRect.of(from.x(), from.y(), to.x(), to.y()));

        if (subtractive) {
            List<UIElement> kept = new ArrayList<>(baseline);
            kept.removeAll(inside);
            selection.replaceWith(kept);
        } else if (additive) {
            List<UIElement> combined = new ArrayList<>(baseline);
            for (UIElement item : inside) if (!combined.contains(item)) combined.add(item);
            selection.replaceWith(combined);
        } else {
            selection.replaceWith(inside);
        }
    }

    private void end() {
        active = false;
        StyleGroup.inlinePipeline(band.getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));
    }
}
