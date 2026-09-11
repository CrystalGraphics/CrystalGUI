package com.crystalgui.app.uibuilder.canvas.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

import org.joml.Vector2f;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * The numbers behind a Free Transform, live in both directions — the tool's page in the editor's
 * {@code ContextToolbar}, as Photoshop's options bar belongs to the transform while its box is up.
 *
 * <p>Everything the box can be dragged into can also be typed: the position of a chosen reference point,
 * the scale as a percentage beside the size it produces, the angle, the two skews and the pivot. A typed
 * value lands on Enter, Tab or a click away; until then the field keeps what is being typed and the others
 * go on following the box.</p>
 *
 * <h3>The reference widget decides what stays still</h3>
 *
 * <p>A drag knows which handle it grabbed, so it can hold the opposite edge. <b>A typed number knows
 * nothing</b> — scaling to 150% could hold any point of the box — so the 3×3 grid answers that question
 * and this class applies it: every edit records where the reference point was, makes the change, and puts
 * the translate back so that point has not moved. {@code TransformGesture} deliberately holds no anchor
 * rule of its own; two of them would disagree.</p>
 *
 * <p>Each field writes only its own quantity. The rest are shown rounded, and writing them back as well
 * would round the whole transform every time one number was typed.</p>
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

    private final List<Field> fields = new ArrayList<>();

    /** The fields a number typed over the canvas can land in. @see #fieldFor */
    private final Field positionX;
    private final Field scaleWidth;
    private final Field angle;
    private final Field leanX;
    private final Field pivotX;

    private final UIText size = new UIText();

    private final List<UIElement> anchorCells = new ArrayList<>();

    /** -1, 0 or 1 on each axis: which point of the box a typed number is measured against. */
    private int anchorX;

    private int anchorY;

    /**
     * One number on the bar: how it reads off the gesture, and how a typed value goes back.
     *
     * @param holdsReference false for X and Y, which ARE the reference point's position and so cannot also
     *                       be measured against it
     */
    private record Field(NumberControl control, ToDoubleFunction<TransformGesture> read,
                         ObjDoubleConsumer<TransformGesture> write, boolean holdsReference) {
    }

    public TransformOptionsBar(TransformBox box) {
        super(NAME);
        this.box = box;
        addClass(BAR_CLASS);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.ROW));

        appendStructural(buildAnchorGrid());
        positionX = field("X", "x", g -> movedReference(g).x, this::placeReferenceX, false);
        field("Y", "y", g -> movedReference(g).y, this::placeReferenceY, false);
        scaleWidth = field("W%", "w", g -> g.scaleX() * 100d,
                (g, v) -> g.setScale((float) (v / 100d), g.scaleY()), true);
        field("H%", "h", g -> g.scaleY() * 100d,
                (g, v) -> g.setScale(g.scaleX(), (float) (v / 100d)), true);
        size.addClass(SIZE_CLASS);
        appendStructural(size);
        angle = field("Angle", "angle", g -> Math.toDegrees(g.rotation()),
                (g, v) -> g.setRotation((float) Math.toRadians(v)), true);
        leanX = field("Skew X", "skewX", g -> Math.toDegrees(g.skewXRadians()),
                (g, v) -> g.setSkew((float) Math.toRadians(v), g.skewYRadians()), true);
        field("Skew Y", "skewY", g -> Math.toDegrees(g.skewYRadians()),
                (g, v) -> g.setSkew(g.skewXRadians(), (float) Math.toRadians(v)), true);
        pivotX = field("Pivot X", "pivotX", g -> percentOf(g.originX(), g.width()),
                (g, v) -> g.setOrigin((float) (v / 100d) * g.width(), g.originY()), true);
        field("Pivot Y", "pivotY", g -> percentOf(g.originY(), g.height()),
                (g, v) -> g.setOrigin(g.originX(), (float) (v / 100d) * g.height()), true);
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

    /** A labelled number, whose label also scrubs it as every other number in the application does. */
    private Field field(String caption, String id, ToDoubleFunction<TransformGesture> read,
                        ObjDoubleConsumer<TransformGesture> write, boolean holdsReference) {
        NumberControl control = new NumberControl(ConfigDescriptor.number(id, ""), 0d);
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

        Field field = new Field(control, read, write, holdsReference);
        control.changed.connect(ignored -> edited(field));
        fields.add(field);
        return field;
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
                cell.events.getGroup(MouseEvent.Down.class)
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
    private Vector2f referencePoint(TransformGesture gesture) {
        return new Vector2f(gesture.width() * (anchorX + 1) * 0.5f,
                gesture.height() * (anchorY + 1) * 0.5f);
    }

    /** How far the gesture has carried the reference point from where it sits untransformed. */
    private Vector2f movedReference(TransformGesture gesture) {
        Vector2f reference = referencePoint(gesture);
        return gesture.apply(reference.x, reference.y).sub(reference);
    }

    /** X states where the reference point ends up, so the translate absorbs whatever else moved it. */
    private void placeReferenceX(TransformGesture gesture, double x) {
        Vector2f reference = referencePoint(gesture);
        Vector2f now = gesture.apply(reference.x, reference.y);
        gesture.nudgeTranslate((float) (reference.x + x) - now.x, 0f);
    }

    /** @see #placeReferenceX */
    private void placeReferenceY(TransformGesture gesture, double y) {
        Vector2f reference = referencePoint(gesture);
        Vector2f now = gesture.apply(reference.x, reference.y);
        gesture.nudgeTranslate(0f, (float) (reference.y + y) - now.y);
    }

    /** Applies a typed or scrubbed value, holding the reference point where it was. */
    private void edited(Field field) {
        Double value = field.control().getValue();
        if (value == null || !box.isActive()) return;
        TransformGesture gesture = box.gesture();
        Vector2f reference = referencePoint(gesture);
        Vector2f before = gesture.apply(reference.x, reference.y);
        field.write().accept(gesture, value);
        if (field.holdsReference()) {
            Vector2f after = gesture.apply(reference.x, reference.y);
            gesture.nudgeTranslate(before.x - after.x, before.y - after.y);
        }
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
        size.setText(Math.round(gesture.width() * Math.abs(gesture.scaleX())) + " x "
                + Math.round(gesture.height() * Math.abs(gesture.scaleY())) + " px");
    }

    private static float percentOf(float value, float extent) {
        return extent < 1e-4f ? 50f : value / extent * 100f;
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
            case PIVOT -> pivotX;
            case MOVE -> positionX;
            default -> scaleWidth;
        };
        return field.control();
    }
}
