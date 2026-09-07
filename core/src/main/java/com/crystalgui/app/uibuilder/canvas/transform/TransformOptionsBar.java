package com.crystalgui.app.uibuilder.canvas.transform;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * The numbers behind a Free Transform, live in both directions.
 *
 * <p>Photoshop's options bar. Everything the box can be dragged into, it can also be typed: the position
 * of a chosen reference point, the scale as a percentage beside the size it produces, the angle, the two
 * skews and the pivot. It shows only while the box is up.</p>
 *
 * <h3>The reference widget decides what stays still</h3>
 *
 * <p>A drag knows which handle it grabbed, so it can hold the opposite edge. <b>A typed number knows
 * nothing</b> — scaling to 150% could hold any point of the box — so the 3×3 grid answers that question
 * and this class applies it: every edit records where the reference point was, makes the change, and puts
 * the translate back so that point has not moved. {@code TransformGesture} deliberately holds no anchor
 * rule of its own; two of them would disagree.</p>
 *
 * <h3>Writing back is guarded</h3>
 *
 * <p>A control fires {@code changed} whether a person or this class set it, so a sync would be read
 * straight back as an edit. {@code writing} is the latch that tells the two apart — the alternative,
 * comparing values for equality, silently drops a real edit that happens to round to what is displayed.
 * </p>
 */
public final class TransformOptionsBar extends UIElement {

    public static final Name NAME = Name.of("transformoptions");

    public static final String BAR_CLASS = "__transform-options__";

    /** One labelled control. */
    public static final String FIELD_CLASS = "__transform-field__";

    public static final String LABEL_CLASS = "__transform-label__";

    /** The 3x3 reference-point grid, and one of its nine cells. */
    public static final String ANCHOR_CLASS = "__transform-anchor__";

    public static final String ANCHOR_CELL_CLASS = "__transform-anchor-cell__";

    /** The chosen cell. */
    public static final String ANCHOR_ON_CLASS = "__on__";

    /** The read-only px readout beside the two percentages. */
    public static final String SIZE_CLASS = "__transform-size__";

    private final TransformBox box;

    private final NumberControl positionX = number("x");
    private final NumberControl positionY = number("y");
    private final NumberControl scaleWidth = number("w");
    private final NumberControl scaleHeight = number("h");
    private final NumberControl angle = number("angle");
    private final NumberControl leanX = number("skewX");
    private final NumberControl leanY = number("skewY");
    private final NumberControl pivotX = number("pivotX");
    private final NumberControl pivotY = number("pivotY");

    private final UIText size = new UIText();

    private final List<UIElement> anchorCells = new ArrayList<>();

    /** -1, 0 or 1 on each axis: which point of the box a typed number is measured against. */
    private int anchorX;

    private int anchorY;

    /** @see TransformOptionsBar the note on why this is a latch and not an equality check */
    private boolean writing;

