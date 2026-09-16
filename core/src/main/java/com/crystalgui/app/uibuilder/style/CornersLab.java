package com.crystalgui.app.uibuilder.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The corners lab: a box with a puck on each corner, dragged to round it.
 *
 * <pre>{@code
 * CornersLab.open(chip, fields);   // edits all eight longhands
 * }</pre>
 *
 * <p>Radii are eight properties — an x and a y per corner — and editing them as eight rows of numbers is
 * how a person loses track of which corner they are on. Here the box <b>is</b> the element's corners: drag
 * a puck and that corner rounds, hold the link and all four follow. The same gesture as the box model's
 * numbers, on the shape instead of the edges.</p>
 *
 * <p>Longhands are what gets written, never the {@code border-radius} shorthand: an inline style is stored
 * per property, so a shorthand would be authorable in a sheet and not on an element — and the lab would then
 * behave differently depending on which target it was opened from.</p>
 */
public final class CornersLab {

    public static final String BOX_CLASS = "__corner-box__";
    public static final String PUCK_CLASS = "__corner-puck__";

    /** Top-left, top-right, bottom-right, bottom-left — the order CSS states corners in. */
    private static final StyleProperty<?>[] X = {
            BorderRadiusProperties.TOP_LEFT_X, BorderRadiusProperties.TOP_RIGHT_X,
            BorderRadiusProperties.BOTTOM_RIGHT_X, BorderRadiusProperties.BOTTOM_LEFT_X};

    private static final StyleProperty<?>[] Y = {
            BorderRadiusProperties.TOP_LEFT_Y, BorderRadiusProperties.TOP_RIGHT_Y,
            BorderRadiusProperties.BOTTOM_RIGHT_Y, BorderRadiusProperties.BOTTOM_LEFT_Y};

    private static final String[] CORNERS = {"top-left", "top-right", "bottom-right", "bottom-left"};

    private final StyleFields fields;
    private final StyleLab lab;
    private final UIElement box = new UIElement();
    private final UIText readout = new UIText("");

    private final float[] radii = new float[4];
    private boolean linked = true;

    private CornersLab(UIElement anchor, StyleFields fields, @Nullable StyleProperty<?> property,
                       Property<String> css) {
        this.fields = fields;
        this.lab = StyleLab.over(anchor, "Corners", property, css);
        for (int i = 0; i < 4; i++) radii[i] = CssValues.number(fields.valueOf(X[i].name), 0f);
    }

    /** Opens the lab over {@code anchor}, editing the corners of whatever {@code fields} writes to. */
    public static CornersLab open(UIElement anchor, StyleFields fields, @Nullable StyleProperty<?> property,
                                  Property<String> css) {
        CornersLab corners = new CornersLab(anchor, fields, property, css);
        corners.build();
        corners.lab.open();
        return corners;
    }

    private void build() {
        box.addClass(BOX_CLASS);
        for (int i = 0; i < 4; i++) box.append(puck(i));
        lab.content().append(box);

        UIElement row = new UIElement();
        row.addClass("__lab-row__");
        Button link = new Button(linked ? "Linked" : "Per corner");
        link.addClass("__lab-keyword__");
        link.attachListener(() -> {
            linked = !linked;
            link.setText(linked ? "Linked" : "Per corner");
        });
        row.append(link);
        for (String preset : List.of("0", "4", "8", "999")) {
            Button value = new Button(preset.equals("999") ? "pill" : preset);
            value.addClass("__lab-keyword__");
            value.attachListener(() -> {
                for (int i = 0; i < 4; i++) radii[i] = Float.parseFloat(preset);
                writeAll();
            });
            row.append(value);
        }
        row.append(readout);
        lab.content().append(row);

        lab.caption(() -> "top-left " + CssValues.px(radii[0]) + " · top-right " + CssValues.px(radii[1])
                + " · bottom-right " + CssValues.px(radii[2]) + " · bottom-left " + CssValues.px(radii[3]));
        refresh();
    }

    private UIElement puck(int corner) {
        UIElement puck = new UIElement();
        puck.addClass(PUCK_CLASS);
        puck.addClass("__" + CORNERS[corner] + "__");
        float[] start = {0f};
        StyleGizmos.drag(puck, (dx, dy) -> {
            // DIAGONALLY INWARD is more of a corner: the two axes agree for the two corners where dragging
            // right means inward, and disagree for the others, so each is signed by where it sits.
            float inward = (corner == 1 || corner == 2 ? -dx : dx) + (corner >= 2 ? -dy : dy);
            float next = Math.max(0f, start[0] + inward / 2f);
            if (linked) {
                for (int i = 0; i < 4; i++) radii[i] = next;
            } else {
                radii[corner] = next;
            }
            refresh();
        }, this::writeAll);
        puck.onMouseDown.attachListener((element, event) -> start[0] = radii[corner], false, true);
        return puck;
    }

    /** The box takes the radii, so what is dragged is what is drawn. */
    private void refresh() {
        for (int i = 0; i < 4; i++) {
            LiveEdits.setInline(box, cast(X[i]), CssValues.px(radii[i]));
            LiveEdits.setInline(box, cast(Y[i]), CssValues.px(radii[i]));
        }
        readout.setText(linked ? CssValues.px(radii[0])
                : CssValues.px(radii[0]) + " " + CssValues.px(radii[1]) + " "
                        + CssValues.px(radii[2]) + " " + CssValues.px(radii[3]));
        lab.refresh();
    }

    /** Writes the eight longhands — one text edit each, and only for the corners that changed. */
    private void writeAll() {
        for (int i = 0; i < 4; i++) {
            String value = CssValues.px(radii[i]);
            if (!value.equals(fields.valueOf(X[i].name))) fields.value(X[i].name).set(value);
            if (!value.equals(fields.valueOf(Y[i].name))) fields.value(Y[i].name).set(value);
        }
        refresh();
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
