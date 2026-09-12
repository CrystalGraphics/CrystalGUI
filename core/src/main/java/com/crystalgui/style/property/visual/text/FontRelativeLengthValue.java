package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.border.LengthPercent;

import javax.annotation.Nullable;

/**
 * A {@link LengthPercent} for a property whose percentage resolves against the <b>font size</b>
 * rather than against an axis of the box — which makes {@code em} and {@code %} the same unit here,
 * so this accepts both.
 *
 * <pre>
 *   text-stroke: 0.04em;   -&gt; percent(0.04)   // a 25th of the font size
 *   text-stroke: 4%;       -&gt; percent(0.04)   // identical
 *   text-stroke: 2px;      -&gt; px(2)           // absolute, and NOT the same thing
 * </pre>
 *
 * <p>{@code LengthPercent.parse} itself must never learn {@code em}: it also backs
 * {@code border-radius}, {@code transform-origin} and the outline offsets, whose percentages are
 * fractions of the BOX, so there {@code 1em} would have to mean a whole box width. The unit only
 * has a meaning where the resolve axis is already the font size, which is why it lives beside the
 * one family of properties that qualifies.</p>
 *
 * <p>Easy to get wrong: {@code em} is folded into a PERCENT, not into px. Resolving it to pixels
 * here would need a font size that the cascade does not have at parse time — and the whole reason a
 * percentage works is that the consumer supplies the font size as the resolve axis.</p>
 *
 * <p><b>Why this folds rather than implementing {@link FontRelative}.</b> That interface exists for
 * values whose percentage means something else — a {@code DimensionValue}'s percentage is a fraction
 * of the parent, so its {@code em} needs a second, separate resolution channel and a pass over every
 * matched element. Here the percentage basis ALREADY IS the font size, so the two channels would
 * carry the same number under two names. The unit itself still comes from {@code FontRelative}.</p>
 */
public class FontRelativeLengthValue extends StyleValue<LengthPercent> {

    public FontRelativeLengthValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable LengthPercent doCompute(String rawValue) {
        // The UNIT is FontRelative's, not this class's: it owns em for every parser, precisely so the
        // suffix is not spelled out in three places. Tested before LengthPercent.parse for the reason
        // stated there -- "1.5em" is not a number, so the bare-number fallback would throw and drop a
        // perfectly well formed declaration.
        float em = FontRelative.multipleIn(rawValue);
        if (!Float.isNaN(em)) return LengthPercent.percent(em);
        return LengthPercent.parse(rawValue);
    }
}
