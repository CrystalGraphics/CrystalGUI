package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code background: none} — the way to turn a paint layer off.
 *
 * <p>Only the keyword branch is exercised here. Every other value form ends in a sprite or texture
 * construction that reaches {@code CgTextureManager}, and CrystalGraphics is {@code compileOnly} for
 * this module, so it isn't on the test runtime classpath at all — see {@link TextureValueRepeatTest}
 * for the same constraint.</p>
 */
public class TextureNoneKeywordTest {

    @Test
    public void noneResolvesToTheEmptyDrawable() {
        assertSame(CgUiDrawable.EMPTY, TextureValue.parseDrawable("none"));
    }

    /** LDLib2's LSS spells it {@code empty}; accepted so the two dialects read alike. */
    @Test
    public void emptyIsAcceptedAsAnAlias() {
        assertSame(CgUiDrawable.EMPTY, TextureValue.parseDrawable("empty"));
    }

    @Test
    public void theKeywordIsCaseInsensitiveAndTrimmed() {
        assertSame(CgUiDrawable.EMPTY, TextureValue.parseDrawable("  NONE  "));
    }

    /**
     * The distinction that makes this worth a keyword at all: {@code null} is how the parser reports
     * a FAILURE. If "nothing" also returned null, a typo would be indistinguishable from a
     * deliberate blank, and neither could be warned about.
     */
    @Test
    public void anUnrecognisedValueStillFailsRatherThanBlanking() {
        assertNull(TextureValue.parseDrawable("nonesuch"));
        assertNull(TextureValue.parseDrawable("emptyish"));
        assertNull(TextureValue.parseDrawable("not-a-value"));
    }
}
