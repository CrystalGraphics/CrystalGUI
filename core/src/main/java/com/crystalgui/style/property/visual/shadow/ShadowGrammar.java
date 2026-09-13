package com.crystalgui.style.property.visual.shadow;

/**
 * Which shadow syntax a property accepts — Blink's {@code AllowInsetAndSpread}, with the one range CSS
 * gives text that it gives nothing else.
 *
 * <pre>{@code
 * ShadowList.parse("1px 1px 2px red", ShadowGrammar.TEXT_LEVEL_3);         // valid
 * ShadowList.parse("1px 1px 2px 3px red", ShadowGrammar.TEXT_LEVEL_3);     // null: no spread in Level 3
 * ShadowList.parse("1px 1px 2px 3px inset", ShadowGrammar.TEXT_LEVEL_4);   // valid
 * }</pre>
 */
public enum ShadowGrammar {
    /** CSS Text Decoration 3: {@code none | [ <color>? && <length>{2,3} ]#}. */
    TEXT_LEVEL_3(true, false, false),
    /** CSS Text Decoration 4: {@code none | <shadow>#}, with a spread that may not be negative. */
    TEXT_LEVEL_4(true, true, true),
    /** CSS Backgrounds 3 {@code box-shadow}: {@code none | <shadow>#}, spread of any sign. */
    BOX(true, true, false),
    /** Filter Effects 1 {@code drop-shadow()}: one shadow, no spread, no {@code inset}, no {@code none}. */
    DROP_SHADOW(false, false, false);

    /** Whether {@code none} and a comma-separated list are accepted. */
    public final boolean list;
    /** Whether a fourth length and {@code inset} are accepted. */
    public final boolean insetAndSpread;
    /** Whether a negative spread is invalid, as CSS Text Decoration 4 makes it for text. */
    public final boolean spreadNonNegative;

    ShadowGrammar(boolean list, boolean insetAndSpread, boolean spreadNonNegative) {
        this.list = list;
        this.insetAndSpread = insetAndSpread;
        this.spreadNonNegative = spreadNonNegative;
    }
}
