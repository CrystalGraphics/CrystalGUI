package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.text.UIText;

/**
 * The full-size editor for a declaration: a specimen, the controls that change it, a measured caption and
 * the CSS it produces — a dialog opened beside the row it was pressed on.
 *
 * <pre>{@code
 * StyleLab lab = StyleLab.over(chip, "Shadow");
 * lab.specimen(new UIText("Ag")).preview(TEXT_SHADOW, css);           // the value, shown on something
 * lab.form().prop(number("blur", "Blur").range(0f, 40f), blur);        // a row, bound to a property
 * lab.content().append(new OffsetPad("offset").bind(offset));         // a gizmo, bound the same way
 * lab.caption(Property.derived(() -> layers.get().size() + " shadows"));
 * lab.readout("text-shadow", css);
 * lab.open();
 * }</pre>
 *
 * <p><b>Everything in a lab is bound, and nothing is refreshed.</b> A row, a gizmo, the specimen and the
 * caption each follow a {@link Property} for as long as the lab is open, so an undo, an edit in the sheet's
 * text or a row elsewhere shows here with nothing to call. Build a composite value's parts with
 * {@link Property#map} over the declaration, and a selection as a {@code Property.of(0)} of the lab's own.</p>
 *
 * <ul>
 *   <li>A custom gizmo is a {@code ValueControl}: it is shown the value in {@code writeToWidgets} and reports
 *       an edit with {@code commit}, bracketing a drag with {@code beginInteraction}/{@code endInteraction}
 *       so the drag is one undo step.</li>
 *   <li>A derived property that writes several declarations names its history with
 *       {@code editedIn(fields.history())}, or its gesture is one undo step per frame.</li>
 * </ul>
 */
public final class StyleLab {

    public static final String LAB_CLASS = "__style-lab__";
    public static final String STAGE_CLASS = "__lab-stage__";
    public static final String GROUND_CLASS = "__lab-ground__";
    public static final String DARK_CLASS = "__dark__";
    public static final String LIGHT_CLASS = "__light__";
    public static final String PICKS_CLASS = "__lab-grounds__";
    public static final String PICK_CLASS = "__lab-ground-pick__";
    public static final String ACTIVE_CLASS = "__active__";
    public static final String ZOOM_CLASS = "__lab-zoom__";
    public static final String SPECIMEN_CLASS = "__lab-specimen__";
    /** A specimen with nothing inside it, which IS the value rather than carrying it. @see #specimen() */
    public static final String BARE_CLASS = "__bare__";
    /** What a lab puts INSIDE a specimen when the property needs something to act on. */
    public static final String SAMPLE_CLASS = "__lab-sample__";
    public static final String CONTENT_CLASS = "__lab-content__";
    public static final String CAPTION_CLASS = "__lab-caption__";
    public static final String CSS_CLASS = "__lab-css__";
    public static final String SHAPE_CLASS = "__lab-shape__";
    /** A button in a lab's own row: add a layer, a preset. */
    public static final String KEYWORD_CLASS = "__lab-keyword__";
    /** A horizontal run of gizmos or buttons. */
    public static final String ROW_CLASS = "__lab-row__";

    /** Between the row and the lab, so the value being edited is still readable beside it. */
    private static final float LAB_GAP = 8f;

    /**
     * The plate every lab's specimen stands on, {@link #DARK_CLASS} or {@link #LIGHT_CLASS}. One for the process: a
     * person judging values against white judges the next lab against white too, and an open lab follows a switch.
     */
    private static final Property<String> GROUND = Property.of(DARK_CLASS);

    /** The lab each window has open, so opening one closes the last. @see #open() */
    private static final Map<UIDocument, StyleLab> OPEN = new WeakHashMap<>();

    private final UIElement anchor;

    /**
     * A DIALOG, not a popover: a popover light-dismisses, and a lab is tuned while looking at the element it
     * changes. It stays until closed and brings a title bar to drag it by.
     */
    private final Dialog dialog;

    private final CanvasView stage = new CanvasView();
    private final UIElement specimen = new UIElement();
    private final UIText zoomLabel = new UIText("1.00x");
    private final List<UIElement> picks = new ArrayList<>();
    /** The kit's own panel, so a lab's rows are the inspector's rows at the lab's width. */
    private final ConfiguratorPanel panel = new ConfiguratorPanel();
    private final UIText caption = new UIText("");
    private final UIText readout = new UIText("");

    private StyleLab(UIElement anchor, String title) {
        this.anchor = anchor;
        dialog = new Dialog(title);
        dialog.addClass(LAB_CLASS);
        // GONE WITH IT, so a lab left open cannot outlive the element it edits.
        dialog.removeWhenClosed();

        buildStage();
        dialog.getContent().append(stage);
        panel.addClass(CONTENT_CLASS);
        dialog.getContent().append(panel);
        caption.addClass(CAPTION_CLASS);
        dialog.getContent().append(caption);
        readout.addClass(CSS_CLASS);
        dialog.getContent().append(readout);
        specimen();
    }

    /** A lab anchored to the row's chip, titled {@code title}. */
    public static StyleLab over(UIElement anchor, String title) {
        return new StyleLab(anchor, title);
    }

    /**
     * The lab's rows — a label column and a control column, the inspector's own.
     *
     * <pre>{@code
     * lab.form().prop(ConfigDescriptor.number("blur", "Blur").range(0f, 40f), blur);
     * }</pre>
     */
    public PanelForm form() {
        return panel.form();
    }

