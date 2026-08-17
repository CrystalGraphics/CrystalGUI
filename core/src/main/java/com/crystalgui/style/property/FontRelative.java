package com.crystalgui.style.property;

/**
 * A parsed value that is a multiple of <b>the element's own font size</b> — CSS's {@code em}.
 *
 * <h3>Why this cannot be resolved where every other unit is</h3>
 *
 * <p>A {@link StyleValue} is parsed once and cached, and the same instance is shared by every element a
 * rule matches — that sharing is what makes a stylesheet cheap. An {@code em} has no answer at that
 * point: it is a different number of pixels on every element, and a different number again the moment
 * anything changes a font size. So the unit is recorded at parse time and resolved per element, in
 * {@code StyleEngine.rematch}, which is the first place both the declaration and an element are in hand.</p>
 *
 * <h3>What it is for</h3>
 *
 * <p>Everything in this engine that wants to stay proportionate to text and currently cannot. The
 * editor's gutter is the worst case and the reason this exists: its padding is authored in CSS, its
 * digits are measured text, and zooming grew one and not the other — so the gutter's proportions visibly
 * came apart from the code. {@code TextEditor.gutterMetric} worked around it by caching the font size it
 * first ever saw and scaling every metric by the ratio since, which is an {@code em} with no name and no
 * way for a sheet to opt out of. {@code ZoomIndicatorPart}'s three multipliers are the same thing written
 * as Java arithmetic.</p>
 *
 * <h3>The reference size, and when it is used</h3>
 *
 * <p>{@link #compute()} on a font-relative value answers against {@link #REFERENCE_FONT_SIZE} rather than
 * failing, so a consumer that never resolves — serialization, a test reading a declaration directly —
 * gets a sane number instead of one that is silently a tenth of what it should be. It is the size
 * {@code ua/core.css}'s universal rule gives every element, so for an unstyled element it is also the
 * right answer.</p>
 */
public interface FontRelative<T> {

    /**
     * The font size an unresolved {@code em} is computed against.
     *
     * <p>{@code * { font-size: 10 }} in {@code ua/core.css} — see the type note. Deliberately the same
     * number rather than a private constant, because the two being different would make an unresolved
     * value wrong in a way nothing would report.</p>
     */
    float REFERENCE_FONT_SIZE = 10f;

    /**
     * The {@code em} multiple in {@code raw}, or {@code NaN} if it is not an {@code em} length.
     *
     * <p>Here rather than in either parser because both need it and neither owns the unit. It has to be
     * tested <b>before</b> the {@code px} suffix and before the bare-number fallback in each: {@code px}
     * would never be reached is not the problem — {@code "1.5em"} simply is not a number, so the
     * fallback throws and the whole declaration is dropped with a warning about a value that is perfectly
     * well formed.</p>
     *
     * <p>{@code em} and not {@code rem}. A root-relative unit needs a root font size, and this engine's
     * universal {@code * { font-size: 10 }} means every element already has one — so {@code rem} would be
     * a second name for a constant rather than a second unit.</p>
     */
    static float multipleIn(String raw) {
        if (raw == null) return Float.NaN;
        String trimmed = raw.trim().toLowerCase();
        if (!trimmed.endsWith("em") || trimmed.length() <= 2) return Float.NaN;
        try {
            return Float.parseFloat(trimmed.substring(0, trimmed.length() - 2).trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** Whether this value was authored in {@code em}. False for every ordinary length. */
    boolean isFontRelative();

    /**
     * This value in pixels against {@code fontSize}.
     *
     * <p>Must be called only when {@link #isFontRelative()} — an ordinary length has nothing to resolve
     * and answering anyway would let a caller multiply a px value by a font size.</p>
     */
    T resolveAgainst(float fontSize);
}