    public TransformOptionsBar(TransformBox box) {
        super(NAME);
        this.box = box;
        addClass(BAR_CLASS);
        setDisplayed(false);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.ROW));

        appendStructural(buildAnchorGrid());
        field("X", positionX);
        field("Y", positionY);
        field("W%", scaleWidth);
        field("H%", scaleHeight);
        size.addClass(SIZE_CLASS);
        appendStructural(size);
        field("Angle", angle);
        field("Skew X", leanX);
        field("Skew Y", leanY);
        field("Pivot X", pivotX);
        field("Pivot Y", pivotY);

        positionX.changed.connect(ignored -> edited(false));
        positionY.changed.connect(ignored -> edited(false));
        scaleWidth.changed.connect(ignored -> edited(true));
        scaleHeight.changed.connect(ignored -> edited(true));
        angle.changed.connect(ignored -> edited(true));
        leanX.changed.connect(ignored -> edited(true));
        leanY.changed.connect(ignored -> edited(true));
        pivotX.changed.connect(ignored -> edited(true));
        pivotY.changed.connect(ignored -> edited(true));
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

    private static NumberControl number(String id) {
        return new NumberControl(ConfigDescriptor.number(id, ""), 0d);
    }

    /** A label that also scrubs its own control, as every other number in the application does. */
    private void field(String caption, NumberControl control) {
        UIElement group = new UIElement();
        group.addClass(FIELD_CLASS);
        StyleGroup.defaultPipeline(group.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW));
        UIText label = new UIText();
        label.setText(caption);
        label.addClass(LABEL_CLASS);
        group.append(label, control);
        control.scrubWith(label);
        appendStructural(group);
    }

    private UIElement buildAnchorGrid() {
        UIElement grid = new UIElement();
        grid.addClass(ANCHOR_CLASS);
        StyleGroup.defaultPipeline(grid.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        for (int row = -1; row <= 1; row++) {
            UIElement line = new UIElement();
            StyleGroup.defaultPipeline(line.getStyle().getLayoutGroup(),
                    l -> l.flexDirection(FlexDirection.ROW));
            for (int column = -1; column <= 1; column++) {
                final int cellX = column;
                final int cellY = row;
                UIElement cell = new UIElement();
                cell.addClass(ANCHOR_CELL_CLASS);
                cell.events.getGroup(com.crystalgui.ui.event.MouseEvent.Down.class)
                        .attachListener((element, event) -> {
                            setAnchor(cellX, cellY);
                            event.stopPropagation();
                        }, false, false);
                anchorCells.add(cell);
                line.append(cell);
            }
            grid.append(line);
        }
        markAnchor();
        return grid;
    }

    /** Which point of the box a typed number is measured against. Centre by default, as Photoshop is. */
    public void setAnchor(int x, int y) {
        anchorX = x;
        anchorY = y;
        markAnchor();
        sync();
    }

    private void markAnchor() {
        for (int i = 0; i < anchorCells.size(); i++) {
            boolean on = i == (anchorY + 1) * 3 + (anchorX + 1);
            UIElement cell = anchorCells.get(i);
            if (cell.hasClass(ANCHOR_ON_CLASS) != on) {
                if (on) cell.addClass(ANCHOR_ON_CLASS);
                else cell.removeClass(ANCHOR_ON_CLASS);
            }
        }
    }

    /** The reference point in the node's own pixels. */
    private Vector2f referencePoint() {
        TransformGesture gesture = box.gesture();
        return new Vector2f(gesture.width() * (anchorX + 1) * 0.5f,
                gesture.height() * (anchorY + 1) * 0.5f);
    }

    /**
     * Applies a typed value, holding the reference point where it was.
     *
     * @param holdReference false for X and Y, which ARE the reference point's position and so cannot also
     *                      be measured against it
     */
    private void edited(boolean holdReference) {
        if (writing || !box.isActive()) return;
        TransformGesture gesture = box.gesture();
        Vector2f reference = referencePoint();
        Vector2f before = gesture.apply(reference.x, reference.y);

        gesture.setScale((float) (scaleWidth.getValue() / 100d), (float) (scaleHeight.getValue() / 100d));
        gesture.setRotation((float) Math.toRadians(angle.getValue()));
        gesture.setSkew((float) Math.toRadians(leanX.getValue()),
                (float) Math.toRadians(leanY.getValue()));
        gesture.setOrigin((float) (pivotX.getValue() / 100d) * gesture.width(),
                (float) (pivotY.getValue() / 100d) * gesture.height());

        if (holdReference) {
            Vector2f after = gesture.apply(reference.x, reference.y);
            gesture.nudgeTranslate(before.x - after.x, before.y - after.y);
        } else {
            // X and Y state where the reference point should END UP, measured from where it starts with
            // no transform at all -- so the translate absorbs whatever the rest of the gesture did to it.
            Vector2f now = gesture.apply(reference.x, reference.y);
            gesture.nudgeTranslate(
                    (float) (reference.x + positionX.getValue()) - now.x,
                    (float) (reference.y + positionY.getValue()) - now.y);
        }
        box.preview();
        sync();
    }

    /** Shows the bar and its numbers, or hides it. Called every frame by the box. */
    public void sync() {
        boolean wanted = box.isActive();
        if (isDisplayed() != wanted) setDisplayed(wanted);
        if (!wanted) return;

        TransformGesture gesture = box.gesture();
        Vector2f reference = referencePoint();
        Vector2f moved = gesture.apply(reference.x, reference.y);
        writing = true;
        try {
            positionX.setValue(round(moved.x - reference.x));
            positionY.setValue(round(moved.y - reference.y));
            scaleWidth.setValue(round(gesture.scaleX() * 100f));
            scaleHeight.setValue(round(gesture.scaleY() * 100f));
            angle.setValue(round((float) Math.toDegrees(gesture.rotation())));
            leanX.setValue(round((float) Math.toDegrees(gesture.skewXRadians())));
            leanY.setValue(round((float) Math.toDegrees(gesture.skewYRadians())));
            pivotX.setValue(round(percentOf(gesture.originX(), gesture.width())));
            pivotY.setValue(round(percentOf(gesture.originY(), gesture.height())));
            size.setText(Math.round(gesture.width() * Math.abs(gesture.scaleX())) + " x "
                    + Math.round(gesture.height() * Math.abs(gesture.scaleY())) + " px");
        } finally {
            writing = false;
        }
    }

    private static float percentOf(float value, float extent) {
        return extent < 1e-4f ? 50f : value / extent * 100f;
    }

    /** Two decimals, so a readout does not jitter through six digits while a handle is dragged. */
    private static double round(float value) {
        return Math.round(value * 100f) / 100d;
    }

    /** The field a mid-gesture number goes into, for the grip being dragged. */
    public NumberControl fieldFor(TransformGesture.Kind kind) {
        return switch (kind) {
            case ROTATE -> angle;
            case SKEW -> leanX;
            case PIVOT -> pivotX;
            case MOVE -> positionX;
            default -> scaleWidth;
        };
    }
}
