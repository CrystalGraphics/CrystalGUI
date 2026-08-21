package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.text.FontStyle;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.style.property.visual.text.TextDecorationLine;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;

import dev.vfyjxf.taffy.style.LengthPercentageAuto;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The resolved style of one {@code ::highlight(name)} pseudo-element on one element — CSS Pseudo-Elements
 * 4's <i>highlight pseudo-element</i>, as used by the CSS Custom Highlight API.
 *
 * <h3>Why this is not an {@link ElementStyle}</h3>
 * <p>{@code ElementStyle} is bound to a {@link com.crystalgui.ui.UIElement}: it calls
 * {@code onStyleChanged()}, marks the Taffy tree dirty, and registers transitions keyed on that element.
 * A highlight is not an element and has none of those; one per name per element would fire all three
 * spuriously. So this is a plain resolved-value map, built by {@link StyleEngine} using the same
 * {@code StyleSlot} comparison the real cascade uses — the cascade is shared, the plumbing is not.</p>
 *
 * <h3>The property set is restricted, and that is the spec, not a shortcut</h3>
 * <p>CSS Pseudo-Elements 4 allows highlight pseudo-elements only properties that <i>"do not affect
 * layout and can be applied performantly in a highly dynamic environment"</i> — colour, background,
 * text-decoration, text-shadow. Font properties are excluded <b>deliberately</b>, because a highlight
 * must never reflow the text it highlights: a search that changed the line breaks as you typed would be
 * unusable.</p>
 *
 * <p>{@link StyleEngine} enforces that by dropping any other declaration with a warning, rather than
 * accepting it and quietly doing nothing.</p>
 */
public final class HighlightStyle {

    /**
     * The properties a highlight actually paints.
     *
     * <p>Smaller than the spec's list, and that gap is tracked separately in {@link #NOT_YET_PAINTABLE}
     * rather than papered over. A property that resolves through the cascade and is then dropped on the
     * floor is worse than one the engine refuses outright: the rule looks right, the colour never
     * appears, and there is nothing to search for. {@code CgStyleSpan}'s own javadoc records the same
     * lesson from three fields that were carried faithfully and then ignored.</p>
     */
    public static final Set<StyleProperty<?>> ALLOWED = Set.of(
            StylePropertyRegistry.COLOR,
            StylePropertyRegistry.BACKGROUND_COLOR,
            StylePropertyRegistry.TEXT_DECORATION_LINE,
            StylePropertyRegistry.TEXT_DECORATION_COLOR,
            // font-weight and font-style are a DELIBERATE DIVERGENCE from CSS Pseudo-Elements 4, which
            // allows a highlight only properties that cannot reflow the text it highlights.
            //
            // The spec's reason does not hold here, and that is the whole argument. On the web a highlight
            // is a pure overlay painted over already-laid-out text, so permitting a wider face would mean a
            // highlight could move the very glyphs it is highlighting. In this engine a highlight ALREADY
            // re-shapes -- a span boundary is a shaping-run boundary, which UIText's own javadoc records --
            // so the premise the restriction rests on is false for us. The incremental risk is that a
            // WRAPPING label could re-wrap; the incremental gain is that a code editor can express its
            // scheme at all, and every reference scheme in existence italicises comments.
            //
            // Refusing them instead would have meant an editor colour scheme that silently cannot say what
            // IntelliJ's, VS Code's and Zed's all say. Allowing them and then dropping them where reflow is
            // possible would be worse still -- that is the "resolves but paints nothing" class this file
            // exists to prevent, and it is why there is no ALLOWED_IN_EDITOR variant: one rule, applied
            // everywhere, is the only version nobody has to remember.
            StylePropertyRegistry.FONT_WEIGHT,
            StylePropertyRegistry.FONT_STYLE,
            // border-radius and the two horizontal paddings are the BAND'S OWN GEOMETRY, and they are a
            // second deliberate divergence for the same reason as the two above: CSS forbids them because
            // on the web a highlight is a pure overlay over already-laid-out text, so anything box-shaped
            // would have to be invented from nothing. Here the band is a real rect this engine draws
            // itself -- `UIText.paintHighlightBands` already walks the shaped runs and fills one -- so
            // rounding it and inflating it horizontally are things it can simply do.
            //
            // NEITHER CAN REFLOW ANYTHING, which is the restriction's actual purpose. The padding inflates
            // the painted rect and never the text's measurement, so the glyphs do not move and the line
            // does not re-wrap; it is `outline-offset`'s relationship to a box, not `padding`'s. Vertical
            // padding is deliberately NOT allowed: a band is as tall as its line box, and inflating that
            // makes consecutive lines overlap each other's bands.
            //
            // The point is inline code. `{@code x}` on a bare recoloured run reads as an arbitrarily
            // tinted word; on a rounded plate with air either side it reads as code, which is what every
            // reference renderer draws and what this could not express.
            BorderRadiusProperties.TOP_LEFT_X,
            BorderRadiusProperties.TOP_LEFT_Y,
            BorderRadiusProperties.TOP_RIGHT_X,
            BorderRadiusProperties.TOP_RIGHT_Y,
            BorderRadiusProperties.BOTTOM_RIGHT_X,
            BorderRadiusProperties.BOTTOM_RIGHT_Y,
            BorderRadiusProperties.BOTTOM_LEFT_X,
            BorderRadiusProperties.BOTTOM_LEFT_Y,
            LayoutProperties.PADDING_LEFT,
            LayoutProperties.PADDING_RIGHT);

