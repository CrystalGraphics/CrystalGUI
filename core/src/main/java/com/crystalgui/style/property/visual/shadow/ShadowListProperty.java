package com.crystalgui.style.property.visual.shadow;

import com.crystalgui.style.property.StyleProperty;

/**
 * A shadow-list property: parsed in its {@link ShadowGrammar}, written back in the same, and
 * transitioned with CSS's shadow-list interpolation.
 *
 * <pre>{@code
 * StyleProperty<ShadowList> textShadow =
 *         new ShadowListProperty("text-shadow", ShadowGrammar.TEXT_LEVEL_4).setInheritable(true);
 * }</pre>
 */
public class ShadowListProperty extends StyleProperty<ShadowList> {

    public ShadowListProperty(String name, ShadowGrammar grammar) {
        super(name, ShadowList.class, ShadowList.NONE, raw -> new ShadowValue(raw, grammar));
        setAllowTransition(true);
        setInterpolator(ShadowList::interpolate);
        setWriter(ShadowParser::write);
    }
}
