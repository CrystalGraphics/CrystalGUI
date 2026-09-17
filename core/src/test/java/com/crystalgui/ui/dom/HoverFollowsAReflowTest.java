package com.crystalgui.ui.dom;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;

import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * <b>A reflow under a still pointer restyles {@code :hover} in the frame it happens.</b>
 *
 * <p>The hover is diffed after the cascade has run, so without a second pass the element the pointer left kept its
 * hover look for a frame and the one now under it lacked it — a virtualised tree's highlight jumped to a recycled
 * row and back on every fold.</p>
 */
public class HoverFollowsAReflowTest extends UiDocumentTestBase {

    private static final int PLAIN = 0xFF000000;
    private static final int HOVERED = 0xFFFF0000;

    @Test
    public void theElementMovedUnderThePointerIsHoveredInTheSameFrame() {
        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        UIElement top = new UIElement().addClass("__row__").layout(l -> l.width(200).height(20));
        UIElement below = new UIElement().addClass("__row__").layout(l -> l.width(200).height(20));
        top.setHitTest(true);
        below.setHitTest(true);
        root.append(top);
        root.append(below);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse(
                ".__row__ { background-color: #000000; } .__row__:hover { background-color: #FF0000; }"));
        frame();
        move(100, 10);
        frame();
        assertEquals(HOVERED, colourOf(top));

        top.layout(l -> l.display(TaffyDisplay.NONE));
        frame();
        assertEquals("the row that moved under the pointer is lit", HOVERED, colourOf(below));
    }

    private static int colourOf(UIElement element) {
        Integer value = element.getStyle().getComputed(StylePropertyRegistry.BACKGROUND_COLOR);
        return value == null ? PLAIN : value;
    }
}
