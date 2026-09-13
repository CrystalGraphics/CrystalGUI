package com.crystalgui.app.uibuilder.canvas.transform;

import java.util.function.Consumer;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

import org.joml.Vector2f;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ToolbarForm;
import com.crystalgui.widget.config.control.NumberControl;

/**
 * The numbers behind a Free Transform, live in both directions — the tool's page in the editor's
 * {@code ContextToolbar}, as Photoshop's options bar belongs to the transform while its box is up.
 *
 * <p>Everything the box can be dragged into can also be typed: where the element has been moved to, where
 * the pivot sits, the scale, the rotation and the two skews. Dense the way Photoshop's and Paint.NET's
 * bars are — a letter per number, the unit inside the value, and the full name on hover, where W and H
 * also give the size the scale produces.</p>
 *
 * <p>Each number is a field bound to the box's gesture, so it follows every drag, typed value, undo step
 * and open and close with nothing telling it, and every change made here is a step of the box's
 * {@code history} — a typed number one step, a scrub one step as a whole.</p>
 *
 * <h3>Every number says what the TRANSFORM is doing</h3>
 *
 * <p>W and H are percentages, R and the skews are degrees from square, and X and Y are how far the
 * element has been moved from where layout put it — all of them zero, or 100%, on an element nothing has
 * touched. None of them is an absolute coordinate, because the element's absolute position is layout's
 * answer and not this tool's.</p>
 *
 * <p><b>X and Y are measured at the element's centre</b>, which is what makes them the element's and
 * nobody else's: measured at a corner, rotating in place would report a move the element never made, and
 * read off the translate they would jump whenever the pivot was placed — the translate absorbs that, to
 * hold the box still.</p>
 *
 * <h3>The grid is the pivot, and PX/PY are its numbers</h3>
 *
 * <p>As Photoshop's reference point is. A cell puts the pivot on one of nine points without moving the
 * box; PX and PY say where it is as a percentage of the box, so the nine cells are the round numbers of
 * that same pair. A typed scale, rotation or skew holds the pivot still with no help, because
 * {@code transform-origin} is the one point every rotate, skew and scale leaves where it is.</p>
 *
 * <p>Each field writes only its own quantity — except W and H while the chain between them is on, when
 * either takes the other with it at the ratio the two had.</p>
 */
public final class TransformOptionsBar extends UIElement {

    public static final Name NAME = Name.of("transformoptions");

    public static final String BAR_CLASS = "__transform-options__";

    /** On the chain between W and H, which the sheet draws as a link. */
    public static final String LINK_CLASS = "__transform-link__";

    private final TransformBox box;

    /** Whether W and H scale together. */
    private final Property<Boolean> linked = Property.of(false);

    private final Property<double[]> pivot;

    private final NumberControl positionX;
    private final NumberControl pivotFractionX;
    private final NumberControl scaleWidth;
    private final NumberControl angle;
    private final NumberControl leanX;

    public TransformOptionsBar(TransformBox box) {
        super(NAME);
        this.box = box;
        addClass(BAR_CLASS);

        // PHOTOSHOP'S GROUPS, with the pivot's own numbers beside the grid that picks them. Every number
        // here is unbounded, so each scrubs at one unit per pixel -- a percent, a pixel, a degree.
        ToolbarForm form = ToolbarForm.into(this);
        pivot = Property.derived(
                () -> new double[] {fraction(gesture().originX(), gesture().width()),
                        fraction(gesture().originY(), gesture().height())},
                at -> edit(g -> g.setOrigin((float) at[0] * g.width(), (float) at[1] * g.height())));
        form.prop(ConfigDescriptor.anchor("pivot", "Pivot"), pivot.editedIn(box.history()));
        pivotFractionX = control(number(form, "px", "PX", "Pivot X", "%",
                g -> fraction(g.originX(), g.width()) * 100d,
                (g, v) -> g.setOrigin((float) (v / 100d) * g.width(), g.originY())));
        number(form, "py", "PY", "Pivot Y", "%",
                g -> fraction(g.originY(), g.height()) * 100d,
                (g, v) -> g.setOrigin(g.originX(), (float) (v / 100d) * g.height()));

        form.separator();
        positionX = control(number(form, "x", "X", "Position X", null,
                TransformOptionsBar::movedX, TransformOptionsBar::moveToX));
        number(form, "y", "Y", "Position Y", null, TransformOptionsBar::movedY, TransformOptionsBar::moveToY);

        form.separator();
        Configurator width = number(form, "w", "W", "Width", "%", g -> g.scaleX() * 100d, (g, v) -> scaleTo(g, v, true));
        width.describeWith(size("Width", g -> g.width() * Math.abs(g.scaleX())));
        scaleWidth = control(width);
        form.prop(ConfigDescriptor.bool("link", "Maintain aspect ratio").toggle(true).shortLabel(""), linked)
                .addClass(LINK_CLASS);
        number(form, "h", "H", "Height", "%", g -> g.scaleY() * 100d, (g, v) -> scaleTo(g, v, false))
                .describeWith(size("Height", g -> g.height() * Math.abs(g.scaleY())));

        form.separator();
        angle = control(number(form, "r", "R", "Rotation", "°", g -> Math.toDegrees(g.rotation()),
                (g, v) -> g.setRotation((float) Math.toRadians(v))));

        form.separator();
        leanX = control(number(form, "sx", "SX", "Skew X", "°", g -> Math.toDegrees(g.skewXRadians()),
                (g, v) -> g.setSkew((float) Math.toRadians(v), g.skewYRadians())));
        number(form, "sy", "SY", "Skew Y", "°", g -> Math.toDegrees(g.skewYRadians()),
                (g, v) -> g.setSkew(g.skewXRadians(), (float) Math.toRadians(v)));
    }

