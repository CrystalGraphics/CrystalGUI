package com.crystalgui.style;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * Elements whose ancestor chains agree share one match in a drain; any difference a selector can see keeps them
 * apart, however far up the chain it is.
 */
public class StyleSharingTest extends UiDocumentTestBase {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private static int colourOf(UIElement node) {
        return node.computedStyle().get(StylePropertyRegistry.BACKGROUND_COLOR);
    }

    /** {@code root.kind > div > div > leaf}: the leaf's style depends on the class three levels up. */
    private static UIElement branch(UIElement under, String kind) {
        UIElement top = new UIElement().addClass(kind);
        UIElement middle = new UIElement();
        UIElement inner = new UIElement();
        UIElement leaf = new UIElement().addClass("leaf");
        top.append(middle);
        middle.append(inner);
        inner.append(leaf);
        under.append(top);
        return leaf;
    }

    @Test
    public void cousinsUnderDifferentAncestorsDoNotShare() {
        document.styles().addStylesheet(StyleSheet.parse(
                ".red .leaf { background-color: #ff0000; } .blue .leaf { background-color: #0000ff; }"));
        UIElement root = new UIElement();
        UIElement red = branch(root, "red");
        UIElement blue = branch(root, "blue");
        UIElement red2 = branch(root, "red");
        document.append(root);
        document.update(W, H);

        assertEquals(RED, colourOf(red));
        assertEquals(BLUE, colourOf(blue));
        assertEquals(RED, colourOf(red2));
    }

    @Test
    public void stateOnAnAncestorKeepsCousinsApart() {
        document.styles().addStylesheet(StyleSheet.parse(
                ".leaf { background-color: #0000ff; } :disabled .leaf { background-color: #ff0000; }"));
        UIElement root = new UIElement();
        UIElement on = branch(root, "same");
        UIElement off = branch(root, "same");
        ((UIElement) off.parent().parent().parent()).setEnabled(false);
        document.append(root);
        document.update(W, H);

        assertEquals(BLUE, colourOf(on));
        assertEquals(RED, colourOf(off));
    }
}
