package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A maximise animates through LAYOUT, never through a transform.</b>
 *
 * <p>It is the one window animation that is not a transform, and the reason is that a size change reflows
 * the window's content. FLIP — jump layout to the destination, apply the inverse transform, ease it away —
 * draws the <em>destination's</em> layout at the <em>source's</em> geometry: restoring a maximised window
 * reflowed its text for a 600px-wide window and then drew that three times magnified, so the animation
 * opened on a frame of enormous text. Reported as <em>"it eases back out to the original size, it's just
 * that the animation starts super scaled in"</em>.</p>
 *
 * <p>Animating the layout instead cannot be visually wrong — every frame is a correctly laid-out window at
 * an intermediate size. The cost is a reflow per frame, which this engine already pays whenever anyone
 * drags a window's resize handle.</p>
 */
public class WindowResizeAnimationTest extends UiTestBase {

    private UIWindow window;

    @Before
    public void build() {
        Desktop.setAnimationsEnabled(true);
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        window.updateWithoutPainting();
    }

    @After
    public void restoreDefault() {
        Desktop.setAnimationsEnabled(false);
    }

    private WindowFrame settledWindow() {
        WindowFrame frame = window.openWindow(new WindowFrame("Sized"));
        frame.resizeTo(200, 140).moveTo(40, 30);
        for (int pass = 0; pass < 3; pass++) window.updateWithoutPainting();
        return frame;
    }

    /**
     * <b>It runs, and it is not a transform that runs.</b>
     *
     * <p>{@code animationTarget} answers only for the transform driver, so a null there while
     * {@code isAnimating} is true is precisely "a size change is playing, and not by scaling a surface".
     * That pair is what would fail the day somebody reintroduces FLIP here.</p>
     */
    @Test
    public void maximisingAnimatesWithoutATransform() {
        WindowFrame frame = settledWindow();

        frame.maximize();

        assertTrue("no maximise animation started at all", frame.isAnimating());
        assertNull("the maximise is animating a TRANSFORM -- that magnifies the reflowed content",
                frame.animationTarget());
    }

    /**
     * <b>It starts at the size the window already was.</b>
     *
     * <p>The observable half of the same thing: layout must be at the SOURCE rect on the first frame, not
     * at the destination. Under FLIP it was the other way round — layout jumped to full screen (or to the
     * restored rect) immediately, and only the drawn scale came back — which is what put the reflow a
     * whole animation ahead of the picture.</p>
     *
     * <p>Time-independent in the direction that matters: the driver writes its start rect in its
     * constructor, and the handful of microseconds a test frame takes is nothing against 250ms, so the
     * measured width stays near the window's own 200 rather than the work area's 400.</p>
     */
    @Test
    public void maximisingStartsAtTheWindowsOwnSize() {
        WindowFrame frame = settledWindow();

        frame.maximize();
        window.updateWithoutPainting();

        float width = frame.getRuntimeCache().getWidth();
        assertTrue("layout jumped to the destination -- the content is reflowed a whole animation early,"
                + " which is what the transform then had to magnify back (measured " + width + ")",
                width < 300f);
    }
}
