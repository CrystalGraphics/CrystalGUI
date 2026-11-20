package com.crystalgui.style.property.visual.backdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.paint.effect.CgFilter;

/** backdrop-filter reads CSS's chain in order, the glass past it, and writes what it read. */
public class BackdropFilterValueTest {

    @Test
    public void cssFunctionsInTheOrderWritten() {
        CgBackdropFilter f = BackdropFilterValue.parse("grayscale(1) contrast(1.2) blur(8px)");
        assertEquals(CgBackdropFilter.of(CgFilter.grayscale(1f).then(CgFilter.contrast(1.2f)).then(CgFilter.blur(8f))), f);
        assertEquals(CgBackdropFilter.Lens.NONE, f.lens());
    }

    @Test
    public void theTaskbarsDeclarationReadsBackAsWritten() {
        String css = "blur(8px) saturate(1.25) luminosity(0.3) tint(#20202013) noise(0.02) fallback(#2B2D30)";
        CgBackdropFilter taskbar = BackdropFilterValue.parse(css);
        assertEquals(8f, taskbar.blur(), 0f);
        assertEquals(0.3f, taskbar.luminosity(), 0f);
        assertEquals(0.02f, taskbar.lens().grain(), 0f);
        assertEquals(taskbar, BackdropFilterValue.parse(BackdropFilterValue.write(taskbar)));
    }

    @Test
    public void noneAndNothing() {
        assertSame(CgBackdropFilter.NONE, BackdropFilterValue.parse("none"));
        assertNull(BackdropFilterValue.parse("wobble(3)"));
    }
}
