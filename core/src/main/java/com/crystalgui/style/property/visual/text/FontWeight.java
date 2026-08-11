package com.crystalgui.style.property.visual.text;

/**
 * CSS-facing {@code font-weight} — how heavy the text is drawn.
 *
 * <h3>Two values, because the backend has two faces</h3>
 *
 * <p>CSS models weight as a 100–900 scale plus keywords. This engine resolves that scale onto
 * {@code CgFontStyle}, which offers exactly {@code REGULAR} and {@code BOLD} — so a nine-step enum
 * would be nine names for two outcomes, and every one of the seven that could not be told apart
 * would be a property that resolves, cascades, and paints identically to its neighbour. That is the
 * failure this codebase keeps paying for, so it is not repeated here.</p>
 *
 * <p>The numeric scale is still <b>accepted</b> — see {@code FontWeightValue} — because authors write
 * {@code font-weight: 600} and refusing it would be a parse warning for a value with an obvious
 * meaning. It maps by the same rule a browser uses when a family ships only two faces: 600 and above
 * take the bold face, everything below takes the regular one.</p>
 *
 * <h3>No {@code bolder} / {@code lighter}</h3>
 *
 * <p>Both are defined relative to the <em>parent's computed weight</em>, which needs the inherited
 * value at compute time rather than at cascade time. With two faces they would also collapse to
 * "bold" and "normal" in almost every tree. Omitted rather than aliased, so they fail to parse
 * instead of quietly meaning something else.</p>
 */
public enum FontWeight {

    NORMAL,
    BOLD;

    /** Whether this weight selects the bold face — the only question the shaper can answer. */
    public boolean isBold() {
        return this == BOLD;
    }

    /**
     * The CSS numeric scale, resolved the way a two-face family resolves it.
     *
     * <p>600 is the boundary CSS Fonts 4 uses for "the bold face" when a family has no weight between
     * 400 and 700, which is every family this engine loads today.</p>
     */
    public static FontWeight ofNumeric(int value) {
        return value >= 600 ? BOLD : NORMAL;
    }
}
