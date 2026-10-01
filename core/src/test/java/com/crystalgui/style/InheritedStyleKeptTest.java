package com.crystalgui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * A parent's restyle reaches its children only through what they inherit. When nothing inherited moved, a child
 * keeps the same {@link ComputedStyle}, which is what leaves its box, its layout and everything under it alone.
 */
public class InheritedStyleKeptTest extends UiDocumentTestBase {

    @Test
    public void aParentChangeNothingInheritsKeepsTheChildsStyle() {
        UIElement parent = new UIElement();
        UIElement child = new UIElement();
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        ComputedStyle was = child.computedStyle();
        parent.generalStyle(g -> g.backgroundColor(0xFF00FF00));
        document.update(W, H);

        assertSame(was, child.computedStyle());
    }

    /** A re-match that lands on the values it already had is not a change: nothing downstream may relayout. */
    @Test
    public void aRematchLandingOnTheSameValuesKeepsTheStyle() {
        document.styles().addStylesheet(StyleSheet.parse(".a { background-color: #ff0000; } .b { background-color: #ff0000; }"));
        UIElement node = new UIElement();
        node.addClass("a");
        document.append(node);
        document.update(W, H);

        ComputedStyle was = node.computedStyle();
        node.removeClass("a");
        node.addClass("b");
        document.update(W, H);

        assertSame(was, node.computedStyle());
    }

    @Test
    public void anInheritedChangeStillReachesTheChild() {
        UIElement parent = new UIElement();
        UIElement child = new UIElement();
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        ComputedStyle was = child.computedStyle();
        parent.generalStyle(g -> g.color(0xFF123456));
        document.update(W, H);

        assertNotSame(was, child.computedStyle());
        assertEquals(0xFF123456, (int) child.computedStyle().get(StylePropertyRegistry.COLOR));
    }
}