    /** A number field bound to one quantity of the gesture. */
    private Configurator number(ToolbarForm form, String id, String letter, String name, String unit,
                                ToDoubleFunction<TransformGesture> read,
                                ObjDoubleConsumer<TransformGesture> write) {
        Property<Double> value = Property.derived(() -> round(read.applyAsDouble(gesture())),
                v -> edit(g -> write.accept(g, v))).editedIn(box.history());
        return form.prop(ConfigDescriptor.number(id, name).shortLabel(letter).unit(unit), value);
    }

    private static NumberControl control(Configurator cell) {
        return (NumberControl) cell.control();
    }

    /** What W and H say on hover: the size the scale produces, which is what a percentage is OF. */
    private Property<String> size(String name, ToDoubleFunction<TransformGesture> pixels) {
        return Property.derived(() -> name + ": " + Math.round(pixels.applyAsDouble(gesture())) + " px");
    }

    private TransformGesture gesture() {
        return box.gesture();
    }

    /** Applies a change to an open box as one step of its history, and shows it. */
    private void edit(Consumer<TransformGesture> change) {
        if (!box.isActive()) return;
        box.step(() -> change.accept(box.gesture()));
        box.preview();
    }

    /** Whether W and H scale together. */
    public boolean isLinked() {
        return Boolean.TRUE.equals(linked.get());
    }

    /** While linked, a typed or scrubbed W takes H with it at the ratio the two had, and H takes W. */
    public void setLinked(boolean on) {
        linked.set(on);
    }

    /** A typed or scrubbed W or H, taking the other with it while the two are linked. */
    private void scaleTo(TransformGesture gesture, double percent, boolean width) {
        float next = (float) (percent / 100d);
        float sx = gesture.scaleX();
        float sy = gesture.scaleY();
        if (width) gesture.setScale(next, isLinked() && sx != 0f ? sy * next / sx : sy);
        else gesture.setScale(isLinked() && sy != 0f ? sx * next / sy : sx, next);
    }

    /**
     * Puts the pivot on one of the nine points of the box — -1, 0 or 1 on each axis — without moving the box.
     */
    public void placePivot(int column, int row) {
        pivot.set(new double[] {(column + 1) * 0.5d, (row + 1) * 0.5d});
    }

    /**
     * How far the element has been moved from where layout put it, across.
     *
     * <p>Measured at the CENTRE, and both halves of that matter: a corner swings when the box rotates in
     * place, which is not a move the element made, and the translate itself is rewritten whenever the
     * pivot is placed — to hold the box still — so reading that reported a move nobody asked for.</p>
     */
    private static double movedX(TransformGesture g) {
        return centre(g).x - g.width() * 0.5f;
    }

    /** @see #movedX */
    private static double movedY(TransformGesture g) {
        return centre(g).y - g.height() * 0.5f;
    }

    /** Moves the element until its centre is that far across from where layout put it. */
    private static void moveToX(TransformGesture g, double x) {
        g.nudgeTranslate((float) (x - movedX(g)), 0f);
    }

    /** @see #moveToX */
    private static void moveToY(TransformGesture g, double y) {
        g.nudgeTranslate(0f, (float) (y - movedY(g)));
    }

    /** Where the box's middle is drawn, in the element's own pixels. */
    private static Vector2f centre(TransformGesture g) {
        return g.apply(g.width() * 0.5f, g.height() * 0.5f);
    }

    /** A length as a fraction of the box, and the middle of a box with no size. */
    private static double fraction(float at, float extent) {
        return extent < 1e-4f ? 0.5d : at / (double) extent;
    }

    /** Two decimals, so a readout does not report float noise as a change while a handle is dragged. */
    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /** The field a number typed over the canvas goes into, for the grip last dragged. */
    public NumberControl fieldFor(TransformGesture.Kind kind) {
        return switch (kind) {
            case ROTATE -> angle;
            case SKEW -> leanX;
            case PIVOT -> pivotFractionX;
            case MOVE -> positionX;
            default -> scaleWidth;
        };
    }
}
