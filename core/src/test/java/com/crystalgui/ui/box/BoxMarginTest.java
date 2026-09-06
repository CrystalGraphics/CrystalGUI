package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>L3.2 / E6 — the fourth box-model edge.</b>
 *
 * <p>{@code border()} and {@code padding()} were read from the layout and {@code margin()} was not, so
 * anything drawing the box model drew three rects and had four to draw. Read from Taffy like the other
 * two, which is what makes it the RESOLVED margin rather than what the cascade asked for.</p>
 */
public class BoxMarginTest extends UiDocumentTestBase {

    @Test
    public void theResolvedMarginIsReadableOffTheBox() {
        UIElement element = new UIElement().layout(l -> l
                .width(40).height(20)
                .marginLeft(7).marginTop(5).marginRight(3).marginBottom(11));
        document.append(element);
        document.update(W, H);

        Box box = element.box();
        assertEquals(7f, box.margin().left, 0.01f);
        assertEquals(5f, box.margin().top, 0.01f);
        assertEquals(3f, box.margin().right, 0.01f);
        assertEquals(11f, box.margin().bottom, 0.01f);
    }

    /** Nothing declared is a real answer, and it is zero rather than absent. */
    @Test
    public void anElementWithNoMarginReportsZero() {
        UIElement element = new UIElement().layout(l -> l.width(40).height(20));
        document.append(element);
        document.update(W, H);

        Box box = element.box();
        assertEquals(0f, box.margin().left, 0.01f);
        assertEquals(0f, box.margin().bottom, 0.01f);
    }
}
