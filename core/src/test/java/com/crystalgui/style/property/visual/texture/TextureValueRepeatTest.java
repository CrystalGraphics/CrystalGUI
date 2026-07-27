package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiRepeat;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Keyword parsing for {@code sprite(...)}'s trailing tiling argument.
 *
 * <p>Tests the parser in isolation rather than through {@code StyleSheet.parse}, because actually
 * building a sprite calls {@code CgTextureManager} — CrystalGraphics is {@code compileOnly} for
 * this module, so it isn't on the test runtime classpath at all and any sprite construction throws
 * {@code NoClassDefFoundError}. The keyword disambiguation is the part with real logic anyway: it
 * has to return {@code null} for non-keywords so {@code parseSprite}'s type-sniffing loop can fall
 * through to the other argument forms.</p>
 */
public class TextureValueRepeatTest {

    @Test
    public void singleKeywordAppliesToBothAxes() {
        CgUiRepeat[] modes = TextureValue.parseRepeatPair("round");
        assertNotNull(modes);
        assertEquals(CgUiRepeat.ROUND, modes[0]);
        assertEquals(CgUiRepeat.ROUND, modes[1]);
    }

    @Test
    public void twoKeywordsSetAxesIndependently() {
        CgUiRepeat[] modes = TextureValue.parseRepeatPair("repeat space");
        assertNotNull(modes);
        assertEquals(CgUiRepeat.REPEAT, modes[0]);
        assertEquals(CgUiRepeat.SPACE, modes[1]);
    }

    @Test
    public void parsingIsCaseInsensitiveAndTolerantOfExtraWhitespace() {
        CgUiRepeat[] modes = TextureValue.parseRepeatPair("  STRETCH   Round  ");
        assertNotNull(modes);
        assertEquals(CgUiRepeat.STRETCH, modes[0]);
        assertEquals(CgUiRepeat.ROUND, modes[1]);
    }

    /** Must be null, not a throw or a default — parseSprite relies on null to try the next argument
     * form (a texture-size pair) before giving up. */
    @Test
    public void nonKeywordsReturnNullSoOtherArgFormsCanBeSniffed() {
        assertNull(TextureValue.parseRepeatPair("64 64"));
        assertNull(TextureValue.parseRepeatPair("#ff0000"));
        assertNull(TextureValue.parseRepeatPair("wobble"));
        assertNull(TextureValue.parseRepeatPair(null));
    }

    /** One bad keyword invalidates the whole pair rather than silently half-applying. */
    @Test
    public void aPairWithOneBadKeywordIsRejectedEntirely() {
        assertNull(TextureValue.parseRepeatPair("round wobble"));
        assertNull(TextureValue.parseRepeatPair("wobble round"));
    }

    @Test
    public void threeOrMoreKeywordsAreRejected() {
        assertNull(TextureValue.parseRepeatPair("round round round"));
    }
}
