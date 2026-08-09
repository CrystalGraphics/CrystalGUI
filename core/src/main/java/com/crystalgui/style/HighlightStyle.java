package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.text.TextDecorationLine;

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
            StylePropertyRegistry.TEXT_DECORATION_LINE);

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
