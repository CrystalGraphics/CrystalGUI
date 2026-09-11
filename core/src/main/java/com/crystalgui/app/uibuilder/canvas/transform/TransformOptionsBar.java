package com.crystalgui.app.uibuilder.canvas.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.ContextToolbar;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * The numbers behind a Free Transform, live in both directions — the tool's page in the editor's
 * {@code ContextToolbar}, as Photoshop's options bar belongs to the transform while its box is up.
 *
 * <p>Everything the box can be dragged into can also be typed: where the element has been moved to, where
 * the pivot sits, the scale, the rotation and the two skews. Dense the way Photoshop's and Paint.NET's
 * bars are — a letter per number, the unit inside the value, and the full name on hover, where W and H
 * also give the size the scale produces. A typed value lands on Enter, Tab or a click away; until then the
 * field keeps what is being typed and the others go on following the box.</p>
 *
 * <p>Every change made here is a step of the box's {@code history}, recorded the way every config host
 * records into its own — a typed number one step, a scrub one step as a whole — so Ctrl+Z steps it back
 * from the canvas or from the field a scrub left focused.</p>
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
 * box, and lights while the pivot sits there; dragged anywhere else, none does — and PX and PY say where
 * it went, as a percentage of the box, so the nine cells are the round numbers of that same pair. A typed
 * scale, rotation or skew holds the pivot still with no help, because {@code transform-origin} is the one
 * point every rotate, skew and scale leaves where it is.</p>
 *
 * <p>Each field writes only its own quantity — except W and H while the chain between them is on, when
 * either takes the other with it at the ratio the two had. The rest are shown rounded, and writing them
 * back as well would round the whole transform every time one number was typed.</p>
 */
public final class TransformOptionsBar extends UIElement {

    public static final Name NAME = Name.of("transformoptions");

    public static final String BAR_CLASS = "__transform-options__";

    /** One labelled control. */
    public static final String FIELD_CLASS = "__transform-field__";

    public static final String LABEL_CLASS = "__transform-label__";

    /** The 3x3 pivot grid, and one of its nine cells. */
    public static final String ANCHOR_CLASS = "__transform-anchor__";

    public static final String ANCHOR_CELL_CLASS = "__transform-anchor-cell__";

    /** The cell the pivot sits on, and the chain while it is on. */
    public static final String ANCHOR_ON_CLASS = "__on__";

    /** The chain between W and H. */
    public static final String LINK_CLASS = "__transform-link__";

    /** Neither an edge nor the middle. @see #anchorOf */
    private static final int BETWEEN = Integer.MIN_VALUE;

    /** What each cell does, row by row from the top left — nine targets too small to label. */
    private static final String[] ANCHOR_NAMES = {
            "Pivot: top left", "Pivot: top", "Pivot: top right",
            "Pivot: left", "Pivot: centre", "Pivot: right",
            "Pivot: bottom left", "Pivot: bottom", "Pivot: bottom right"};

    private final TransformBox box;

    private final List<Field> fields = new ArrayList<>();

    /** The fields a number typed over the canvas can land in. @see #fieldFor */
    private final Field positionX;
    private final Field pivotFractionX;
    private final Field scaleWidth;
    private final Field angle;
    private final Field leanX;

    /** Named apart because hovering it says the height the scale produces. */
    private final Field scaleHeight;

    /** The nine cells, row by row from the top left. */
    private final List<UIElement> pivotCells = new ArrayList<>();

    private final Button link = new Button();

    /** Whether W and H scale together. @see #setLinked */
    private boolean linked;

    /** One number on the bar: how it reads off the gesture, how a typed value goes back, and what
     * hovering it says. */
    private record Field(NumberControl control, Tooltip hint, String name,
                         ToDoubleFunction<TransformGesture> read, ObjDoubleConsumer<TransformGesture> write) {
    }

