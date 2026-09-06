package com.crystalgui.widget.composite;

import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.ArgbMath;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
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
 * <p>The web (the rings and the spokes) takes the element's computed {@code border-color} for its
 * colour and {@code ::part(web)}'s laid-out HEIGHT for its thickness -- a border-WIDTH would draw a
 * rectangle around the plot, since a border box is drawn whether or not there is a background. A theme
 * draws
 * it. Each axis's colour is DATA and is written inline onto that axis's label: a registry may declare
 * an attribute in any hue, so no stylesheet can enumerate them. Everything else — the label font, the
 * chart's size, its padding — is ordinary CSS on {@code radarchart} and {@code ::part(axis-label)}.</p>
 */
public class RadarChart extends UIElement {

    public static final Name NAME = Name.of("radarchart");
    
        /**
     * The axis NAMES, and with them how many axes there are — so this is the structural slot and every
     * other per-axis one is read against the count it sets.
     */
    public static final State<RadarChart, List<String>> LABELS =
            State.of("labels", StateTypes.stringListUnder("label"),
                    RadarChart::axisLabels, RadarChart::setAxisLabels, List.of());

    /** One ARGB per axis. Data rather than theme: a registry declares a stat's colour. */
    public static final State<RadarChart, int[]> COLORS =
            State.of("colors", StateTypes.intArrayUnder("argb"),
                    RadarChart::axisColors, RadarChart::setAxisColors, new int[0]);

    /** What each axis's point says on hover. An empty entry says nothing. */
    public static final State<RadarChart, List<String>> DETAILS =
            State.of("details", StateTypes.stringListUnder("detail"),
                    RadarChart::axisDetails, RadarChart::setAxisDetails, List.of());

    /** The data — the one slot a live chart sends per tick. */
    public static final State<RadarChart, double[]> VALUES =
            State.of("values", StateTypes.doubleArrayUnder("value"),
                    RadarChart::values, RadarChart::setValues, new double[0]);

    /** {@code 0} means "the largest value present", which is why it is also what it is omitted at. */
    public static final State<RadarChart, Double> MAX =
            State.of("max", StateTypes.DOUBLE, RadarChart::getMax, RadarChart::setMax, 0d)
                    .omittedWhen(0d);

    public static final State<RadarChart, AxisGradient> GRADIENT =
            State.of("gradient", StateTypes.enumOf(AxisGradient.class),
                    RadarChart::getAxisGradient, RadarChart::setAxisGradient, AxisGradient.NONE)
                    .omittedWhen(AxisGradient.NONE);

    /**
     * LABELS BEFORE EVERYTHING PER-AXIS, which is why a contract applies slots in declaration order.
     *
     * <p>Slider's range-before-value, one dimension up: the label list is what says how many axes there
     * are, so colours, details and values arriving first would be written against the count the chart
     * happened to hold — silently, since every one of them ignores an index past the end. A chart
     * growing from four attributes to six would take its two new values on the NEXT tick that moved
     * them, which for a stat nobody is training is never.</p>
     *
     * <p>There are no events. Nothing here is a control: a radar reports a model rather than editing
     * one, and its points carry tooltips rather than gestures.</p>
     */
    public static final WidgetContract<RadarChart> CONTRACT = WidgetContracts.register(
            WidgetContract.of(RadarChart.class, "radarchart")
                    .state(LABELS)
                    .state(COLORS)
                    .state(DETAILS)
                    .state(VALUES)
                    .state(MAX)
                    .state(GRADIENT)
                    .primary(VALUES)
                    .build());

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
        TOWARD_CENTRE,

