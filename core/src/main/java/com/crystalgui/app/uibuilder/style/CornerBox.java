package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.control.Button;

/**
 * Four corner radii as a box with a handle on each corner's curve, where it crosses the corner's diagonal — dragged
 * inward to round it, and staying on the curve it draws.
 *
 * <pre>{@code
 * Property<Boolean> linked = Property.of(true), elliptical = Property.of(false);
 * lab.content().append(new CornerBox("corners", linked, elliptical).bind(radii));
 * // radii: [tlX, tlY, trX, trY, brX, brY, blX, blY] in px
 * }</pre>
 *
 * <p><b>A miniature of the element</b>, once {@link #subject} names it: the box takes the element's proportions within
 * {@link #MAX_WIDTH}x{@link #MAX_HEIGHT} and draws its radii at the same scale, so a pill on the element is a pill here
 * and each handle sits on the curve the element actually has.</p>
 *
 * <ul>
 *   <li><b>Circular</b>, a drag diagonally inward rounds the corner; <b>elliptical</b>, across sets its horizontal
 *       radius and down its vertical one.</li>
 *   <li>While {@code linked} holds every corner moves together; <b>Alt</b> moves the one dragged alone.</li>
 *   <li><b>Right-click</b> a handle to square its corner, or every corner while linked.</li>
 *   <li>The chain in the middle toggles {@code linked}.</li>
 * </ul>
 */
public final class CornerBox extends ValueControl<double[]> {

    public static final Name NAME = Name.of("cornerbox");

    public static final String BOX_CLASS = "__corner-box__";
    public static final String PUCK_CLASS = "__corner-puck__";
    /** A side's handle, at the inner edge of its border. */
    public static final String SIDE_CLASS = "__side-puck__";
    public static final String LINK_CLASS = "__corner-link__";
    public static final String ACTIVE_CLASS = "__active__";

    /** Top-left, top-right, bottom-right, bottom-left — the order CSS states corners in. */
    static final StyleProperty<?>[] X = {
            BorderRadiusProperties.TOP_LEFT_X, BorderRadiusProperties.TOP_RIGHT_X,
            BorderRadiusProperties.BOTTOM_RIGHT_X, BorderRadiusProperties.BOTTOM_LEFT_X};

    static final StyleProperty<?>[] Y = {
            BorderRadiusProperties.TOP_LEFT_Y, BorderRadiusProperties.TOP_RIGHT_Y,
            BorderRadiusProperties.BOTTOM_RIGHT_Y, BorderRadiusProperties.BOTTOM_LEFT_Y};

    private static final String[] CORNERS = {"top-left", "top-right", "bottom-right", "bottom-left"};
    private static final String[] SIDES = {"top", "right", "bottom", "left"};

    /**
     * Where an arc of radius r crosses its corner's diagonal, as a share of r in from each edge: {@code 1 - 1/sqrt 2}.
     * At r alone the handle sat at the curve's centre, well inside the shape it rounds.
     */
    private static final float ON_CURVE = (float) (1d - 1d / Math.sqrt(2d));

    /** The largest the miniature is drawn; the element's proportions decide which side reaches its limit. */
    static final float MAX_WIDTH = 168f;
    static final float MAX_HEIGHT = 100f;

    /** The shortest a side of the miniature gets, so a hairline element still has corners to hold. */
    private static final float MIN_SIDE = 28f;

    private final Property<Boolean> linked;
    private final Property<Boolean> elliptical;
    private final UIElement[] pucks = new UIElement[4];

    /** The element the radii belong to, or null to draw them at their own size in the sheet's box. */
    @Nullable
    private UIElement subject;

    /** Miniature pixels per element pixel. */
    private double scale = 1d;

    /** The border widths the side handles edit, top, right, bottom, left, or null without {@link #sides}. */
    @Nullable
    private Property<double[]> widths;
    private final UIElement[] sidePucks = new UIElement[4];

    public CornerBox(String id, Property<Boolean> linked, Property<Boolean> elliptical) {
        super(NAME, ConfigDescriptor.vector(id, "", 4), new double[8]);
        this.linked = linked;
        this.elliptical = elliptical;
        addClass(BOX_CLASS);
        for (int corner = 0; corner < 4; corner++) {
            pucks[corner] = puck(corner);
            append(pucks[corner]);
        }

        Button link = new Button("");
        link.addClass(LINK_CLASS);
        link.attachListener(() -> linked.set(!Boolean.TRUE.equals(linked.get())));
        PropertyWatch.follow(link, linked, on -> link.toggleClass(ACTIVE_CLASS, Boolean.TRUE.equals(on)));
        append(link);
        // PLACED ONCE THERE IS A SIZE: the first value arrives before the lab is attached, when there is no box and no
        // document to wait on, and every handle sat stacked in the middle.
        onConnected(this::placeWhenLaidOut);
    }

