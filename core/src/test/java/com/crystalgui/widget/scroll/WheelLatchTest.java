package com.crystalgui.widget.scroll;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Input;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One spin of the wheel stays on the scroll view it started in; the next spin chains outward, and
 * {@code overscroll-behavior: contain} keeps it in for good. A list inside a panel, the pointer over the list.
 */
public class WheelLatchTest extends UiDocumentTestBase {

    private final long[] now = {10_000L};
    private ScrollerView outer;
    private ScrollerView inner;
    private int x;
    private int y;

    @Before
    public void listInAPanel() {
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.styleEngine().addStylesheet(StyleSheet.parse(
                ".contain { overscroll-behavior: contain; } scrollerview { scroll-behavior: auto; }"));
        document.input().useWheelClock(() -> now[0]);

        outer = new ScrollerView();
        outer.layout(l -> l.width(120).height(100).flexDirection(FlexDirection.COLUMN));
        inner = new ScrollerView();
        inner.layout(l -> l.width(120).height(60).flexShrink(0).flexDirection(FlexDirection.COLUMN));
        for (int i = 0; i < 4; i++) inner.append(new UIElement().layout(l -> l.width(120).height(40).flexShrink(0)));
        outer.append(inner);
        outer.append(new UIElement().layout(l -> l.width(120).height(300).flexShrink(0)));
        document.append(new UIElement().layout(l -> l.width(400).height(300)));
        document.children().get(0).append(outer);
        frame();
        frame();
        x = 20;
        y = 20;
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
    }

    private void spin(float notches) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, notches, -1L));
        for (int i = 0; i < 30; i++) frame();
    }

    @Test
    public void aSpinPastTheEndOfTheListStaysOnTheList() {
        for (int i = 0; i < 5; i++) spin(1f);
        assertEquals("the list is at its end", 100f, inner.scrollTop(), 0.5f);
        assertEquals("and the panel never moved", 0f, outer.scrollTop(), 0.5f);

        now[0] += Input.WHEEL_LATCH_TIMEOUT_MS + 1;
        spin(1f);
        assertTrue("a new spin chains to the panel", outer.scrollTop() > 0f);
    }

    @Test
    public void movingThePointerAfterAPauseEndsTheSpin() {
        for (int i = 0; i < 4; i++) spin(1f);
        now[0] += 200;
        x += 2;
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        spin(1f);
        assertTrue(outer.scrollTop() > 0f);
    }

    @Test
    public void containKeepsEveryNewSpinOnTheList() {
        inner.addClass("contain");
        frame();
        inner.scrollTo(0f, 100f);
        frame();
        for (int i = 0; i < 3; i++) {
            now[0] += Input.WHEEL_LATCH_TIMEOUT_MS + 1;
            spin(1f);
        }
        assertEquals(0f, outer.scrollTop(), 0.5f);
    }
}