    /** Where a gizmo that is not a row goes — a pad, a bar, a stack of layers. */
    public UIElement content() {
        return panel;
    }

    /** The specimen as a plate of its own, for a value that needs no subject: a transform, a filter. */
    public StyleLab specimen() {
        specimen.removeAll();
        specimen.addClass(BARE_CLASS);
        return this;
    }

    /**
     * Puts {@code sample} inside the specimen, for a value that acts on something — text for a type or a
     * shadow. It gets {@link #SAMPLE_CLASS}.
     */
    public StyleLab specimen(UIElement sample) {
        specimen.removeAll();
        specimen.removeClass(BARE_CLASS);
        sample.addClass(SAMPLE_CLASS);
        specimen.append(sample);
        return this;
    }

    /** Shows {@code css} as {@code property} on the specimen. @see LiveEdits#follow */
    public StyleLab preview(StyleProperty<?> property, Property<String> css) {
        LiveEdits.follow(specimen, property, css);
        return this;
    }

    /** Puts colored shapes behind the specimen, which is what a blur or a refraction needs to be visible. */
    public StyleLab backdrop() {
        for (int i = 0; i < 3; i++) {
            UIElement shape = new UIElement();
            shape.addClass(SHAPE_CLASS);
            shape.addClass(SHAPE_CLASS + i);
            // APPENDED, never addNode: addNode writes left/top inline, which beats the offsets in the sheet.
            stage.content().append(shape);
        }
        return this;
    }

    /** What the caption says, followed while the lab is open. */
    public StyleLab caption(Property<String> text) {
        PropertyWatch.follow(caption, text, caption::setText);
        return this;
    }

    /** Prints {@code css} as the declaration the sheet holds, followed while the lab is open. */
    public StyleLab readout(String name, Property<String> css) {
        PropertyWatch.follow(readout, css,
                value -> readout.setText(name + ": " + (value == null || value.isBlank() ? "—" : value)));
        return this;
    }

    private void buildStage() {
        stage.addClass(STAGE_CLASS);
        stage.addClass(GROUND_CLASS);
        stage.setZoomRange(0.25f, 32f);

        specimen.addClass(SPECIMEN_CLASS);
        stage.addNode(specimen, 12f, 12f);

        UIElement grounds = new UIElement();
        grounds.addClass(PICKS_CLASS);
        grounds.append(pick(LIGHT_CLASS));
        grounds.append(pick(DARK_CLASS));
        stage.addOverlay(grounds);
        ground(GROUND.get());
        PropertyWatch.follow(stage, GROUND, this::ground);

        zoomLabel.addClass(ZOOM_CLASS);
        stage.addOverlay(zoomLabel);
        stage.onViewChanged.connect(
                () -> zoomLabel.setText(String.format(Locale.ROOT, "%.2fx", stage.getZoom())));

        // RIGHT-CLICK IS HOME, as the gallery's text-lab canvas does it.
        stage.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() == CgMouseCodes.RIGHT_BUTTON) {
                home();
                event.preventDefault();
            }
        }, false, true);
    }

    private UIElement pick(String tone) {
        UIElement pick = new UIElement();
        pick.addClass(PICK_CLASS);
        pick.addClass(tone);
        pick.setHitTest(true);
        pick.onMouseDown.attachListener((element, event) -> {
            GROUND.set(tone);
            event.preventDefault();
        }, false, true);
        picks.add(pick);
        return pick;
    }

    /** The specimen at actual size in the middle of the stage; false while there is nothing measured to centre. */
    private boolean home() {
        var bounds = stage.contentBounds();
        var view = stage.box();
        if (bounds == null || view == null || view.width() <= 0f || bounds.width() <= 0f) return false;
        stage.setZoom(1f);
        stage.centerOnWorld(bounds.centerX(), bounds.centerY());
        return true;
    }

    /** Switches the plate the specimen stands on. A class carries it, so the colors stay in the sheet. */
    private void ground(String tone) {
        stage.removeClass(DARK_CLASS);
        stage.removeClass(LIGHT_CLASS);
        stage.addClass(tone);
        for (UIElement pick : picks) pick.toggleClass(ACTIVE_CLASS, pick.hasClass(tone));
    }

    /**
     * Opens it beside the row, replacing whatever lab that window already had open.
     *
     * <p><b>Parented to the top layer, not to the chip.</b> Inside the chip every press in the lab bubbled
     * back to it and re-opened the lab it was showing. @see StyleChipReentryTest</p>
     */
    public StyleLab open() {
        UIDocument window = anchor.document();
        if (window == null) return this;
        StyleLab showing = OPEN.put(window, this);
        if (showing != null && showing != this) showing.close();

        if (dialog.parentElement() == null) window.topLayerNode().append(dialog);
        dialog.show();
        // PROMOTED BY HAND: only showModal() promotes, and a lab is modeless so the element stays reachable.
        window.promote(dialog);
        dialog.placeBeside(anchor, AnchoredPlacement.Side.RIGHT, LAB_GAP);
        // CENTRED ONCE MEASURED: the specimen was placed at the stage's corner, leaving most of the plate empty
        // under a line of text in its top-left.
        window.animation().afterLayout(stage, delta -> !home());
        return this;
    }

    public void close() {
        dialog.close();
    }
}
