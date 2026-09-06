package com.crystalgui.widget.composite;

import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.TaffyPosition;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A radar chart — several axes fanned from a centre, each reaching its own value.
 *
 * <h3>Geometry ported from Chart.js</h3>
 *
 * <p>The angles, the value-to-radius mapping and the label placement are Chart.js's
 * {@code RadialLinearScale} ({@code src/scales/scale.radialLinear.js}, MIT). They are the
 * conventional answers and each is easy to get subtly wrong:</p>
 *
 * <ul>
 *   <li><b>Axis 0 points UP.</b> {@code getPointPosition} subtracts a quarter turn from the index
 *       angle, so the first axis is at twelve o'clock and the rest run clockwise. Without it the
 *       first axis points right, which no radar chart anywhere does.</li>
 *   <li><b>A label's alignment comes from its angle</b>, not from its position: at the top and bottom
 *       it centres, on the right it is left-aligned and on the left right-aligned, so every label
 *       grows AWAY from the chart. {@code getTextAlignForAngle} and {@code yForAngle} are ported
 *       whole, including their exact boundary cases at 0/90/180/270 — those four are where a
 *       hand-rolled version puts a label a few pixels into the polygon it labels.</li>
 *   <li><b>The plot shrinks to fit its labels.</b> Chart.js measures them and reduces the drawing
 *       area; the version here reserves the widest label horizontally and the tallest vertically,
 *       which is the same idea with one measurement instead of a per-angle fit.</li>
 * </ul>
 *
 * <h3>Wedges, not one polygon</h3>
 *
 * <p>The fill is one triangle per axis pair, fanned from the centre and taking the axis's own colour,
 * rather than a single polygon in one colour. That is this chart's own design rather than Chart.js's,
 * and it is what makes a six-attribute sheet readable at a glance: each attribute owns a wedge.</p>
 *
 * <p>Each wedge marks {@link CgVectorRenderer#EDGE_P1_P2} as its silhouette — the rim — so only the
 * outer edge is antialiased and the two spokes into the centre stay hard. Feathering all three would
 * put a soft seam between every neighbouring pair, which reads as a hairline crack through the fan.</p>
 *
 * <h3>What is CSS and what is data</h3>
 *
 * <h3>Why this is a composite rather than a display widget</h3>
 *
 * <p>It composes a {@link Tooltip}, which lives one tier up — the same reason {@code SearchField} sits
 * here rather than beside the controls it looks like one of. A widget's tier is decided by what it
 * COMPOSES, not by what it is.</p>
 *
 * <p>The web (the rings and the spokes) takes the element's computed {@code color}, so a theme draws
 * it. Each axis's colour is DATA and is written inline onto that axis's label: a registry may declare
 * an attribute in any hue, so no stylesheet can enumerate them. Everything else — the label font, the
 * chart's size, its padding — is ordinary CSS on {@code radarchart} and {@code ::part(axis-label)}.</p>
 */
public class RadarChart extends UIElement {

    public static final Name NAME = Name.of("radarchart");

    /** Every axis label, so a theme can size and weight them together. */
    public static final String AXIS_LABEL_PART = "axis-label";

    /**
     * Carries the web's stroke thickness as its own HEIGHT, and draws nothing.
     *
     * <p>A part rather than {@code border-width}, which is the obvious spelling and was tried: the
     * element paints no background, but a border box is drawn regardless, so the chart came out inside
     * a rectangle. The web is not the element's border and cannot borrow its properties. Reading a
     * value back off a styled part is the engine's own idiom for this — {@code NodePort} takes a
     * wire's colour from its dot's computed border-colour for the same reason.</p>
     *
     * <p>Out of flow and zero-width, so it costs no layout.</p>
     */
    public static final String WEB_PART = "web";

    /**
     * Carries the wedge fill's strength as the ALPHA of its background colour, and draws nothing.
     *
     * <p>How strongly a chart fills is a property of the CHART, not of the data: an axis supplies a
     * hue and the theme decides how much of it to lay down. Encoding it in the caller's colours — the
     * first arrangement here — put the decision in whatever built the axis list, so every caller had
     * to remember it and no theme could change it.</p>
     *
     * <p>The alpha of a colour rather than {@code opacity}, which is the obvious spelling: an element
     * with {@code opacity < 1} is composited through a layer FBO, so a part that exists only to carry
     * a number would allocate a render target for a zero-sized box.</p>
     */
    public static final String FILL_PART = "fill";

    /**
     * The marker sitting on each value vertex, in that axis's colour.
     *
     * <p>A real element rather than something {@code paintContent} draws, and the reason is the hover:
     * hit testing works on BOXES, so a dot painted into the content has nothing to hover. As an element
     * it gets its size and its round shape from CSS, its colour inline from the data, and it can carry
     * a {@link Tooltip} — which is what a point is for. Square in the sheet, so a 50% radius is a circle.</p>
     */
    public static final String POINT_PART = "point";

    /**
     * One spoke.
     *
     * @param label what it is called
     * @param value how far out it reaches, against the chart's maximum
     * @param argb   the axis's colour. Its ALPHA IS IGNORED — the rim is drawn opaque and the fill
     *               takes its strength from {@code ::part(fill)}, so a plain RGB palette works as-is
     * @param detail what hovering this axis's point says, or {@code null} for no tooltip. The CALLER's
     *               words: a value's formatting is its owner's business, and a chart that invented one
     *               would be choosing a precision and a separator for data it knows nothing about
     */
    public record Axis(String label, double value, int argb, @Nullable String detail) {

        /** An axis with no hover text — its point still draws. */
        public Axis(String label, double value, int argb) {
            this(label, value, argb, null);
        }
    }

    /** How a wedge's fill is coloured between the two axes that bound it. */
    public enum AxisGradient {

        /** One flat colour per wedge — the axis it starts at. */
        NONE,

        /**
         * Each wedge ramps from its own axis's colour to the NEXT one's, across the wedge.
         *
         * <p>Which makes the colour belong to the SPOKE rather than to the area beside it: every axis
         * is its own hue along its own line, and the fill between two of them is the blend. A wedge
         * chart otherwise reads as six flat panels that happen to meet, and the eye has to be told
         * which panel goes with which label; here the label's colour runs all the way down its own
         * edge and there is nothing to look up.</p>
         */
        BETWEEN_AXES,

        /**
         * Each wedge fades from its own colour at the rim to nothing at the centre.
         *
         * <p>Answers a different problem: every wedge converges on one point in the middle, and six
         * saturated colours meeting there is the pinwheel a wedge chart is always about to become.
         * The colour ends up where the datum is.</p>
         */
        TOWARD_CENTRE
    }

    /** Below three there is no polygon to draw, and two axes are a line. */
    private static final int MIN_AXES = 3;

    private final ShadowRoot shadow;
    private final UIElement web;
    private final UIElement fill;
    private final List<Axis> axes = new ArrayList<>();
    private final List<UIText> labels = new ArrayList<>();
    private final List<UIElement> points = new ArrayList<>();

    /** {@code 0} means "the largest value present", which is what a sheet of attributes wants. */
    private double explicitMax;
    private int rings = 4;
    private AxisGradient axisGradient = AxisGradient.NONE;
    /** What the labels were last placed against, so the hook below costs two comparisons a frame. */
    private float placedWidth = -1f;
    private float placedHeight = -1f;

    public RadarChart() {
        super(NAME);
        // Its structure is its own and a caller has no content to put in it.
        refusePublicChildren();
        this.shadow = attachShadow();
        this.web = new UIElement();
        this.web.set(Attribute.PART, WEB_PART);
        this.web.setHitTest(false);
        StyleGroup.defaultPipeline(this.web.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
        shadow.append(this.web);
        this.fill = new UIElement();
        this.fill.set(Attribute.PART, FILL_PART);
        this.fill.setHitTest(false);
        StyleGroup.defaultPipeline(this.fill.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
        shadow.append(this.fill);
        // NO setHitTest(false) ON THE ROOT, although a readout otherwise wants one: it applies to the
        // whole SUBTREE, like `pointer-events: none`, so it would make the points unhoverable and the
        // tooltips dead. The labels and the two carrier parts each refuse the pointer individually
        // instead, which leaves the points as the only thing in here that can be hit.
    }

    /** Replaces the axes, rebuilding one label each. */
    public RadarChart setAxes(Collection<Axis> newAxes) {
        axes.clear();
        // Forces the hook's next pass to place them: the box has not changed, but its contents have.
        placedWidth = -1f;
        placedHeight = -1f;
        for (UIText label : labels) shadow.remove(label);
        labels.clear();
        for (UIElement point : points) shadow.remove(point);
        points.clear();

        for (Axis axis : newAxes) {
            axes.add(axis);
            UIText label = new UIText(axis.label());
            label.set(Attribute.PART, AXIS_LABEL_PART);
            // DATA, and the one legitimate inline colour write: an axis's hue comes from whatever
            // registry declared it, so no sheet can name them all.
            // FULL STRENGTH. The alpha on an axis belongs to its FILL; a label carrying it would fade
            // with the wedge, and a label is read rather than looked through.
            StyleGroup.inlinePipeline(label.getStyle().getGeneralGroup(),
                    g -> g.color(axis.argb() | 0xFF000000));
            // Decoration on the chart, not a thing in its own right.
            label.setHitTest(false);
            // OUT OF FLOW. Placed by their angle after layout, so they must not lay out in a row.
            StyleGroup.defaultPipeline(label.getStyle().getLayoutGroup(), l -> l.positionType(TaffyPosition.ABSOLUTE));
            labels.add(label);
            shadow.append(label);

            // AFTER the label, so painter's order puts the marker over the fill it sits on. Its colour
            // is the axis's at full strength -- the fill is washed and a marker washed with it would
            // disappear into its own wedge.
            UIElement point = new UIElement();
            point.set(Attribute.PART, POINT_PART);
            StyleGroup.inlinePipeline(point.getStyle().getGeneralGroup(),
                    g -> g.backgroundColor(axis.argb() | 0xFF000000));
            StyleGroup.defaultPipeline(point.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            // No delay asked for: a point is a target the pointer was AIMED at, so the engine's
            // instant default is already right and stating it again would just be noise.
            if (axis.detail() != null) Tooltip.attach(point, axis.detail());
            points.add(point);
            shadow.append(point);
        }
        placeLabelsAfterLayout();
        return this;
    }

    public List<Axis> axes() {
        return List.copyOf(axes);
    }

    /** The value the outer ring stands for. {@code 0} restores "the largest value present". */
    public RadarChart setMax(double max) {
        this.explicitMax = Math.max(0d, max);
        return this;
    }

    /**
     * How a wedge is coloured between the two axes bounding it. {@link AxisGradient#NONE} by default.
     *
     * <p>No new number either way. A ramp's ends are the same {@code ::part(fill)} strength a flat
     * fill uses, so a theme still has exactly one dial — and both ends of a fade share their RGB and
     * differ only in alpha, which is what keeps it from passing through the muddy half-colour a lerp
     * toward transparent BLACK gives.</p>
     */
    public RadarChart setAxisGradient(AxisGradient gradient) {
        this.axisGradient = gradient == null ? AxisGradient.NONE : gradient;
        return this;
    }

    /** How many rings the web draws, the outermost included. Fewer than one draws no web. */
    public RadarChart setRings(int rings) {
        this.rings = Math.max(0, rings);
        return this;
    }

    /**
     * Chart.js's {@code getDistanceFromCenterForValue}, with a floor of zero: a value below the
     * minimum plots at the centre rather than outside the chart on the opposite spoke.
     */
    private float extent(int index) {
        double max = maxValue();
        if (max <= 0d) return 0f;
        return (float) Math.max(0d, Math.min(1d, axes.get(index).value() / max));
    }

    private double maxValue() {
        if (explicitMax > 0d) return explicitMax;
        double max = 0d;
        for (Axis axis : axes) max = Math.max(max, axis.value());
        return max;
    }

    /**
     * The web's stroke thickness, read off the {@link #WEB_PART}'s laid-out height.
     *
     * <p>One pixel until that part has been measured, which is only ever the first frame.</p>
     */
    private float lineWidth() {
        Box laid = web.box();
        return laid == null ? 1f : Math.max(0.5f, laid.height());
    }

    /** Chart.js's {@code getIndexAngle} less a quarter turn, so axis 0 is at twelve o'clock. */
    private double angleOf(int index) {
        return index * (Math.PI * 2d / axes.size()) - Math.PI / 2d;
    }

    // ── Painting ────────────────────────────────────────────────────────────────────────────────

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        if (axes.size() < MIN_AXES) return;
        float radius = radiusIn(box);
        if (radius <= 0f) return;

        float cx = box.width() / 2f;
        float cy = box.height() / 2f;
        // THE WEB IS A SET OF BORDERS, so it takes the border properties rather than inventing its
        // own. border-width also feeds Taffy, which is harmless here: box-sizing is border-box
        // engine-wide, so it eats into the content rather than growing the chart, and the element
        // paints no background so no border box is ever drawn over the plot.
        int webColor = computedStyle().get(StylePropertyRegistry.BORDER_COLOR);
        float line = lineWidth();

        // WEB FIRST. It is the reference the values are read against, so it belongs BEHIND them --
        // drawn last it lays a grid over the data and the chart reads as a wireframe with colour
        // trapped inside it.
        paintWeb(ctx, cx, cy, radius, webColor, line);
        paintWedges(ctx, cx, cy, radius);
        paintRim(ctx, cx, cy, radius, line);
        // ONE flush for both paths. curve() and triangle() share a material, so alternating them is
        // free and only a switch to the quad path costs anything -- which is why neither half flushes.
        ctx.flush();
    }

    /** The fill's strength, 0..1 — the alpha of {@link #FILL_PART}'s computed background colour. */
    private float fillStrength() {
        int argb = fill.getStyle().computed().get(StylePropertyRegistry.BACKGROUND_COLOR);
        return ((argb >>> 24) & 0xFF) / 255f;
    }

    private void paintWedges(CgUiPaintContext ctx, float cx, float cy, float radius) {
        int count = axes.size();
        float strength = fillStrength();
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            double here = angleOf(i);
            double there = angleOf(next);
            float rHere = radius * extent(i);
            float rThere = radius * extent(next);

            float x1 = cx + (float) Math.cos(here) * rHere;
            float y1 = cy + (float) Math.sin(here) * rHere;
            float x2 = cx + (float) Math.cos(there) * rThere;
            float y2 = cy + (float) Math.sin(there) * rThere;

            // OPAQUE HUE, then the theme's fill strength. The alpha an axis carries is ignored on
            // purpose: how much colour a chart lays down is the chart's decision and the same for
            // every axis, so it belongs in one place rather than in six.
            int rim = scaleAlpha(axes.get(i).argb() | 0xFF000000, strength);

            int rimNext = scaleAlpha(axes.get(next).argb() | 0xFF000000, strength);

            CgVectorRenderer.Triangle wedge = ctx.triangle().points(cx, cy, x1, y1, x2, y2);
            float ux = x1 - cx, uy = y1 - cy;
            float vx = x2 - cx, vy = y2 - cy;

            switch (axisGradient) {
                case BETWEEN_AXES -> {
                    // A ramp that is 0 along the spoke to P1 and 1 along the spoke to P2, which is
                    // what puts each axis's own hue on its own line. `t = dot(p - C, dir)` is zero on
                    // the line through C perpendicular to dir, so dir must be perpendicular to u --
                    // and dividing by the cross product is what makes it reach exactly 1 on v.
                    float cross = ux * vy - uy * vx;
                    // Zero when the two spokes are collinear, which includes either value being 0:
                    // the wedge is a sliver with no width to ramp across, so it takes one colour.
                    if (Math.abs(cross) > 1e-4f) wedge.gradient(rim, rimNext, cx, cy, -uy / cross, ux / cross);
                    else wedge.color(rim);
                }
                case TOWARD_CENTRE -> {
                    // The far end is the midpoint of the RIM, which is where this wedge's bisector
                    // actually leaves it -- the rim is a chord rather than an arc, so its own two ends
                    // are nearer the centre than any arc through them would be. `dir` carries the SCALE
                    // as well as the direction, so dividing by the squared length reaches 1 at the rim.
                    float mx = (ux + vx) / 2f, my = (uy + vy) / 2f;
                    float lenSq = mx * mx + my * my;
                    if (lenSq > 0f) wedge.gradient(rim & 0x00FFFFFF, rim, cx, cy, mx / lenSq, my / lenSq);
                    else wedge.color(rim);
                }
                default -> wedge.color(rim);
            }
            // The RIM is the outline; the two spokes are seams against the neighbouring wedges.
            // Feathering those would draw a soft crack down every one of them.
            wedge.silhouetteEdge(CgVectorRenderer.EDGE_P1_P2).submit();
        }
    }

    /** The rings and the spokes — Chart.js's {@code pathRadiusLine} with a polygon rather than a circle. */
    private void paintWeb(CgUiPaintContext ctx, float cx, float cy, float radius, int argb, float line) {
        if (rings <= 0 || (argb >>> 24) == 0) return;
        int count = axes.size();

        for (int ring = 1; ring <= rings; ring++) {
            float r = radius * ring / rings;
            // THE RIM IS THE SCALE AND THE INNER RINGS ARE TICKS ON IT, so they are not equals: the
            // outer ring bounds the chart and stays at full strength, and the rest fade inward. Drawn
            // flat, the web reads as a wireframe cage competing with the data inside it; ramped, it
            // reads as one boundary with graduations under it. The ramp keeps the innermost at half
            // rather than fading it to nothing, or the middle of the chart loses its scale entirely.
            int ringArgb = scaleAlpha(argb, 0.5f + 0.5f * ring / rings);
            for (int i = 0; i < count; i++) {
                double here = angleOf(i);
                double there = angleOf((i + 1) % count);
                ctx.curve()
                        .line(cx + (float) Math.cos(here) * r, cy + (float) Math.sin(here) * r,
                                cx + (float) Math.cos(there) * r, cy + (float) Math.sin(there) * r)
                        // A HALF width, which is this primitive's convention.
                        .width(line / 2f)
                        .color(ringArgb)
                        .submit();
            }
        }

        // The spokes cross every ring, so they take the inner rings' weight rather than the rim's --
        // at full strength they are the brightest thing in the web and the eye follows them instead
        // of the shape the data makes.
        int spokeArgb = scaleAlpha(argb, 0.5f);
        for (int i = 0; i < count; i++) {
            double angle = angleOf(i);
            ctx.curve()
                    .line(cx, cy,
                            cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius)
                    .width(line / 2f)
                    .color(spokeArgb)
                    .submit();
        }
    }

    /** {@code argb} with its alpha multiplied — the colour keeps its hue and only its weight moves. */
    private static int scaleAlpha(int argb, float factor) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * The outline along the value points, in each axis's own colour at FULL strength.
     *
     * <p>What separates a radar chart from a pie: the fill states the area and the rim states the
     * shape, and a translucent fill on its own has no edge to read. Always opaque — an outline at
     * half alpha is not a lighter outline, it is a blurry one — so the contrast between a faint fill
     * and its own saturated rim is the whole effect.</p>
     */
    private void paintRim(CgUiPaintContext ctx, float cx, float cy, float radius, float line) {
        int count = axes.size();
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            double here = angleOf(i);
            double there = angleOf(next);
            float rHere = radius * extent(i);
            float rThere = radius * extent(next);
            // THE RIM BLENDS WITH THE FILL UNDER IT. A segment runs between two vertices that carry
            // different hues, so a flat stroke would put axis i's colour hard up against axis i+1's
            // point -- the one place the eye is looking to read that axis off.
            int from = axes.get(i).argb() | 0xFF000000;
            int to = axisGradient == AxisGradient.BETWEEN_AXES
                    ? axes.get(next).argb() | 0xFF000000
                    : from;
            ctx.curve()
                    .line(cx + (float) Math.cos(here) * rHere, cy + (float) Math.sin(here) * rHere,
                            cx + (float) Math.cos(there) * rThere, cy + (float) Math.sin(there) * rThere)
                    .width(line / 2f)
                    .colors(from, to)
                    .submit();
        }
    }

    // ── Labels ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The plot's radius, after reserving room for the labels.
     *
     * <p>Chart.js fits per angle; this reserves the widest label on both sides and the tallest above
     * and below, which is the same idea with one measurement. Conservative by a few pixels on a chart
     * whose longest label is horizontal, and never wrong in the direction that clips one.</p>
     */
    private float radiusIn(Box box) {
        float widest = 0f;
        float tallest = 0f;
        for (UIText label : labels) {
            Box laid = label.box();
            // A label that has never been laid out reserves nothing; the next frame corrects it.
            if (laid == null) continue;
            widest = Math.max(widest, laid.width());
            tallest = Math.max(tallest, laid.height());
        }
        float available = Math.min(box.width() - widest * 2f, box.height() - tallest * 2f);
        return Math.max(0f, available / 2f);
    }

    /**
     * Places the labels once this frame's layout has measured them.
     *
     * <p>{@code afterLayout} rather than an ordinary per-frame hook: the frame is animation, then
     * style, then layout, so a hook that runs before layout would place every label against the size
     * it had last frame — and against zero on the frame the axes were set.</p>
     */
    private void placeLabelsAfterLayout() {
        UIDocument document = document();
        if (document == null) return;
        // PERMANENT, and cheap. A label's position depends on the chart's box, which changes on any
        // resize with nothing to re-register the hook -- so a run-once version left every label where
        // the first layout put it and only ever looked right at the size it opened at. Staying costs
        // two float comparisons a frame and the hook dies with the node, which is what an owned hook
        // is for. A label's SIZE does not depend on the radius, so there is no loop to settle.
        document.animation().afterLayout(this, delta -> {
            Box box = box();
            if (box != null && (box.width() != placedWidth || box.height() != placedHeight)) {
                placedWidth = box.width();
                placedHeight = box.height();
                placeLabels();
            }
            return true;
        });
    }

    private void placeLabels() {
        Box box = box();
        if (box == null || axes.size() < MIN_AXES) return;
        float radius = radiusIn(box);
        float cx = box.width() / 2f;
        float cy = box.height() / 2f;

        for (int i = 0; i < labels.size(); i++) {
            UIText label = labels.get(i);
            Box laid = label.box();
            if (laid == null) continue;

            double angle = angleOf(i);
            float x = cx + (float) Math.cos(angle) * radius;
            float y = cy + (float) Math.sin(angle) * radius;

            // Chart.js's own two rules, on its angle convention: degrees clockwise from straight up.
            float degrees = (float) ((i * 360d / axes.size()) % 360d);
            float w = laid.width();
            float h = laid.height();

            // getTextAlignForAngle: centred at top and bottom, and otherwise growing away from the chart.
            float shiftX;
            if (degrees == 0f || degrees == 180f) shiftX = -w / 2f;
            else if (degrees < 180f) shiftX = 0f;
            else shiftX = -w;

            // yForAngle: vertically centred at the sides, fully above across the top.
            float shiftY;
            if (degrees == 90f || degrees == 270f) shiftY = -h / 2f;
            else if (degrees > 270f || degrees < 90f) shiftY = -h;
            else shiftY = 0f;

            float left = x + shiftX;
            float top = y + shiftY;
            StyleGroup.inlinePipeline(label.getStyle().getLayoutGroup(),
                    l -> l.left(left).top(top));
        }
        placePoints(radius, cx, cy);
    }

    /**
     * The markers, centred on their VALUE vertices — where the rim turns, not out at the label.
     *
     * <p>Centred rather than corner-placed, which the labels are: a label is a block of text that
     * grows away from the chart, and a point is a dot whose middle IS the datum. Placing one by its
     * corner would sit it half a marker off the line it is meant to be on.</p>
     */
    private void placePoints(float radius, float cx, float cy) {
        for (int i = 0; i < points.size(); i++) {
            UIElement point = points.get(i);
            Box laid = point.box();
            if (laid == null) continue;
            double angle = angleOf(i);
            float r = radius * extent(i);
            float left = cx + (float) Math.cos(angle) * r - laid.width() / 2f;
            float top = cy + (float) Math.sin(angle) * r - laid.height() / 2f;
            StyleGroup.inlinePipeline(point.getStyle().getLayoutGroup(),
                    l -> l.left(left).top(top));
        }
    }

    @Override
    public void connected() {
        super.connected();
        // The axes may have been set before this joined a document, in which case the hook above
        // found none to register with. Asked again here, which is the moment one exists.
        placeLabelsAfterLayout();
    }
}
