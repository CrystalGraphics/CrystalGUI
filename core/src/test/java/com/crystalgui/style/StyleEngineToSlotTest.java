package com.crystalgui.style;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.StyleRule;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link StyleEngine#toSlot} is a pure static method with no {@code UIWindow} dependency, so it's
 * testable directly despite {@code StyleEngine} itself requiring a window to construct.
 */
public class StyleEngineToSlotTest {

    @Test
    public void malformedDeclarationValueIsSkippedNotStoredAsNull() {
        var decl = new StyleRule.Declaration(StylePropertyRegistry.COLOR, new ColorValue("notacolor"), false);

        var slot = StyleEngine.toSlot(decl, StyleOrigin.STYLESHEET, 10, 0);

        assertNull("a malformed value must never become a cascade-winning slot", slot);
    }

    @Test
    public void wellFormedDeclarationValueProducesARealSlot() {
        var decl = new StyleRule.Declaration(StylePropertyRegistry.COLOR, new ColorValue("#FF0000"), false);

        var slot = StyleEngine.toSlot(decl, StyleOrigin.STYLESHEET, 10, 0);

        assertNotNull(slot);
        assertEquals((Integer) 0xFFFF0000, slot.value());
    }

    @Test
    public void importantFlagIsIndependentOfParseSuccess() {
        var decl = new StyleRule.Declaration(StylePropertyRegistry.COLOR, new ColorValue("notacolor"), true);

        assertTrue(decl.important());
        assertNull(StyleEngine.toSlot(decl, StyleOrigin.IMPORTANT, 10, 0));
    }
}