    public TransformOptionsBar(TransformBox box) {
        super(NAME);
        this.box = box;
        addClass(BAR_CLASS);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.ROW));

        // PHOTOSHOP'S GROUPS, with the pivot's own numbers beside the grid that picks them: what turns
        // it, where it has been moved to, how big, how turned, how leaned.
        appendStructural(buildPivotGrid());
        pivotFractionX = field("PX", "Pivot X", "%", g -> fraction(g.originX(), g.width()) * 100d,
                (g, v) -> g.setOrigin((float) (v / 100d) * g.width(), g.originY()));
        field("PY", "Pivot Y", "%", g -> fraction(g.originY(), g.height()) * 100d,
                (g, v) -> g.setOrigin(g.originX(), (float) (v / 100d) * g.height()));
        appendStructural(ContextToolbar.separator());
        positionX = field("X", "Position X", null, TransformOptionsBar::movedX,
                TransformOptionsBar::moveToX);
        field("Y", "Position Y", null, TransformOptionsBar::movedY, TransformOptionsBar::moveToY);
        appendStructural(ContextToolbar.separator());
        scaleWidth = field("W", "Width", "%", g -> g.scaleX() * 100d, (g, v) -> scaleTo(g, v, true));
        appendStructural(buildLink());
        scaleHeight = field("H", "Height", "%", g -> g.scaleY() * 100d, (g, v) -> scaleTo(g, v, false));
        appendStructural(ContextToolbar.separator());
        angle = field("R", "Rotation", "°", g -> Math.toDegrees(g.rotation()),
                (g, v) -> g.setRotation((float) Math.toRadians(v)));
        appendStructural(ContextToolbar.separator());
        leanX = field("SX", "Skew X", "°", g -> Math.toDegrees(g.skewXRadians()),
                (g, v) -> g.setSkew((float) Math.toRadians(v), g.skewYRadians()));
        field("SY", "Skew Y", "°", g -> Math.toDegrees(g.skewYRadians()),
                (g, v) -> g.setSkew(g.skewXRadians(), (float) Math.toRadians(v)));
    }

    /**
     * Follows the box every frame.
     *
     * <p>Not driven by the box: the gesture changes on a drag, on a numeric edit, on an undo step and on
     * open and close, and a bar that had to be told about each of those would eventually miss one. Owned
     * by this node, so it stops when the node leaves the tree.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            sync();
            return true;
        });
    }

    /** A letter and a number, whose letter also scrubs it as every other number in the application does. */
    private Field field(String letter, String name, @Nullable String unit,
                        ToDoubleFunction<TransformGesture> read, ObjDoubleConsumer<TransformGesture> write) {
        // ONE UNIT PER PIXEL, on every number here. Percentages, degrees and pixels each have a natural
        // scale, and the rate that follows a value's MAGNITUDE -- right for an unbounded number -- crawls
        // at exactly the zero all of these start from: 120 pixels of hand was worth 3.6% at one end of the
        // pivot and 36% at the other, which reads as the field sticking rather than as a speed.
        NumberControl control = new NumberControl(
                ConfigDescriptor.number(name, "").unit(unit).scrubRate(1d), 0d);
        UIElement group = new UIElement();
        group.addClass(FIELD_CLASS);
        StyleGroup.defaultPipeline(group.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW));
        UIText label = new UIText();
        label.setText(letter);
        label.addClass(LABEL_CLASS);
        group.append(label, control);
        control.scrubWith(label);
        appendStructural(group);

        Field field = new Field(control, Tooltip.attach(group, name), name, read, write);
        control.changed.connect(ignored -> edited(field));
        // A SCRUB IS ONE STEP, as it is for every config host: the control brackets the gesture, and the
        // history holds its merge run open across it.
        control.interacting.connect(active -> {
            if (Boolean.TRUE.equals(active)) box.history().beginMergeRun();
            else box.history().endMergeRun();
        });
        fields.add(field);
        return field;
    }

    private UIElement buildPivotGrid() {
        UIElement grid = new UIElement();
        grid.addClass(ANCHOR_CLASS);
        StyleGroup.defaultPipeline(grid.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        // ONE TOOLTIP, ON THE GRID, RELABELLED PER CELL. A tooltip hangs under the element it is attached
        // to, and a cell is four pixels tall in the middle of the row -- so nine of them sat that much
        // higher than every other tooltip on the bar, overlapping the toolbar instead of clearing it. The
        // grid is the full height of the row (see the sheet), so its own tooltip lands where the rest do.
        Tooltip hint = Tooltip.attach(grid, ANCHOR_NAMES[4]);
        for (int row = -1; row <= 1; row++) {
            UIElement line = new UIElement();
            StyleGroup.defaultPipeline(line.getStyle().getLayoutGroup(),
                    l -> l.flexDirection(FlexDirection.ROW));
            for (int column = -1; column <= 1; column++) {
                final int cellX = column;
                final int cellY = row;
                final String name = ANCHOR_NAMES[(row + 1) * 3 + (column + 1)];
                UIElement cell = new UIElement();
                cell.addClass(ANCHOR_CELL_CLASS);
                cell.onMouseEnter.attachListener((element, event) -> hint.setText(name), false, false);
                cell.events.getGroup(MouseEvent.Down.class)
                        .attachListener((element, event) -> {
                            placePivot(cellX, cellY);
                            event.stopPropagation();
                        }, false, false);
                pivotCells.add(cell);
                line.append(cell);
            }
            grid.append(line);
        }
        return grid;
    }

    /** Photoshop's chain between W and H. */
    private UIElement buildLink() {
        link.addClass(LINK_CLASS);
        link.onPressed.connect(() -> setLinked(!linked));
        Tooltip.attach(link, "Maintain aspect ratio");
        return link;
    }

    /** Whether W and H scale together. */
    public boolean isLinked() {
        return linked;
    }

    /** While linked, a typed or scrubbed W takes H with it at the ratio the two had, and H takes W. */
    public void setLinked(boolean on) {
        linked = on;
        link.toggleClass(ANCHOR_ON_CLASS, on);
    }

    /** A typed or scrubbed W or H, taking the other with it while the two are linked. */
    private void scaleTo(TransformGesture gesture, double percent, boolean width) {
        float next = (float) (percent / 100d);
        float sx = gesture.scaleX();
        float sy = gesture.scaleY();
        if (width) gesture.setScale(next, linked && sx != 0f ? sy * next / sx : sy);
        else gesture.setScale(linked && sy != 0f ? sx * next / sy : sx, next);
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

    /**
     * Puts the pivot on one of the nine points of the box — -1, 0 or 1 on each axis — without moving the box.
     */
    public void placePivot(int column, int row) {
        if (!box.isActive()) return;
        TransformGesture gesture = box.gesture();
        box.step(() -> gesture.setOrigin(gesture.width() * (column + 1) * 0.5f,
                gesture.height() * (row + 1) * 0.5f));
        box.preview();
        sync();
    }

    /** Applies a typed or scrubbed value as a step of the box's history. */
    private void edited(Field field) {
        Double value = field.control().getValue();
        if (value == null || !box.isActive()) return;
        box.step(() -> field.write().accept(box.gesture(), value));
        box.preview();
        sync();
    }

    /** Shows the box's numbers, leaving alone a field that is being typed into. */
    public void sync() {
        if (!box.isActive()) return;
        TransformGesture gesture = box.gesture();
        for (Field field : fields) {
            field.control().setLiveValue(round(field.read().applyAsDouble(gesture)));
        }
        markPivot(gesture);
        describeSize(scaleWidth, gesture.width() * Math.abs(gesture.scaleX()));
        describeSize(scaleHeight, gesture.height() * Math.abs(gesture.scaleY()));
    }

    /** Lights the cell the pivot sits on, and none while it is between them. */
    private void markPivot(TransformGesture gesture) {
        int column = anchorOf(gesture.originX(), gesture.width());
        int row = anchorOf(gesture.originY(), gesture.height());
        for (int i = 0; i < pivotCells.size(); i++) {
            boolean on = column != BETWEEN && row != BETWEEN && i == (row + 1) * 3 + (column + 1);
            UIElement cell = pivotCells.get(i);
            if (cell.hasClass(ANCHOR_ON_CLASS) != on) cell.toggleClass(ANCHOR_ON_CLASS, on);
        }
    }

    /** -1, 0 or 1 when {@code at} is an edge or the middle of {@code extent}, else {@link #BETWEEN}. */
    private static int anchorOf(float at, float extent) {
        if (extent < 1e-4f) return 0;
        double step = fraction(at, extent) * 2d;
        for (int anchor = -1; anchor <= 1; anchor++) {
            if (Math.abs(step - (anchor + 1)) < 2e-3d) return anchor;
        }
        return BETWEEN;
    }

    /** What W and H say on hover: the size the scale produces, which is what a percentage is OF. */
    private static void describeSize(Field field, float pixels) {
        String text = field.name() + ": " + Math.round(pixels) + " px";
        if (!text.equals(field.hint().getText())) field.hint().setText(text);
    }

    /** Two decimals, so a readout does not jitter through six digits while a handle is dragged. */
    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /** The field a number typed over the canvas goes into, for the grip last dragged. */
    public NumberControl fieldFor(TransformGesture.Kind kind) {
        Field field = switch (kind) {
            case ROTATE -> angle;
            case SKEW -> leanX;
            case PIVOT -> pivotFractionX;
            case MOVE -> positionX;
            default -> scaleWidth;
        };
        return field.control();
    }
}
