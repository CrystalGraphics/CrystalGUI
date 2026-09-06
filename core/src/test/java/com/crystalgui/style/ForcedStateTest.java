package com.crystalgui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>L3.1 / E5 — a pseudo-state can be forced, so a hover rule can be read at leisure.</b>
 *
 * <p>What a devtools {@code :hov} panel does. Without it a hover rule can only be seen while a pointer is
 * on the element, which is exactly when nobody can look at the inspector describing it.</p>
 */
public class ForcedStateTest extends UiDocumentTestBase {

    private static final int RED = 0xFFFF0000;

    private UIElement probe;

    private UIElement open() {
        probe = new UIElement().layout(l -> l.width(40).height(20));
        probe.addClass("probe");
        document.append(probe);
        document.styleEngine().addStylesheet(StyleSheet.parse(".probe:hover { color: #FF0000 }"));
        document.update(W, H);
        return probe;
    }

    @Test
    public void aHoverRuleAppliesWithNoPointer() {
        open();
        assertFalse("nothing is hovering it", probe.isHovered());
        assertFalse("and no rule has applied", RED == colour());

        probe.forceState(PseudoClasses.HOVER, true);
        document.update(W, H);

        assertEquals("the :hover rule applies anyway", RED, colour());
        assertFalse("and the real state was never touched -- only what the cascade is told",
                probe.isHovered());
    }

    @Test
    public void nullClearsIt() {
        open();
        probe.forceState(PseudoClasses.HOVER, true);
        document.update(W, H);
        assertEquals(RED, colour());

        probe.forceState(PseudoClasses.HOVER, null);
        document.update(W, H);

        assertNull("the override is gone, not set to false", probe.forcedState(PseudoClasses.HOVER));
        assertFalse("and the pointer decides again", RED == colour());
    }

    /**
     * <b>FALSE is not the same as null</b> — it forces the state OFF, which is what makes a hover rule
     * inspectable in both directions while a pointer really is on the element.
     */
    @Test
    public void falseForcesTheStateOffAgainstTheRealOne() {
        open();
        probe.setHovered(true);
        document.update(W, H);
        assertEquals("really hovered, so the rule applies", RED, colour());

        probe.forceState(PseudoClasses.HOVER, false);
        document.update(W, H);

        assertTrue("the element is still hovered", probe.isHovered());
        assertFalse("but the cascade is told otherwise", RED == colour());
    }

    private int colour() {
        Integer value = probe.getStyle().getComputed(StylePropertyRegistry.COLOR);
        return value == null ? 0 : value;
    }
}