    private UIElement puck(int corner) {
        UIElement puck = new UIElement();
        puck.addClass(PUCK_CLASS);
        puck.addClass("__" + CORNERS[corner] + "__");
        // INWARD, per axis: a left corner rounds as the handle moves right, a top one as it moves down.
        float sx = corner == 0 || corner == 3 ? 1f : -1f;
        float sy = corner <= 1 ? 1f : -1f;
        double[] from = new double[8];
        boolean[] alone = new boolean[1];
        StyleGizmos.drag(puck, () -> {
            System.arraycopy(radii(), 0, from, 0, 8);
            alone[0] = !Boolean.TRUE.equals(linked.get()) || CgModifiers.hasAlt(modifiers());
            // FROM WHAT SHOWS: a radius typed past the cap starts the drag at the cap, not a pixel of travel per unit
            // of the invisible excess.
            for (int i = 0; i < 4; i++) {
                from[i * 2] = Math.min(from[i * 2], cap(alone[0], true));
                from[i * 2 + 1] = Math.min(from[i * 2 + 1], cap(alone[0], false));
            }
            beginInteraction();
        }, (dx, dy) -> {
            double x, y;
            // OVER ON_CURVE, so the handle stays under the pointer: it moves that share of what the radius does. And
            // over the scale, since the pointer moves in the miniature and the radius is the element's.
            double per = ON_CURVE * scale;
            if (Boolean.TRUE.equals(elliptical.get())) {
                x = Math.max(0d, Math.round(from[corner * 2] + sx * dx / per));
                y = Math.max(0d, Math.round(from[corner * 2 + 1] + sy * dy / per));
            } else {
                // DIAGONALLY INWARD, the average of the two axes: the handle rides the corner's own diagonal.
                x = y = Math.max(0d, Math.round(from[corner * 2] + (sx * dx + sy * dy) / 2f / per));
            }
            // NEVER PAST WHAT SHOWS, which is where CSS stops scaling the curve any further.
            if (Boolean.TRUE.equals(elliptical.get())) {
                x = Math.min(x, cap(alone[0], true));
                y = Math.min(y, cap(alone[0], false));
            } else {
                x = y = Math.min(x, Math.min(cap(alone[0], true), cap(alone[0], false)));
            }
            double[] next = radii().clone();
            for (int i = 0; i < 4; i++) {
                if (alone[0] && i != corner) continue;
                next[i * 2] = x;
                next[i * 2 + 1] = y;
            }
            commitAndShow(next);
        }, this::endInteraction);

        puck.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            double[] next = radii().clone();
            boolean all = Boolean.TRUE.equals(linked.get());
            for (int i = 0; i < 4; i++) {
                if (all || i == corner) {
                    next[i * 2] = 0d;
                    next[i * 2 + 1] = 0d;
                }
            }
            commitAndShow(next);
            event.preventDefault();
        }, false, true);
        return puck;
    }

    /**
     * The largest radius on one axis that still changes the subject, in its px: half its side. CSS lets a lone corner
     * reach the whole side, but the rounded-rect shader resolves each corner in its own quadrant and so draws no radius
     * past half the box -- and a drag past what is drawn moves a handle off the curve. Unbounded with no subject.
     */
    private double cap(boolean alone, boolean horizontal) {
        if (subject == null || subject.box() == null) return Double.MAX_VALUE;
        float side = horizontal ? subject.box().width() : subject.box().height();
        if (Boolean.FALSE.equals(elliptical.get())) side = Math.min(subject.box().width(), subject.box().height());
        return side <= 0f ? Double.MAX_VALUE : Math.ceil(side / 2f);
    }

    /**
     * A handle at each side's inner border edge, dragged inward to thicken that side: every side while {@code linked}
     * holds, the one dragged alone otherwise or with Alt. Right-click takes the side's border off.
     *
     * @param widths top, right, bottom, left, in the subject's px
     */
    public CornerBox sides(Property<double[]> widths, Property<Boolean> linked) {
        this.widths = widths;
        for (int side = 0; side < 4; side++) {
            sidePucks[side] = sidePuck(side, widths, linked);
            append(sidePucks[side]);
        }
        PropertyWatch.follow(this, widths, w -> placePucks(radii()));
        return this;
    }

    private UIElement sidePuck(int side, Property<double[]> widths, Property<Boolean> linked) {
        UIElement puck = new UIElement();
        puck.addClass(SIDE_CLASS);
        puck.addClass("__" + SIDES[side] + "__");
        double[] from = new double[4];
        boolean[] alone = new boolean[1];
        StyleGizmos.drag(puck, () -> {
            System.arraycopy(sideWidths(), 0, from, 0, 4);
            alone[0] = !Boolean.TRUE.equals(linked.get()) || CgModifiers.hasAlt(modifiers());
            beginInteraction();
        }, (dx, dy) -> {
            // INWARD IS THICKER, and the handle sits at the inner edge, so it moves one for one with the width.
            float inward = switch (side) {
                case 0 -> dy;
                case 1 -> -dx;
                case 2 -> -dy;
                default -> dx;
            };
            double cap = subject == null || subject.box() == null ? Double.MAX_VALUE
                    : Math.ceil(Math.min(subject.box().width(), subject.box().height()) / 2f);
            double width = Math.max(0d, Math.min(cap, Math.round(Math.min(from[side], cap) + inward / scale)));
            double[] next = from.clone();
            for (int i = 0; i < 4; i++) {
                if (alone[0] && i != side) continue;
                next[i] = width;
            }
            widths.set(next);
        }, this::endInteraction);
        puck.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            double[] next = sideWidths().clone();
            boolean all = Boolean.TRUE.equals(linked.get());
            for (int i = 0; i < 4; i++) {
                if (all || i == side) next[i] = 0d;
            }
            widths.set(next);
            event.preventDefault();
        }, false, true);
        return puck;
    }

    private double[] sideWidths() {
        double[] now = widths == null ? null : widths.get();
        return now == null || now.length < 4 ? new double[4] : now;
    }

    /**
     * The subject's border and outline on the miniature, at its scale and in its colors -- read from its computed style,
     * so it is what the element draws whichever target or spelling set it. No border leaves the sheet's own frame.
     */
    private void paintEdge() {
        BorderSample.Edge edge = BorderSample.Edge.of(subject);
        if (edge == null) return;
        StyleFields.Group sides = StyleFields.group(StyleFields.BORDER_WIDTH);
        StyleFields.Group offsets = StyleFields.group(StyleFields.OUTLINE_OFFSET);
        boolean bordered = edge.bordered();
        for (int i = 0; i < 4; i++) {
            StyleProperty<?> width = StyleFields.propertyOf(sides.longhands().get(i));
            if (bordered) LiveEdits.setInline(this, width, scaled(edge.borders()[i]));
            else LiveEdits.clearInline(this, width);
            LiveEdits.setInline(this, StyleFields.propertyOf(offsets.longhands().get(i)), scaled(edge.offsets()[i]));
        }
        paintColor(StylePropertyRegistry.BORDER_COLOR, bordered, edge.borderColor());
        paintColor(StylePropertyRegistry.BORDER_TOP_COLOR, bordered, edge.topColor());
        paintColor(StylePropertyRegistry.BORDER_BOTTOM_COLOR, bordered, edge.bottomColor());
        LiveEdits.setInline(this, StylePropertyRegistry.OUTLINE_WIDTH, scaled(edge.outlineWidth()));
        LiveEdits.setInline(this, StylePropertyRegistry.OUTLINE_COLOR, CssValues.color(edge.outlineColor()));
        // THE HANDLES SIT ON THE BORDER'S EDGES, which move with it.
        placeWhenLaidOut();
    }

    private void paintColor(StyleProperty<Integer> property, boolean shown, int argb) {
        if (shown) LiveEdits.setInline(this, property, CssValues.color(argb));
        else LiveEdits.clearInline(this, property);
    }

    private String scaled(float px) {
        return CssValues.px(Math.round(px * scale * 10d) / 10d);
    }

    /** Draws the radii as they are on {@code node}: its proportions, at its scale. */
    public CornerBox subject(@Nullable UIElement node) {
        this.subject = node;
        writeToWidgets(getValue());
        PropertyWatch.follow(this, Property.derived(() -> BorderSample.Edge.signature(node)), signature -> paintEdge());
        return this;
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    private double[] radii() {
        double[] now = getValue();
        return now == null || now.length < 8 ? new double[8] : now;
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        double[] radii = value == null || value.length < 8 ? new double[8] : value;
        fitToSubject();
        for (int i = 0; i < 4; i++) {
            LiveEdits.setInline(this, X[i], CssValues.px(Math.round(radii[i * 2] * scale * 10d) / 10d));
            LiveEdits.setInline(this, Y[i], CssValues.px(Math.round(radii[i * 2 + 1] * scale * 10d) / 10d));
        }
        placePucks(radii);
    }

    /**
     * Each handle on its corner's curve -- on the corner itself when it is square -- with the radii scaled and cut to
     * the box as the box itself draws them. Placed once the box has a size, which it does not on the first write.
     */
    private void placePucks(double[] element) {
        if (box() == null) {
            placeWhenLaidOut();
            return;
        }
        double[] radii = new double[8];
        for (int i = 0; i < 8; i++) radii[i] = element[i] * scale;
        // SCALED AS THE BOX DRAWS THEM: every radius by the one factor that makes the largest side fit. @see BoxPainter
        float width = box().width(), height = box().height();
        double f = Math.min(1d, Math.min(fit(width, radii[0] + radii[2]), fit(width, radii[6] + radii[4])));
        f = Math.min(f, Math.min(fit(height, radii[1] + radii[7]), fit(height, radii[3] + radii[5])));
        // FROM THE PADDING EDGE, which is where an offset is measured, while the curve is the border box's.
        var border = box().border();
        for (int corner = 0; corner < 4; corner++) {
            float bx = corner == 0 || corner == 3 ? border.left : border.right;
            float by = corner <= 1 ? border.top : border.bottom;
            // AND CUT AT HALF THE BOX PER AXIS, as the shader draws it. @see #cap
            double rx = Math.min(width / 2f, radii[corner * 2] * f);
            double ry = Math.min(height / 2f, radii[corner * 2 + 1] * f);
            String x = CssValues.px(Math.round((rx * ON_CURVE - bx) * 10d) / 10d);
            String y = CssValues.px(Math.round((ry * ON_CURVE - by) * 10d) / 10d);
            UIElement puck = pucks[corner];
            LiveEdits.setInline(puck, corner == 0 || corner == 3 ? LayoutProperties.LEFT : LayoutProperties.RIGHT, x);
            LiveEdits.setInline(puck, corner <= 1 ? LayoutProperties.TOP : LayoutProperties.BOTTOM, y);
        }
        if (widths == null) return;
        // EACH SIDE'S HANDLE AT ITS BORDER'S INNER EDGE, which is the padding edge an offset is measured from: 0 there.
        double[] w = sideWidths();
        float[] edge = {border.top, border.right, border.bottom, border.left};
        for (int side = 0; side < 4; side++) {
            String inset = CssValues.px(Math.round((w[side] * scale - edge[side]) * 10d) / 10d);
            LiveEdits.setInline(sidePucks[side], switch (side) {
                case 0 -> LayoutProperties.TOP;
                case 1 -> LayoutProperties.RIGHT;
                case 2 -> LayoutProperties.BOTTOM;
                default -> LayoutProperties.LEFT;
            }, inset);
        }
    }

    /**
     * The miniature's size and scale from the subject's: its longer proportion reaches {@link #MAX_WIDTH} or
     * {@link #MAX_HEIGHT}, whichever it meets first. Left to the sheet while the subject has no size.
     */
    private void fitToSubject() {
        if (subject == null || subject.box() == null || subject.box().width() <= 0f || subject.box().height() <= 0f) {
            scale = 1d;
            return;
        }
        float w = subject.box().width(), h = subject.box().height();
        double s = Math.min(MAX_WIDTH / w, MAX_HEIGHT / h);
        float width = (float) Math.max(MIN_SIDE, w * s), height = (float) Math.max(MIN_SIDE, h * s);
        scale = width / w;
        LiveEdits.setInline(this, LayoutProperties.WIDTH, CssValues.px(Math.round(width)));
        LiveEdits.setInline(this, LayoutProperties.HEIGHT, CssValues.px(Math.round(height)));
    }

    private void placeWhenLaidOut() {
        UIDocument window = document();
        if (window == null) return;
        window.animation().afterLayout(this, delta -> {
            if (box() == null) return true;
            placePucks(radii());
            return false;
        });
    }

    private static double fit(float length, double sum) {
        return sum > length ? length / sum : 1d;
    }

    private static int modifiers() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }
}