        /**
         * Both: the spoke-to-spoke blend, fading out toward the centre.
         *
         * <p>The two answer different halves of the same picture, so they compose -- the hue says which
         * axis a region belongs to and the fade takes the colour off the point where all of them meet.
         * The blend is stepped rather than continuous across each slice here (see
         * {@code paintBlendedWedge}), which is affordable precisely BECAUSE of the fade: the step is
         * scaled by the alpha it is drawn at, so it is smallest exactly where a fan's error is normally
         * worst.</p>
         */
        BETWEEN_AXES_TOWARD_CENTRE
    }

    /**
     * Rings when nothing says otherwise. LOCAL, and deliberately not a state slot: how many
     * graduations a scale draws is a decision the panel makes once when it builds the chart, not
     * something a model moves, so putting it on the wire would be a slot nothing ever sends.
     */
    private static final int DEFAULT_RINGS = 4;

    /** What an axis added by a shorter description than the one before it is coloured. */
    private static final int DEFAULT_AXIS_COLOR = 0xFFFFFFFF;



    /** Below three there is no polygon to draw, and two axes are a line. */
    private static final int MIN_AXES = 3;

    /**
     * Sub-triangles per wedge under {@link AxisGradient#BETWEEN_AXES}.
     *
     * <p>The residual step across a slice boundary is the wedge's whole colour span divided by this
     * and fades to nothing at the rim, so sixteen leaves a sixteenth of a red-to-cyan swing over the
     * few pixels nearest the centre, under a fill that is part transparent anyway.</p>
     */
    private static final int WEDGE_SLICES = 16;

    /**
     * Slices under {@link AxisGradient#BETWEEN_AXES_TOWARD_CENTRE}, where each carries ONE colour and
     * spends its ramp on the fade instead.
     *
     * <p>So the step does not decay with radius the way {@link #WEDGE_SLICES}'s does -- it is scaled by
     * the fade's own alpha and is therefore largest at the RIM, alongside a stroke that is blending
     * smoothly. Forty puts it under three levels there.</p>
     */
    private static final int WEDGE_SLICES_FADED = 40;

    private final ShadowRoot shadow;
    private final UIElement web;
    private final UIElement fill;
    private final List<Axis> axes = new ArrayList<>();
    private final List<UIText> labels = new ArrayList<>();
    private final List<UIElement> points = new ArrayList<>();
    /** One per point, ALWAYS attached: empty text is how a tooltip says nothing, so a detail that
     *  arrives after the axes were set has something to arrive at. */
    private final List<Tooltip> tips = new ArrayList<>();

    /** {@code 0} means "the largest value present", which is what a sheet of attributes wants. */
    private double explicitMax;
    private int rings = DEFAULT_RINGS;
    private AxisGradient axisGradient = AxisGradient.NONE;
    /** What the labels were last placed against, so the hook below costs two comparisons a frame. */
    private float placedWidth = -1f;
    private float placedHeight = -1f;
    /** Kept from the last label pass, so the points can be re-placed without re-measuring them. */
    private float placedRadius;

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
        for (Tooltip tip : tips) tip.detach();
        tips.clear();

        for (Axis axis : newAxes) {
            axes.add(axis);
            UIText label = new UIText(axis.label());
            label.set(Attribute.PART, AXIS_LABEL_PART);
            // DATA, and the one legitimate inline colour write: an axis's hue comes from whatever
            // registry declared it, so no sheet can name them all.
            // FULL STRENGTH. The alpha on an axis belongs to its FILL; a label carrying it would fade
            // with the wedge, and a label is read rather than looked through.
            // Colour goes on through the shared helper, so setAxisColors writes it in exactly one place.
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
            StyleGroup.defaultPipeline(point.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            // No delay asked for: a point is a target the pointer was AIMED at, so the engine's
            // instant default is already right and stating it again would just be noise.
            tips.add(Tooltip.attach(point, axis.detail() == null ? "" : axis.detail()));
            points.add(point);
            shadow.append(point);
            applyAxisColour(axes.size() - 1, axis.argb());
        }
        placeLabelsAfterLayout();
        notifyStateChanged();
        return this;
    }

    /**
     * An axis's hue onto the two elements that wear it, at FULL strength.
     *
     * <p>The one legitimate inline colour write in this widget: a hue comes from whatever registry
     * declared the stat, so no sheet can name them all. Full strength on both because the alpha an
     * axis carries belongs to its FILL -- a label carrying it would fade with the wedge and a label is
     * read rather than looked through, and a marker washed with its own wedge disappears into it.</p>
     */
    private void applyAxisColour(int index, int argb) {
        int opaque = argb | 0xFF000000;
        StyleGroup.inlinePipeline(labels.get(index).getStyle().getGeneralGroup(), g -> g.color(opaque));
        StyleGroup.inlinePipeline(points.get(index).getStyle().getGeneralGroup(),
                g -> g.backgroundColor(opaque));
    }

    public List<Axis> axes() {
        return List.copyOf(axes);
    }

    /** @see #LABELS */
    public List<String> axisLabels() {
        List<String> out = new ArrayList<>(axes.size());
        for (Axis axis : axes) out.add(axis.label());
        return out;
    }

    /** @see #COLORS */
    public int[] axisColors() {
        int[] out = new int[axes.size()];
        for (int i = 0; i < out.length; i++) out[i] = axes.get(i).argb();
        return out;
    }

    /** Empty rather than {@code null} for an axis with nothing to say -- a wire has no third answer. */
    public List<String> axisDetails() {
        List<String> out = new ArrayList<>(axes.size());
        for (Axis axis : axes) out.add(axis.detail() == null ? "" : axis.detail());
        return out;
    }

    /** @see #VALUES */
    public double[] values() {
        double[] out = new double[axes.size()];
        for (int i = 0; i < out.length; i++) out[i] = axes.get(i).value();
        return out;
    }

    /**
     * Renames the axes, and their COUNT is what says how many there are.
     *
     * <p>Each axis keeps whatever colour, detail and value it already had at that index, so a rename
     * does not blank the chart and the three per-axis slots can arrive in any order after this one. A
     * list of a different length rebuilds, which is the only thing here that does.</p>
     */
    public RadarChart setAxisLabels(List<String> newLabels) {
        if (newLabels == null || newLabels.equals(axisLabels())) return this;
        List<Axis> rebuilt = new ArrayList<>(newLabels.size());
        for (int i = 0; i < newLabels.size(); i++) {
            Axis old = i < axes.size() ? axes.get(i) : null;
            rebuilt.add(old == null
                    ? new Axis(newLabels.get(i), 0d, DEFAULT_AXIS_COLOR, null)
                    : new Axis(newLabels.get(i), old.value(), old.argb(), old.detail()));
        }
        return setAxes(rebuilt);
    }

    /** One ARGB per axis, in order. A shorter list leaves the rest alone; extras are ignored. */
    public RadarChart setAxisColors(int[] colors) {
        if (colors == null) return this;
        boolean moved = false;
        for (int i = 0; i < colors.length && i < axes.size(); i++) {
            Axis axis = axes.get(i);
            if (axis.argb() == colors[i]) continue;
            axes.set(i, new Axis(axis.label(), axis.value(), colors[i], axis.detail()));
            applyAxisColour(i, colors[i]);
            moved = true;
        }
        if (moved) notifyStateChanged();
        return this;
    }

    /** One hover text per axis, in order. @see #setDetail */
    public RadarChart setAxisDetails(List<String> details) {
        if (details == null) return this;
        for (int i = 0; i < details.size() && i < axes.size(); i++) setDetail(i, details.get(i));
        return this;
    }

    /**
     * Where the axis called {@code label} sits, or {@code -1}. First match, if two share a name.
     *
     * <p>Public so a caller with a list that may not match this chart can ask before it writes -- the
     * setters below refuse a name they do not have, and this is how you avoid finding out that way.</p>
     */
    public int axisIndex(String label) {
        for (int i = 0; i < axes.size(); i++) {
            if (axes.get(i).label().equals(label)) return i;
        }
        return -1;
    }

    /** What the axis called {@code label} currently reaches. @throws IllegalArgumentException if absent */
    public double value(String label) {
        return axes.get(require(label)).value();
    }

    /**
     * Moves one axis to a new value, leaving its name, its colour and its hover text alone.
     *
     * <p>BY NAME, because the name is already the identity: {@link #LABELS} is the structural slot on
     * the wire and everything else is read against the count it sets, so a chart has exactly one notion
     * of which axis is which and an API keyed on position would be a second. A caller writing
     * {@code setValue("SPI", 12)} also cannot be broken by an axis being inserted above it.</p>
     *
     * <p>The chart OWNS its data and a caller changes it through here, rather than handing over an
     * observable for the chart to watch: the fill, the rim and the markers are all read from these
     * values on the frame they are drawn, so there is nothing to notify and no subscription to outlive
     * anything.</p>
     *
     * @throws IllegalArgumentException if no axis carries that label. A name that is not there is a
     *         misspelling or a chart that has not been given its axes yet, and both are the kind of
     *         mistake that is invisible if a setter shrugs: the value simply never appears
     */
    public RadarChart setValue(String label, double value) {
        return setValue(require(label), value);
    }

    /** @see #setValue(String, double) */
    public RadarChart setDetail(String label, @Nullable String detail) {
        return setDetail(require(label), detail);
    }

    private int require(String label) {
        int index = axisIndex(label);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "No axis called '" + label + "'. This chart has " + axisLabels());
        }
        return index;
    }

    private RadarChart setValue(int index, double value) {
        if (index < 0 || index >= axes.size()) return this;
        Axis axis = axes.get(index);
        if (axis.value() == value) return this;
        axes.set(index, new Axis(axis.label(), value, axis.argb(), axis.detail()));
        notifyStateChanged();
        return this;
    }

    /**
     * Every axis at once, IN ORDER -- the positional form, and the one {@link #VALUES} applies.
     *
     * <p>Position is the wire's business: the four per-axis slots are parallel lists whose order is
     * the label list's, so this is what a description decodes into. A caller with a name should use
     * {@link #setValue(String, double)}. Extra values are ignored rather than refused, because a
     * description written by a newer peer may legitimately carry more axes than this build knows.</p>
     */
    public RadarChart setValues(double... values) {
        for (int i = 0; i < values.length; i++) setValue(i, values[i]);
        return this;
    }

    /**
     * Changes what hovering one axis's point says. {@code null} or empty says nothing at all.
     *
     * <p>Which is the half of {@link #setValue} that cannot be pulled: a marker's POSITION is read
     * every frame, but its tooltip's words are text in an element, and a detail that quotes the value
     * — which is what a detail is for — would otherwise go on quoting the one it was declared with.</p>
     */
    private RadarChart setDetail(int index, @Nullable String detail) {
        if (index < 0 || index >= axes.size()) return this;
        Axis axis = axes.get(index);
        String text = detail == null ? "" : detail;
        // GUARDED, or a panel pushing its model into its controls every tick -- which is the shape
        // every server-side panel is written in -- sends a delta a tick carrying a value nobody moved.
        if (text.equals(axis.detail() == null ? "" : axis.detail())) return this;
        axes.set(index, new Axis(axis.label(), axis.value(), axis.argb(), detail));
        tips.get(index).setText(text);
        notifyStateChanged();
        return this;
    }

    /** The value the outer ring stands for. {@code 0} restores "the largest value present". */
    public RadarChart setMax(double max) {
        double clamped = Math.max(0d, max);
        if (clamped == this.explicitMax) return this;
        this.explicitMax = clamped;
        notifyStateChanged();
        return this;
    }

    /** {@code 0} means "the largest value present". @see #setMax */
    public double getMax() {
        return explicitMax;
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
        AxisGradient wanted = gradient == null ? AxisGradient.NONE : gradient;
        if (wanted == this.axisGradient) return this;
        this.axisGradient = wanted;
        notifyStateChanged();
        return this;
    }

    public AxisGradient getAxisGradient() {
        return axisGradient;
    }

    /** How many rings the web draws, the outermost included. Fewer than one draws no web. */
    public RadarChart setRings(int rings) {
        this.rings = Math.max(0, rings);
        return this;
    }

    public int getRings() {
        return rings;
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

            if (blendsAcrossWedge()) {
                paintBlendedWedge(ctx, cx, cy, x1, y1, x2, y2, rim, rimNext,
                        axisGradient == AxisGradient.BETWEEN_AXES_TOWARD_CENTRE);
                continue;
            }

            CgVectorRenderer.Triangle wedge = ctx.triangle().points(cx, cy, x1, y1, x2, y2);
            if (axisGradient == AxisGradient.TOWARD_CENTRE) {
                // The far end is the midpoint of the RIM, which is where this wedge's bisector actually
                // leaves it -- the rim is a chord rather than an arc, so its own two ends are nearer the
                // centre than any arc through them would be. `dir` carries the SCALE as well as the
                // direction, so dividing by the squared length reaches 1 at the rim.
                float mx = (x1 + x2) / 2f - cx, my = (y1 + y2) / 2f - cy;
                float lenSq = mx * mx + my * my;
                if (lenSq > 0f) wedge.gradient(rim & 0x00FFFFFF, rim, cx, cy, mx / lenSq, my / lenSq);
                else wedge.color(rim);
            } else {
                wedge.color(rim);
            }
            // The RIM is the outline; the two spokes are seams against the neighbouring wedges.
            // Feathering those would draw a soft crack down every one of them.
            wedge.silhouetteEdge(CgVectorRenderer.EDGE_P1_P2).submit();
        }
    }

    /**
     * One wedge as an angular FAN, so its colour belongs to the ANGLE rather than to the distance
     * across it.
     *
     * <p>A single linear gradient cannot do that, and the near-miss is what makes it worth stating:
     * {@code t = dot(p - C, dir)} is constant along lines PARALLEL to the first spoke, never along
     * rays out of the centre. So the second spoke reaches the next axis's colour only at its tip and
     * carries this one's near the middle, which puts a step down every spoke where one wedge's start
     * colour meets its neighbour's -- worst at the centre, gone at the rim. No {@code dir} escapes it:
     * two iso-lines through the centre would have to be the same line.</p>
     *
     * <p>Each slice's own version of that error is a fraction of the wedge's colour span, so it goes
     * away. The cuts are even steps along the RIM rather than even angles, which is what makes the
     * fill agree exactly with the stroke drawn over it -- {@code Curve.colors} ramps along the segment,
     * so the chord is the parameter both of them read.</p>
     */
    private void paintBlendedWedge(CgUiPaintContext ctx, float cx, float cy,
                                   float ax, float ay, float bx, float by,
                                   int from, int to, boolean fade) {
        int slices = fade ? WEDGE_SLICES_FADED : WEDGE_SLICES;
        float px = ax, py = ay, pm = 0f;
        int pc = from;
        for (int slice = 1; slice <= slices; slice++) {
            float m = (float) slice / slices;
            float qx = ax + (bx - ax) * m;
            float qy = ay + (by - ay) * m;
            int qc = ArgbMath.lerp(from, to, m);

            CgVectorRenderer.Triangle wedge = ctx.triangle().points(cx, cy, px, py, qx, qy);
            if (fade) {
                // ONE ramp is one axis of variation, and the fade needs it -- so the hue is flat across
                // the slice and steps at its edges. Iso-lines parallel to the outer edge put t = 0 at
                // the centre and exactly 1 along the rim, which the bisector reading below only reaches
                // at the rim's midpoint. Transparency keeps the RGB so the ramp fades rather than
                // darkening toward transparent BLACK.
                int mid = ArgbMath.lerp(from, to, (pm + m) / 2f);
                float ex = qx - px, ey = qy - py;
                float nx = -ey, ny = ex;
                float d = (px - cx) * nx + (py - cy) * ny;
                if (Math.abs(d) > 1e-4f) wedge.gradient(mid & 0x00FFFFFF, mid, cx, cy, nx / d, ny / d);
                else wedge.color(mid);
            } else {
                // Zero when the two edges are collinear, which includes either value being 0: a sliver
                // with no width to ramp across, so it takes one colour.
                float cross = (px - cx) * (qy - cy) - (py - cy) * (qx - cx);
                if (Math.abs(cross) > 1e-4f) {
                    wedge.gradient(pc, qc, cx, cy, -(py - cy) / cross, (px - cx) / cross);
                } else {
                    wedge.color(pc);
                }
            }
            // Only the rim is an outline. Every other edge here is a seam -- against the next slice or
            // the next wedge -- and feathering those draws a soft crack down each one.
            wedge.silhouetteEdge(CgVectorRenderer.EDGE_P1_P2).submit();

            px = qx;
            py = qy;
            pm = m;
            pc = qc;
        }
    }

    /** Whether the fill and the rim ramp from one axis's hue to the next's, rather than staying flat. */
    private boolean blendsAcrossWedge() {
        return axisGradient == AxisGradient.BETWEEN_AXES
                || axisGradient == AxisGradient.BETWEEN_AXES_TOWARD_CENTRE;
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
            int to = blendsAcrossWedge() ? axes.get(next).argb() | 0xFF000000 : from;
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
            if (box == null) return true;
            if (box.width() != placedWidth || box.height() != placedHeight) {
                placedWidth = box.width();
                placedHeight = box.height();
                placedRadius = radiusIn(box);
                placeLabels();
            }
            // THE MARKERS FOLLOW THE DATA, which moves with no layout behind it -- so unlike the labels
            // they cannot be placed only when the box changes. Pulled rather than pushed: an unchanged
            // inset writes no candidate (`replaceOrPutCandidate` no-ops), so a still chart costs six
            // comparisons a frame and nothing has to be told a value moved.
            placePoints(placedRadius, box.width() / 2f, box.height() / 2f);
            return true;
        });
    }

    private void placeLabels() {
        Box box = box();
        if (box == null || axes.size() < MIN_AXES) return;
        float radius = placedRadius;
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
