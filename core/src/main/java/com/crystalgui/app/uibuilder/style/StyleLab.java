package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
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
    /** On the copy of the specimen showing where it started. @see #ghost */
    public static final String GHOST_CLASS = "__lab-ghost__";
    public static final String CONTENT_CLASS = "__lab-content__";
    public static final String CAPTION_CLASS = "__lab-caption__";
    public static final String CSS_CLASS = "__lab-css__";
    public static final String SHAPE_CLASS = "__lab-shape__";
    /** The drifting shapes behind a glass specimen, moved as one. */
    public static final String SHAPES_CLASS = "__lab-shapes__";
    /** Straight-edged detail behind a glass specimen, which is what shows a bend: a word and a line under it. */
    public static final String BACKDROP_TEXT_CLASS = "__lab-backdrop-text__";
    public static final String BACKDROP_SUB_CLASS = "__lab-backdrop-sub__";

    /** Where the specimen is first placed on the stage. */
    private static final float SPECIMEN_AT = 12f;
    /** A button in a lab's own row: add a layer, a preset. */
    public static final String KEYWORD_CLASS = "__lab-keyword__";
    /** A horizontal run of gizmos or buttons. */
    public static final String ROW_CLASS = "__lab-row__";
    /** A row of preset pills, tighter than a row of gizmos. */
    public static final String PRESETS_CLASS = "__lab-presets__";

    /** Between the row and the lab, so the value being edited is still readable beside it. */
    private static final float LAB_GAP = 8f;

    /**
     * The plate a person picked for every lab, {@link #DARK_CLASS} or {@link #LIGHT_CLASS}, or "" before anyone has —
     * which means each lab contrasts with its text. One for the process: a person judging values against white
     * judges the next lab against white too, and an open lab follows a switch.
     */
    private static final Property<String> GROUND = Property.of("");

    /** The text color a lab left to itself contrasts its plate with, or null for the dark plate. @see #contrastWith */
    @Nullable
    private Supplier<Integer> contrast;

    /** The lab each window has open, so opening one closes the last. @see #open() */
    private static final Map<UIDocument, StyleLab> OPEN = new WeakHashMap<>();

    private final UIElement anchor;

    /**
     * A DIALOG, not a popover: a popover light-dismisses, and a lab is tuned while looking at the element it
     * changes. It stays until closed and brings a title bar to drag it by.
     */
    private final LabDialog dialog;

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
        dialog = new LabDialog(title);
        dialog.addClass(LAB_CLASS);
        // GONE WITH IT, so a lab left open cannot outlive the element it edits.
        dialog.removeWhenClosed();

        buildStage();
        dialog.getContent().append(stage);
        panel.addClass(CONTENT_CLASS);
        dialog.getContent().append(panel);
        caption.addClass(CAPTION_CLASS);
        readout.addClass(CSS_CLASS);
        dialog.getContent().append(readout);
        specimen();
    }

    /**
     * The lab's window, and where Ctrl+Z inside it finds its history: the declaration's. A press on a gizmo, a
     * stack button or the plate focuses nothing else, so without this the walk started at no element and every
     * edit made there was out of Ctrl+Z's reach.
     */
    private static final class LabDialog extends Dialog implements DataProvider {

        @Nullable
        private Property<String> edits;

        LabDialog(String title) {
            super(title);
            // ACTIVATED BY A PRESS ANYWHERE IN IT, as a window is: a press on a control that takes focus still gives
            // it focus, and one on anything else leaves the lab holding it rather than nothing.
            setFocusPolicy(FocusPolicy.CLICK);
        }

        @Override
        @Nullable
        public Object getData(DataKey<?> key) {
            return key == UiDataKeys.UNDO_STACK && edits != null ? edits.history() : null;
        }
    }

    /** A lab anchored to the row's chip, titled {@code title}. */
    public static StyleLab over(UIElement anchor, String title) {
        return new StyleLab(anchor, title);
    }

    /**
     * The color the specimen's text is drawn in, so a plate nobody picked is the one it reads against: dark for
     * light text, light for dark. A pick overrides it for every lab.
     *
     * <pre>{@code
     * lab.contrastWith(() -> node.getStyle().computed().get(StylePropertyRegistry.COLOR));
     * }</pre>
     *
     * <p>Asked every frame the lab is open, so recoloring the text flips a plate left to itself.</p>
     */
    public StyleLab contrastWith(Supplier<Integer> textColor) {
        contrast = textColor;
        return this;
    }

    /** Forgets a pick, so every lab contrasts with its text again. For a test that picked one. */
    static void forgetGroundPick() {
        GROUND.set("");
    }

    /** The plate shown: the pick, else the one that contrasts with the text, else dark. */
    private String groundShown() {
        String picked = GROUND.get();
        if (!picked.isEmpty()) return picked;
        Integer argb = contrast == null ? null : contrast.get();
        if (argb == null) return DARK_CLASS;
        // RELATIVE LUMINANCE, WCAG's: light text reads on the dark plate, dark text on the light one.
        return luminance(argb) > 0.5 ? DARK_CLASS : LIGHT_CLASS;
    }

    private static double luminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 0xFF) + 0.7152 * linear((argb >> 8) & 0xFF) + 0.0722 * linear(argb & 0xFF);
    }

    private static double linear(int channel) {
        double c = channel / 255d;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
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

    /**
     * Puts a copy of the specimen where it would be with nothing applied, behind it — for a lab whose value MOVES
     * what it is on.
     *
     * <pre>{@code
     * lab.specimen(new UIText("F")).ghost(new UIText("F"));   // the same mark, where it started
     * }</pre>
     *
     * <p>A translate on a plate in an empty stage is a plate in an empty stage, and a rotate leaves a rectangle
     * looking like a rectangle. The distance and the turn are the difference between the two, which is how a
     * design tool shows a moved object. Under the specimen by z-index rather than by order, so it does not depend
     * on which was added first.</p>
     */
    public StyleLab ghost(UIElement sample) {
        UIElement ghost = new UIElement();
        ghost.addClass(SPECIMEN_CLASS);
        ghost.addClass(GHOST_CLASS);
        sample.addClass(SAMPLE_CLASS);
        ghost.append(sample);
        stage.addNode(ghost, SPECIMEN_AT, SPECIMEN_AT);
        return this;
    }

    /** Shows {@code css} as {@code property} on the specimen. @see LiveEdits#follow */
    public StyleLab preview(StyleProperty<?> property, Property<String> css) {
        dialog.edits = css;
        LiveEdits.follow(specimen, property, css);
        return this;
    }

    /**
     * Shows another declaration on the specimen beside the one the lab is about — the origin a transform turns
     * about, which is the difference between a spin and an orbit.
     *
     * <pre>{@code
     * lab.also(TRANSFORM_ORIGIN_X, originX).preview(TRANSFORM, css);
     * }</pre>
     *
     * <p>Unlike {@link #preview} it does not name the lab's history: the declaration the lab is <em>about</em> is
     * the one Ctrl+Z inside it means.</p>
     */
    public StyleLab also(StyleProperty<?> property, Property<String> css) {
        LiveEdits.follow(specimen, property, css);
        return this;
    }

    /**
     * Puts something worth filtering behind the specimen and lets it be dragged over it — the gallery's glass page
     * in small: large colored shapes that DRIFT, for a blur and a saturation to act on, and text, whose straight
     * edges are what a refraction visibly bends. A still backdrop hides the one thing that tells a live capture
     * from a texture.
     */
    public StyleLab backdrop() {
        UIElement shapes = new UIElement();
        shapes.addClass(SHAPES_CLASS);
        for (int i = 0; i < 5; i++) {
            UIElement shape = new UIElement();
            shape.addClass(SHAPE_CLASS);
            shape.addClass(SHAPE_CLASS + i);
            shapes.append(shape);
        }
        // APPENDED, never addNode: addNode writes left/top inline, which beats the offsets in the sheet.
        stage.content().append(shapes);
        UIText word = new UIText("REFRACT");
        word.addClass(BACKDROP_TEXT_CLASS);
        stage.content().append(word);
        UIText line = new UIText("a straight edge is what makes a lens legible");
        line.addClass(BACKDROP_SUB_CLASS);
        stage.content().append(line);

        // A COMPOSITOR OVERRIDE, as the gallery's: layout-free, owned by the shapes, gone with the lab.
        float[] phase = new float[1];
        shapes.onConnected(() -> {
            UIDocument window = shapes.document();
            if (window == null) return;
            window.animation().every(shapes, delta -> {
                phase[0] += delta;
                Box box = shapes.box();
                if (box != null) {
                    box.setTransform(Transform.translate((float) Math.sin(phase[0] * 0.23) * 12f,
                            (float) Math.cos(phase[0] * 0.17) * 8f));
                }
                return true;
            });
        });

        // THE GLASS MOVES, the backdrop does not: dragging it over an edge is how a bezel's bend is seen.
        float[] at = {SPECIMEN_AT, SPECIMEN_AT};
        float[] from = new float[2];
        StyleGizmos.drag(specimen, () -> {
            from[0] = at[0];
            from[1] = at[1];
        }, (dx, dy) -> {
            at[0] = from[0] + dx;
            at[1] = from[1] + dy;
            stage.moveNode(specimen, at[0], at[1]);
        }, () -> { });
        return this;
    }

    /**
     * Leaves the stage out, for a lab whose own gizmo already draws the value -- a corner box is the shape it rounds,
     * and a second copy of it on a plate only took the height the rows needed.
     */
    public StyleLab withoutStage() {
        dialog.getContent().remove(stage);
        return this;
    }

    /**
     * Puts {@code element} above the rows, outside their scroll, where the stage stands in other labs -- for a gizmo
     * that is the lab's specimen, which has to stay in view while the rows under it are scrolled.
     */
    public StyleLab header(UIElement element) {
        dialog.getContent().insertAt(dialog.getContent().indexOf(panel), element);
        return this;
    }

    /** Tags this lab's window with {@code styleClass}, so a sheet can reach its own layout. */
    public StyleLab addClass(String styleClass) {
        dialog.addClass(styleClass);
        return this;
    }

    /** What the caption says, followed while the lab is open. */
    public StyleLab caption(Property<String> text) {
        // ONLY A LAB WITH SOMETHING TO SAY HAS ONE: an empty caption still took a line, its padding and a gap
        // above the readout, which is height the rows scrolled for.
        if (caption.parentElement() == null) {
            dialog.getContent().insertAt(dialog.getContent().indexOf(readout), caption);
        }
        PropertyWatch.follow(caption, text, caption::setText);
        return this;
    }

    /**
     * Prints {@code css} as the declaration reads, followed while the lab is open: one layer to a line, so a long
     * stack breaks between its layers rather than inside a length.
     */
    public StyleLab readout(String name, Property<String> css) {
        return readout(Map.of(name, css));
    }

    /**
     * Prints several declarations as a rule body holds them, only those set, followed while the lab is open -- for a lab
     * that edits more than one. Run together and wrapped at the lab's width: a line each took eight lines for a border.
     *
     * <pre>{@code
     * lab.readout(new LinkedHashMap<>(Map.of("border-radius", radius, "border-width", widths)));
     * }</pre>
     *
     * <p><b>Iterated in the map's own order</b>, so a lab that cares hands over a {@code LinkedHashMap}.</p>
     */
    public StyleLab readout(Map<String, Property<String>> declarations) {
        if (declarations.isEmpty()) return this;
        dialog.edits = declarations.values().iterator().next();
        // A DECLARATION A LAB IS ABOUT IS PRINTED BLANK OR NOT, where one of several is printed only when it is set:
        // a lab's own value reads as "—" rather than vanishing, and a rule body does not carry empty declarations.
        boolean several = declarations.size() > 1;
        PropertyWatch.follow(readout, Property.derived(() -> {
            StringBuilder out = new StringBuilder();
            declarations.forEach((name, css) -> {
                String value = css.get();
                if (several && (value == null || value.isBlank())) return;
                if (out.length() > 0) out.append(' ');
                out.append(name).append(": ").append(readable(value));
                if (several) out.append(';');
            });
            return out.length() == 0 ? "—" : out.toString();
        }), readout::setText);
        return this;
    }

    private static String readable(@Nullable String value) {
        if (value == null || value.isBlank()) return "—";
        // A LAYER A LINE AND NOTHING FURTHER: the lab is a fixed width and the readout wraps within it, so a run
        // of functions or a long call flows. A line a function made the glass lab's footer fourteen lines tall.
        List<String> layers = CssValues.layerStack(value);
        if (layers.size() < 2) return CssValues.readable(value);
        List<String> shown = new ArrayList<>(layers.size());
        for (String layer : layers) {
            String readable = CssValues.readable(CssValues.bodyOf(layer));
            shown.add(CssValues.isOff(layer) ? "/* " + readable + " */" : readable);
        }
        return String.join(",\n    ", shown);
    }

    private void buildStage() {
        stage.addClass(STAGE_CLASS);
        stage.addClass(GROUND_CLASS);
        stage.setZoomRange(0.25f, 32f);

        specimen.addClass(SPECIMEN_CLASS);
        stage.addNode(specimen, SPECIMEN_AT, SPECIMEN_AT);

        UIElement grounds = new UIElement();
        grounds.addClass(PICKS_CLASS);
        grounds.append(pick(LIGHT_CLASS));
        grounds.append(pick(DARK_CLASS));
        stage.addOverlay(grounds);
        PropertyWatch.follow(stage, Property.derived(this::groundShown), this::ground);

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
