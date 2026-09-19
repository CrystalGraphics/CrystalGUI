package com.crystalgui.app.uibuilder.inspect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A write is redundant when it settles to what the element settles to without it</b> — never by what is displayed,
 * which on the frame of the write is the old value of any property with a {@code transition}.
 */
public class DropIfRedundantTest extends UiDocumentTestBase {

    @Test
    public void anEditToATransitioningPropertyIsKept() {
        UIElement node = new UIElement();
        document.append(node);
        LiveEdits.setInline(node, StylePropertyRegistry.TRANSITION, "transform 120ms linear");
        LiveEdits.setInline(node, StylePropertyRegistry.TRANSFORM, "translate(10px, 0px)");
        frame();

        LiveEdits.setInline(node, StylePropertyRegistry.TRANSFORM, "translate(10px, 0px) rotate(45deg)");
        assertFalse("a real edit read as redundant", LiveEdits.dropIfRedundant(node, StylePropertyRegistry.TRANSFORM));
        assertTrue(LiveEdits.hasInline(node, StylePropertyRegistry.TRANSFORM));
    }

    @Test
    public void aValueTheElementHasAnywayIsDropped() {
        UIElement node = new UIElement();
        document.append(node);
        LiveEdits.setInline(node, StylePropertyRegistry.OPACITY, "1");
        assertTrue(LiveEdits.dropIfRedundant(node, StylePropertyRegistry.OPACITY));
        assertFalse(LiveEdits.hasInline(node, StylePropertyRegistry.OPACITY));
    }
}
