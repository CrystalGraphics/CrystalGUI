package com.crystalgui.style.property.visual.shadow;

import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.StyleValue;

import javax.annotation.Nullable;

/**
 * A shadow declaration, parsed once and resolved per element, since its lengths may be {@code em}.
 *
 * <pre>
 *   text-shadow: 0 0 0.5em currentcolor   -&gt; 5px blur at 10px, 8px at 16px, per matched element
 * </pre>
 */
public class ShadowValue extends StyleValue<ShadowList> implements FontRelative<ShadowList> {

    private final ShadowGrammar grammar;
    @Nullable
    private final ShadowParser.Parsed parsed;

    public ShadowValue(String rawValue, ShadowGrammar grammar) {
        super(rawValue);
        this.grammar = grammar;
        this.parsed = ShadowParser.parse(rawValue, grammar);
    }

    @Override
    protected @Nullable ShadowList doCompute(String rawValue) {
        return parsed == null ? null : parsed.resolve(REFERENCE_FONT_SIZE);
    }

    @Override
    public boolean isFontRelative() {
        return parsed != null && parsed.isFontRelative();
    }

    @Override
    public ShadowList resolveAgainst(float fontSize) {
        return parsed.resolve(fontSize);
    }

    public ShadowGrammar grammar() {
        return grammar;
    }
}
