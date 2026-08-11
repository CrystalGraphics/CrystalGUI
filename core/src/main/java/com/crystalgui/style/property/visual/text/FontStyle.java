package com.crystalgui.style.property.visual.text;

/**
 * CSS-facing {@code font-style} — upright or slanted.
 *
 * <h3>{@code oblique} is kept as its own constant and resolves identically</h3>
 *
 * <p>CSS distinguishes them by where the slant comes from: {@code italic} asks for a separately drawn
 * cursive face, {@code oblique} asks for the upright face sheared. This engine synthesises both by
 * shearing, because {@code font-family} is a list of asset paths with no grammar for "and this one is
 * the italic face" — so today the two are the same picture.</p>
 *
 * <p>Kept apart anyway, rather than aliasing {@code oblique} onto {@link #ITALIC} at parse time,
 * because the distinction is real in the source and will matter the moment that grammar exists: at
 * that point {@code italic} should start selecting a loaded face and {@code oblique} should keep
 * shearing. Collapsing them now would silently lose which one the author asked for, and no amount of
 * later work recovers it. {@link #isItalic()} is what callers ask, so nothing downstream has to know
 * there are two.</p>
 */
public enum FontStyle {

    NORMAL,
    ITALIC,
    OBLIQUE;

    /** Whether the text is slanted at all — the only question the shaper can currently answer. */
    public boolean isItalic() {
        return this != NORMAL;
    }
}