    /**
     * Allowed by CSS on a highlight pseudo-element, <b>not yet paintable here</b> — a different failure
     * from a property CSS forbids, so it gets a different message.
     *
     * <p>{@code text-shadow} on a highlight is a second draw of just that range, which is not expressible
     * as a {@code CgStyleSpan} — that carries colour and decorations and nothing positional.</p>
     *
     * <p><b>{@code background-color} used to be here and no longer is.</b> The band it needs turned out to
     * be free: shaping already breaks a run at every span boundary, so a highlighted range <em>is</em> one
     * or more {@code CgShapedRun}s, and each carries {@code sourceStart}/{@code sourceEnd} and
     * {@code totalAdvance}. {@code UIText} walks them and fills a rect before the glyphs. No per-range
     * measurement and no second shaping pass — the geometry this file said the layer did not have was
     * sitting in the layout the whole time.</p>
     */
    public static final Set<StyleProperty<?>> NOT_YET_PAINTABLE = Set.of(
            StylePropertyRegistry.TEXT_SHADOW);

    /** No rule matched — every getter falls through to its inherited/absent answer. */
    public static final HighlightStyle EMPTY = new HighlightStyle(Collections.emptyMap());

    private final Map<StyleProperty<?>, Object> values;

    HighlightStyle(Map<StyleProperty<?>, Object> values) {
        this.values = values.isEmpty() ? Collections.emptyMap() : new HashMap<>(values);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(StyleProperty<T> property, T fallback) {
        Object value = values.get(property);
        return value == null ? fallback : (T) value;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * The glyph colour, or {@code inherited} when this highlight does not set one.
     *
     * <p>Falling back to the originating element's colour <em>is</em> inheritance for a highlight
     * pseudo-element: the spec makes it inherit from the element it decorates, not from that element's
     * parent. A background-only highlight — a search hit — therefore leaves the text its normal
     * colour, which is what makes the range still readable.</p>
     */
    /**
     * The band behind this range, or {@code 0} for none.
     *
     * <p>Zero rather than a nullable Integer because a fully transparent band and no band are the same
     * thing to a painter, and every caller would otherwise repeat the null check.</p>
     */
    public int backgroundColor() {
        return get(StylePropertyRegistry.BACKGROUND_COLOR, 0);
    }

    public int color(int inherited) {
        return get(StylePropertyRegistry.COLOR, inherited);
    }

    public Set<TextDecorationLine> decorations() {
        return get(StylePropertyRegistry.TEXT_DECORATION_LINE, Collections.emptySet());
    }

    /**
     * The colour of this range's underline or strikethrough, or {@code 0} for the text's own colour.
     *
     * <p>{@code 0} is both CSS's {@code text-decoration-color: currentColor} default and the backend's
     * sentinel for "inherit the draw colour", so the two agree without a translation.</p>
     */
    public int decorationColor() {
        return get(StylePropertyRegistry.TEXT_DECORATION_COLOR, 0);
    }

    /**
     * The band's corner radii in pixels, in {@code CgUiRoundedRect}'s order — TL, TR, BR, BL, each an
     * {@code (rx, ry)} pair — or {@code null} when every corner is square.
     *
     * <p>Null rather than an array of zeroes so the painter keeps its {@code fillRect} fast path: a
     * rounded band is a separate material and therefore a separate draw call, which is the right cost for
     * a few words of inline code and the wrong one for an editor's selection.</p>
     */
    @Nullable
    public float[] cornerRadii(float width, float height) {
        float tlx = radius(BorderRadiusProperties.TOP_LEFT_X, width);
        float tly = radius(BorderRadiusProperties.TOP_LEFT_Y, height);
        float trx = radius(BorderRadiusProperties.TOP_RIGHT_X, width);
        float trY = radius(BorderRadiusProperties.TOP_RIGHT_Y, height);
        float brx = radius(BorderRadiusProperties.BOTTOM_RIGHT_X, width);
        float bry = radius(BorderRadiusProperties.BOTTOM_RIGHT_Y, height);
        float blx = radius(BorderRadiusProperties.BOTTOM_LEFT_X, width);
        float bly = radius(BorderRadiusProperties.BOTTOM_LEFT_Y, height);
        if (tlx <= 0f && tly <= 0f && trx <= 0f && trY <= 0f
                && brx <= 0f && bry <= 0f && blx <= 0f && bly <= 0f) {
            return null;
        }
        return new float[] {tlx, tly, trx, trY, brx, bry, blx, bly};
    }

    private float radius(StyleProperty<LengthPercent> corner, float axis) {
        LengthPercent value = get(corner, null);
        return value == null ? 0f : Math.max(0f, value.resolve(axis));
    }

    /** How far the band extends past the run's left edge, in pixels. */
    public float bandPadLeft(float axis) {
        return pad(LayoutProperties.PADDING_LEFT, axis);
    }

    /** How far the band extends past the run's right edge, in pixels. */
    public float bandPadRight(float axis) {
        return pad(LayoutProperties.PADDING_RIGHT, axis);
    }

    /**
     * {@code auto} reads as zero here.
     *
     * <p>It is {@code padding-*}'s initial value and means "the layout decides" — but nothing is laying
     * this out, so the only honest reading is "no inflation". The enum is compared by name rather than
     * imported: it is Taffy's, and a band's padding is not a Taffy quantity.</p>
     */
    private float pad(StyleProperty<LengthPercentageAuto> property, float axis) {
        LengthPercentageAuto value = get(property, null);
        if (value == null) return 0f;
        String kind = value.getType().name();
        if ("LENGTH".equals(kind)) return Math.max(0f, value.getValue());
        if ("PERCENT".equals(kind)) return Math.max(0f, value.getValue() * axis);
        return 0f;
    }

    /**
     * Whether this range is bold, falling back to the originating element's weight.
     *
     * <p>The fallback is what keeps a bold label bold across the three characters a search happened to
     * match: a highlight that says nothing about weight must not silently make its range lighter than
     * the text around it.</p>
     */
    public boolean isBold(boolean inherited) {
        FontWeight weight = get(StylePropertyRegistry.FONT_WEIGHT, null);
        return weight == null ? inherited : weight.isBold();
    }

    /** Whether this range is italic, falling back to the originating element's style. See {@link #isBold}. */
    public boolean isItalic(boolean inherited) {
        FontStyle style = get(StylePropertyRegistry.FONT_STYLE, null);
        return style == null ? inherited : style.isItalic();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HighlightStyle && ((HighlightStyle) other).values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "HighlightStyle" + values;
    }
}
