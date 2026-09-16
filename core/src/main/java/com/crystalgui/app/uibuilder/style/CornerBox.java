package com.crystalgui.app.uibuilder.style;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;

/**
 * Four corner radii as a box with a puck on each corner, dragged diagonally inward to round it.
 *
 * <pre>{@code
 * Property<Boolean> linked = Property.of(true);
 * lab.content().append(new CornerBox("corners", linked).bind(radii));   // [tl, tr, br, bl] in px
 * }</pre>
 *
 * <p>While {@code linked} holds, a drag on any puck moves all four. The box draws the radii it holds.</p>
 */
public final class CornerBox extends ValueControl<double[]> {

    public static final Name NAME = Name.of("cornerbox");

    public static final String BOX_CLASS = "__corner-box__";
    public static final String PUCK_CLASS = "__corner-puck__";

    /** Top-left, top-right, bottom-right, bottom-left — the order CSS states corners in. */
    static final StyleProperty<?>[] X = {
            BorderRadiusProperties.TOP_LEFT_X, BorderRadiusProperties.TOP_RIGHT_X,
            BorderRadiusProperties.BOTTOM_RIGHT_X, BorderRadiusProperties.BOTTOM_LEFT_X};

    static final StyleProperty<?>[] Y = {
            BorderRadiusProperties.TOP_LEFT_Y, BorderRadiusProperties.TOP_RIGHT_Y,
            BorderRadiusProperties.BOTTOM_RIGHT_Y, BorderRadiusProperties.BOTTOM_LEFT_Y};

    private static final String[] CORNERS = {"top-left", "top-right", "bottom-right", "bottom-left"};

    private final Property<Boolean> linked;

    public CornerBox(String id, Property<Boolean> linked) {
        super(NAME, ConfigDescriptor.vector(id, "", 4), new double[4]);
        this.linked = linked;
        addClass(BOX_CLASS);
        for (int corner = 0; corner < 4; corner++) append(puck(corner));
    }

    private UIElement puck(int corner) {
        UIElement puck = new UIElement();
        puck.addClass(PUCK_CLASS);
        puck.addClass("__" + CORNERS[corner] + "__");
        double[] from = new double[4];
        StyleGizmos.drag(puck, () -> {
            double[] now = getValue();
            if (now != null) System.arraycopy(now, 0, from, 0, Math.min(4, now.length));
            beginInteraction();
        }, (dx, dy) -> {
            // DIAGONALLY INWARD is more of a corner, so each axis is signed by where the corner sits.
            float inward = (corner == 1 || corner == 2 ? -dx : dx) + (corner >= 2 ? -dy : dy);
            double next = Math.max(0d, Math.round(from[corner] + inward / 2f));
            double[] radii = from.clone();
            if (Boolean.TRUE.equals(linked.get())) {
                Arrays.fill(radii, next);
            } else {
                radii[corner] = next;
            }
            commit(radii);
        }, this::endInteraction);
        return puck;
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable double[] radii) {
        for (int i = 0; i < 4; i++) {
            String px = CssValues.px(radii == null || i >= radii.length ? 0d : radii[i]);
            LiveEdits.setInline(this, X[i], px);
            LiveEdits.setInline(this, Y[i], px);
        }
    }
}
