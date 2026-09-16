package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.joml.Vector2f;

import com.crystalgraphics.platform.input.CgMouseCodes;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.text.UIText;

/**
 * The full-size editor for one declaration: a specimen, the gizmos that change it, the measured caption,
 * and the CSS it produces — opened beside the row rather than squeezed into it.
 *
 * <pre>{@code
 * StyleLab lab = StyleLab.over(chip, "Background", property, css);
 * lab.specimen(() -> new UIText("Handgloves"));   // what the value is shown ON, or omit for none
 * lab.content().append(new GradientBar(css));
 * lab.caption(() -> stops + " stops at " + angle + "°");
 * lab.open();
 * }</pre>
 *
 * <p><b>The specimen stands on a canvas</b> — panned, zoomed, and reset with a right-click — over a ground
 * you pick, because the properties worth a lab are the ones a single background cannot settle: a stroke, a
 * shadow, a translucent fill. It is a real element with the real declaration applied, and it carries what
 * the lab does not edit off the element too, so what is shown is what the engine will draw rather than an
 * illustration of it.</p>
 *
 * <p>The CSS readout under the gizmos is the value itself, live. It is the same string the sheet is about
 * to hold: a lab whose printed value and written value can differ is a lab you have to double-check.</p>
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
    /** A specimen with nothing inside it, which IS the value rather than carrying it. @see #specimen */
    public static final String BARE_CLASS = "__bare__";
    /** What a lab puts INSIDE a specimen when the property needs something to act on. */
    public static final String SAMPLE_CLASS = "__lab-sample__";
    public static final String CONTENT_CLASS = "__lab-content__";
    public static final String CAPTION_CLASS = "__lab-caption__";
    public static final String CSS_CLASS = "__lab-css__";
    public static final String SHAPE_CLASS = "__lab-shape__";

    private final UIElement anchor;
    private final Property<String> css;

    @Nullable
    private final StyleProperty<?> property;

    /**
     * A DIALOG, not a popover. A popover light-dismisses, so every press outside took the lab with it —
     * which is wrong for a panel you tune a value in while looking at the element it changes, and which
     * no amount of dismissal rules fixes, because that is what a popover IS. A dialog stays until it is
     * closed, and brings a title bar to drag it by.
     */
    private final Dialog dialog;

    /** ONE preview, panned and zoomed. @see #specimen(Supplier) */
    private final CanvasView stage = new CanvasView();
    private final UIElement specimen = new UIElement();
    private final UIText zoomLabel = new UIText("1.00x");
    private final List<UIElement> picks = new ArrayList<>();
    /** The kit's own panel, so a lab's rows are the inspector's rows at the lab's width. */
    private final ConfiguratorPanel panel = new ConfiguratorPanel();
    private final PanelForm form = panel.form();
    private final UIText caption = new UIText("");
    private final UIText readout = new UIText("");
    /** What the lab put inside the specimen: a line of text, a glyph pair, or nothing. */
    @Nullable
    private UIElement sample;

    @Nullable
    private Supplier<String> captionText;

    private final String title;

    private StyleLab(UIElement anchor, String title, @Nullable StyleProperty<?> property, Property<String> css) {
        this.title = title;
        this.anchor = anchor;
        this.property = property;
        this.css = css;

        dialog = new Dialog(title);
        dialog.addClass(LAB_CLASS);
        // GONE WITH IT, so a lab left open cannot outlive the element it edits. A dialog is not dismissed
        // by anything, which is the point of using one and also the one way it can be forgotten.
        dialog.removeWhenClosed();

        buildStage();
        dialog.getContent().append(stage);

        panel.addClass(CONTENT_CLASS);
        dialog.getContent().append(panel);

        caption.addClass(CAPTION_CLASS);
        dialog.getContent().append(caption);

        readout.addClass(CSS_CLASS);
        dialog.getContent().append(readout);
    }

    /** A lab for {@code css}, anchored to the row's gizmo. */
    public static StyleLab over(UIElement anchor, String title, @Nullable StyleProperty<?> property,
                               Property<String> css) {
        return new StyleLab(anchor, title, property, css);
    }

    /**
     * The lab's rows — a label column and a control column, the same ones the inspector is made of.
     *
     * <pre>{@code
     * lab.form().prop(ConfigDescriptor.number("blur", "Blur").range(0f, 40f), blur);
     * lab.form().control("ramp", "Ramp", gradientBar);       // a gizmo of the lab's own
     * }</pre>
     *
     * <p>A lab built out of loose rows was ragged at the label column and ran past its own width, which is
     * how a popover ends up painting controls where their boxes are not. The kit already solves both.</p>
     */
    public PanelForm form() {
        return form;
    }

    /** Where a gizmo that is not a row goes — a bar, a pad, a box. */
    public UIElement content() {
        return panel;
    }

    /**
     * The preview: one specimen on a plane you can pan and zoom, over a ground you pick.
     *
     * <h3>One, where there were two</h3>
     *
     * <p>The pair existed because a stroke or a translucent fill reads on one ground and vanishes on the
     * other, and a fixed plate cannot be argued with. A ground the viewer <b>chooses</b> answers the same
     * question in half the width, and buys back something the pair could not: with the ground no longer
     * fixed, the specimen can carry the element's own {@code color} rather than one picked to contrast
     * with a plate, so the preview is the element instead of a legible impression of it.</p>
     */
    public StyleLab specimen() {
        return specimen(null);
    }

    /** As {@link #specimen()}, with something of the lab's own inside it: a line of text for the type lab. */
    public StyleLab specimen(@Nullable Supplier<UIElement> content) {
        specimen.removeAll();
        sample = null;
        if (content != null) {
            sample = content.get();
            specimen.append(sample);
        }
        // A PLATE ONLY WHEN THE SPECIMEN IS THE VALUE. A transform or a glass filter on a transparent box
        // of nothing shows nothing, so those need a surface; a line of text is its own subject, and a
        // panel behind it is just a grey rectangle around the thing you are looking at.
        if (sample == null) {
            specimen.addClass(BARE_CLASS);
        } else {
            specimen.removeClass(BARE_CLASS);
        }
        return this;
    }

    /** What {@link #specimen(Supplier)} put inside, or null for a lab whose value needs no subject. */
    @Nullable
    public UIElement sample() {
        return sample;
    }

    /**
     * Puts coloured shapes behind the specimen, which is what a blur or a refraction needs to be visible.
     *
     * <p>Siblings on the plane rather than children, because a backdrop filter reads what is BEHIND its
     * element and would never see its own content. The specimen sits over them on {@code z-index}, which
     * keeps this independent of the order the two were added in.</p>
     */
    public StyleLab backdrop() {
        for (int i = 0; i < 3; i++) {
            UIElement shape = new UIElement();
            shape.addClass(SHAPE_CLASS);
            shape.addClass(SHAPE_CLASS + i);
            // APPENDED, never addNode: each shape carries its own offset in the sheet, and addNode
            // writes left/top INLINE -- which beats a class rule, so all three would stack on the
            // plane origin. `position: absolute` is in the sheet too, so nothing here is needed.
            stage.content().append(shape);
        }
        return this;
    }

    /** The canvas the specimen stands on, for a lab that wants to put something else on the plane. */
    public CanvasView stage() {
        return stage;
    }

    private void buildStage() {
        stage.addClass(STAGE_CLASS);
        stage.addClass(GROUND_CLASS);
        stage.addClass(DARK_CLASS);
        stage.setZoomRange(0.25f, 32f);

        specimen.addClass(SPECIMEN_CLASS);
        stage.addNode(specimen, 12f, 12f);

        UIElement grounds = new UIElement();
        grounds.addClass(PICKS_CLASS);
        grounds.append(pick(LIGHT_CLASS));
        grounds.append(pick(DARK_CLASS));
        stage.addOverlay(grounds);

        zoomLabel.addClass(ZOOM_CLASS);
        stage.addOverlay(zoomLabel);
        stage.onViewChanged.connect(
                () -> zoomLabel.setText(String.format(Locale.ROOT, "%.2fx", stage.getZoom())));

        // RIGHT-CLICK IS HOME, as the gallery's own text-lab canvas does it: a specimen zoomed in on one
        // counter of one glyph is easy to lose, and a reset button would cost a row to say so.
        stage.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() == CgMouseCodes.RIGHT_BUTTON) {
                stage.setZoom(1f).setPan(0f, 0f);
                event.preventDefault();
            }
        }, false, true);
    }

    private UIElement pick(String tone) {
        UIElement pick = new UIElement();
        pick.addClass(PICK_CLASS);
        pick.addClass(tone);
        if (DARK_CLASS.equals(tone)) pick.addClass(ACTIVE_CLASS);
        pick.setHitTest(true);
        pick.onMouseDown.attachListener((element, event) -> {
            ground(tone);
            event.preventDefault();
        }, false, true);
        picks.add(pick);
        return pick;
    }

    /** Switches the plate the specimen stands on. A class carries it, so the colours stay in the sheet. */
    private void ground(String tone) {
        stage.removeClass(DARK_CLASS);
        stage.removeClass(LIGHT_CLASS);
        stage.addClass(tone);
        for (UIElement pick : picks) {
            if (pick.hasClass(tone)) {
                pick.addClass(ACTIVE_CLASS);
            } else {
                pick.removeClass(ACTIVE_CLASS);
            }
        }
    }

    /** What the caption says, asked whenever the value changes — measured, never a literal. */
    public StyleLab caption(Supplier<String> text) {
        this.captionText = text;
        return this;
    }

    /**
     * Re-reads the value: the specimens take it, the caption is asked again, and the readout prints what
     * will be written. Call after any gizmo changes something.
     */
    public void refresh() {
        String value = css.get();
        if (property != null) {
            if (value == null || value.isBlank()) {
                LiveEdits.clearInline(specimen, property);
            } else {
                LiveEdits.setInline(specimen, cast(property), value);
            }
        }
        caption.setText(captionText == null ? "" : captionText.get());
        readout.setText(name() + ": " + (value == null || value.isBlank() ? "—" : value));
    }

    private String name() {
        return property == null ? "value" : property.name;
    }

    /**
     * Opens it beside the row, replacing whatever lab that window already had open.
     *
     * <p><b>Parented away from the chip that opened it.</b> A popup attaches itself to the nearest
     * ancestor that accepts children, which for a control means the control — and then every press inside
     * the lab bubbles back to it: the chip re-opens the lab it is already showing, the chip's pointer
     * cursor covers the whole thing, and nothing a person clicks ever happens. Measured, not reasoned
     * about; see {@code StyleChipReentryTest}.</p>
     *
     * <p><b>One at a time, per window.</b> A popover closed itself when the next one opened; a dialog does
     * not, so pressing a second row would leave two labs stacked and a tenth would leave ten.</p>
     */
    public StyleLab open() {
        refresh();
        UIDocument window = anchor.document();
        if (window == null) return this;

        StyleLab showing = OPEN.put(window, this);
        if (showing != null && showing != this) showing.close();

        if (dialog.parentElement() == null) window.topLayerNode().append(dialog);
        dialog.show();
        // PROMOTED BY HAND: only showModal() promotes, and this is deliberately modeless — the element
        // being edited has to stay reachable. Same note as ColorControl's own picker dialog.
        window.promote(dialog);
        // PLACED AND REFRESHED ONCE IT HAS BEEN LAID OUT, both for the same reason: neither the dialog nor
        // the specimen has a box until then. A caption that measures the specimen said "not laid out yet",
        // and a lab placed against its own unknown size could not know whether it fitted.
        window.animation().afterLayout(dialog, delta -> {
            if (!dialog.isConnected()) return false;
            refresh();
            placeBeside(window, anchor);
            return false;
        });
        return this;
    }

    public void close() {
        dialog.close();
    }

    /** The dialog itself, for a lab that wants to add something outside the standard parts. */
    public Dialog dialog() {
        return dialog;
    }

    /** The window the lab is in, or null before it is opened. */
    @Nullable
    public UIDocument window() {
        return dialog.document();
    }

    /** The lab each window has open, so opening one closes the last. @see #open() */
    private static final Map<UIDocument, StyleLab> OPEN = new WeakHashMap<>();

    /**
     * Beside the row it was opened from, flipped and clamped to the window.
     *
     * <p><b>{@link AnchoredPlacement}, not arithmetic on the row's world matrix.</b> That is the one
     * definition of where a popup goes beside a thing, and it already answers the two questions a lab
     * needs: it flips to the other side when there is no room, and it clamps so the panel stays on
     * screen. Hand-rolled from {@code localToWorld} it did neither — a lab opened from a row near the
     * right edge went off it, which is the exact trap that class documents, since a world coordinate
     * carries {@code uiScale} and {@code left}/{@code top} are logical.</p>
     */
    private void placeBeside(UIDocument window, UIElement row) {
        Box self = dialog.box();
        Box root = window.box();
        AnchoredPlacement.Rect rect = AnchoredPlacement.anchorRectInRoot(row, window);
        if (self == null || root == null || rect == null) return;
        Vector2f at = AnchoredPlacement.resolve(rect, self.width(), self.height(),
                root.width(), root.height(), AnchoredPlacement.Side.RIGHT, LAB_GAP);
        dialog.moveTo(at.x(), at.y());
    }

    /** Between the row and the lab, so the value being edited is still readable beside it. */
    private static final float LAB_GAP = 8f;

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
