package com.crystalgui.app.uibuilder.style;

import java.util.Arrays;
import java.util.List;

import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The corners lab: a box with a puck on each corner, dragged to round it.
 *
 * <pre>{@code
 * CornersLab.open(chip, fields);   // edits all eight longhands
 * }</pre>
 *
 * <p>Longhands are what gets written, never the {@code border-radius} shorthand: an inline style is stored
 * per property, so a shorthand would behave differently depending on which target the lab was opened from.</p>
 */
public final class CornersLab {

    private CornersLab() {
    }

    /** Opens the lab over {@code anchor}, editing the corners of whatever {@code fields} writes to. */
    public static void open(UIElement anchor, StyleFields fields) {
        StyleLab lab = StyleLab.over(anchor, "Corners");

        // EIGHT DECLARATIONS AS ONE VALUE: an x and a y per corner, kept equal, one undo step per gesture.
        Property<double[]> radii = Property.derived(() -> {
            double[] read = new double[4];
            for (int i = 0; i < 4; i++) read[i] = CssValues.number(fields.valueOf(CornerBox.X[i].name), 0f);
            return read;
        }, next -> {
            // ONE STEP for a preset; a drag is already one, through the merge run its gesture holds.
            UndoStack history = fields.history();
            boolean grouped = history != null && !history.isMergeRunHeld();
            if (grouped) history.beginTransaction("Corners");
            try {
                for (int i = 0; i < 4; i++) {
                    String px = CssValues.px(next[i]);
                    if (!px.equals(fields.valueOf(CornerBox.X[i].name))) fields.value(CornerBox.X[i].name).set(px);
                    if (!px.equals(fields.valueOf(CornerBox.Y[i].name))) fields.value(CornerBox.Y[i].name).set(px);
                }
            } finally {
                if (grouped) history.endTransaction();
            }
        }).editedIn(fields.history());
        Property<Boolean> linked = Property.of(true);

        lab.content().append(new CornerBox("lab.corners", linked).bind(radii));

        UIElement row = new UIElement();
        row.addClass(StyleLab.ROW_CLASS);
        Button link = new Button("");
        link.addClass(StyleLab.KEYWORD_CLASS);
        link.attachListener(() -> linked.set(!linked.get()));
        PropertyWatch.follow(link, linked, on -> link.setText(on ? "Linked" : "Per corner"));
        row.append(link);
        for (String preset : List.of("0", "4", "8", "999")) {
            Button value = new Button(preset.equals("999") ? "pill" : preset);
            value.addClass(StyleLab.KEYWORD_CLASS);
            double[] all = new double[4];
            Arrays.fill(all, Double.parseDouble(preset));
            value.attachListener(() -> radii.set(all.clone()));
            row.append(value);
        }
        UIText readout = new UIText("");
        PropertyWatch.follow(readout, radii, at -> readout.setText(at[0] == at[1] && at[1] == at[2] && at[2] == at[3]
                ? CssValues.px(at[0])
                : CssValues.px(at[0]) + " " + CssValues.px(at[1]) + " " + CssValues.px(at[2]) + " " + CssValues.px(at[3])));
        row.append(readout);
        lab.content().append(row);

        lab.caption(radii.map(at -> "top-left " + CssValues.px(at[0]) + " · top-right " + CssValues.px(at[1])
                + " · bottom-right " + CssValues.px(at[2]) + " · bottom-left " + CssValues.px(at[3])));
        lab.open();
    }
}
