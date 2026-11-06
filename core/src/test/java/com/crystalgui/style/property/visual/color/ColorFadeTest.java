package com.crystalgui.style.property.visual.color;

import com.crystalgui.style.property.StylePropertyRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A colour fading to {@code transparent} loses alpha and nothing else: the highlight does not darken on its way out. */
public class ColorFadeTest {

    @Test
    public void fadingToTransparentKeepsTheColour() {
        int highlight = 0x4DFFFFFF;
        int transparent = 0x00000000;
        int half = StylePropertyRegistry.BACKGROUND_COLOR.getInterpolator().interpolate(highlight, transparent, 0.5f);
        assertEquals("the colour dimmed instead of only fading", 0xFF, (half >> 16) & 0xFF);
        assertEquals("alpha is half", Math.round(0x4D / 2f), half >>> 24);
    }

    @Test
    public void fadingInFromTransparentKeepsTheColourToo() {
        int half = StylePropertyRegistry.BACKGROUND_COLOR.getInterpolator().interpolate(0x00000000, 0x4DFFFFFF, 0.5f);
        assertEquals(0xFF, (half >> 16) & 0xFF);
    }
}
