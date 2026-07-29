package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The window's root placement (its centring offset and resolved box).
 *
 * <p>{@code UIWindow.init} early-returns once the screen size stops changing, so everything it
 * computed was frozen after the first frame — including the root's size and {@code leftPos}. But
 * {@code init} runs <em>before</em> any stylesheet has been applied, so a CSS-sized root was measured
 * while still unstyled and stayed mis-positioned for the rest of the run.</p>
 *
 * <p>This is not a cosmetic offset: {@code UIElement.getLayoutX()} returns {@code getLeftPos()} for
 * the root and every other element's absolute position accumulates from it, so the whole tree moves.
 * That's why harness scenes had to size their roots in Java.</p>
 */
public class RootPlacementTest extends UiTestBase {

    private static final int SCREEN_W = 800, SCREEN_H = 600;
    /** uiScale defaults to 2, so logical space is half the physical screen. */
    private static final float LOGICAL_W = SCREEN_W / 2f, LOGICAL_H = SCREEN_H / 2f;

    /** The regression. A stylesheet sizes the root, and the stylesheet is only applied on the first
     * {@code calculateStyle} — i.e. strictly after {@code init} has already run and latched. */
    @Test
    public void cssSizedRootIsCentredCorrectly() {
        UIElement root = new UIElement();
        root.addClass("root");

        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.parse(".root { width: 200px; height: 100px; }"));
        window.init(SCREEN_W, SCREEN_H);
        frame(window);

        assertEquals("root width not picked up from CSS", 200f, window.getWidth(), 0.5f);
        assertEquals("root not centred horizontally", (LOGICAL_W - 200f) / 2f, window.getLeftPos(), 0.5f);
        assertEquals("root not centred vertically", (LOGICAL_H - 100f) / 2f, window.getTopPos(), 0.5f);
    }

    /** A Java-sized root must keep behaving exactly as before — this is the case that already worked
     * and must not regress. */
    @Test
    public void javaSizedRootIsCentredCorrectly() {
        UIElement root = new UIElement().layout(l -> l.width(200).height(100));

        UIWindow window = new UIWindow(Ui.of(root));
        window.init(SCREEN_W, SCREEN_H);
        frame(window);

        assertEquals((LOGICAL_W - 200f) / 2f, window.getLeftPos(), 0.5f);
        assertEquals((LOGICAL_H - 100f) / 2f, window.getTopPos(), 0.5f);
    }

    /** The offset must track a root that changes size at runtime, not just at startup. */
    @Test
    public void placementFollowsALaterSizeChange() {
        UIElement root = new UIElement().layout(l -> l.width(200).height(100));

        UIWindow window = new UIWindow(Ui.of(root));
        window.init(SCREEN_W, SCREEN_H);
        frame(window);
        float before = window.getLeftPos();

        root.layout(l -> l.width(100));
        frame(window);

        assertNotEquals("centring did not follow the root's new size", before, window.getLeftPos(), 0.5f);
        assertEquals((LOGICAL_W - 100f) / 2f, window.getLeftPos(), 0.5f);
    }

    /** The whole point: the root's offset is the origin every descendant's absolute position is
     * measured from, so a child must move with it. */
    @Test
    public void descendantsInheritTheCorrectedOrigin() {
        UIElement root = new UIElement();
        root.addClass("root");
        UIElement child = new UIElement().layout(l -> l.width(50).height(50));
        root.addChild(child);

        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.parse(".root { width: 200px; height: 100px; }"));
        window.init(SCREEN_W, SCREEN_H);
        frame(window);

        assertEquals("child's absolute X is not measured from the corrected root origin",
                window.getLeftPos(), child.getRuntimeCache().getX(), 0.5f);
    }

    /** An auto-sized root still centres on whatever the layout resolved it to. */
    @Test
    public void autoSizedRootCentresOnItsResolvedSize() {
        UIElement root = new UIElement();
        root.addChild(new UIElement().layout(l -> l.width(120).height(60)));

        UIWindow window = new UIWindow(Ui.of(root));
        window.init(SCREEN_W, SCREEN_H);
        frame(window);

        assertEquals(120f, window.getWidth(), 0.5f);
        assertEquals((LOGICAL_W - 120f) / 2f, window.getLeftPos(), 0.5f);
    }

    private static void frame(UIWindow window) {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }
}
