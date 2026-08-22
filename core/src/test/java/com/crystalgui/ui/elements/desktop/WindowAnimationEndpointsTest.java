package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * <b>Every window animation must actually LERP between its endpoints.</b>
 *
 * <h3>The bug this exists for</h3>
 *
 * <p>CSS interpolates two transforms component-wise only when their function lists line up, and this
 * engine implements that rule exactly: {@code TransformProperty.interpolate} opens with
 * {@code if (a.size() != b.size()) return snap(...)}, where {@code snap} is the binary rule
 * — {@code t < 0.5 ? from : to}.</p>
 *
 * <p>{@link UITransform#IDENTITY} is an <b>empty function list</b>, so it lines up with nothing. Every
 * animation that used it as its resting end therefore did not interpolate at all: it sat at its start
 * value until the halfway point and jumped, in one step. That produced four different-looking complaints
 * with one cause — a maximise that "does nothing significant", a close that is "choppy, I can see the
 * individual frames", and a minimise that "just fades in place", the last because the jump happened at
 * {@code t = 0.5} and {@code OUT_EXPO} had already taken opacity to about 0.03 by then.</p>
 *
 * <p>It was invisible to every other kind of test. The durations were right, the targets were right, the
 * driver ran every frame, {@code isAnimating} was true, and dragging a window — which writes
 * {@code left}/{@code top} rather than a transform — stayed perfectly smooth throughout.</p>
 *
 * <h3>Why it asks at t = 0.25</h3>
 *
 * <p>Because that is where the two behaviours differ and nothing else does. A real interpolation returns
 * a quarter of the way along; a snap returns the start value <em>exactly</em>. Asking at the midpoint or
 * the ends would agree either way.</p>
 */
public class WindowAnimationEndpointsTest extends UiTestBase {

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
        WindowFrame frame = window.openWindow(new WindowFrame("Endpoints"));
        frame.resizeTo(200, 140).moveTo(40, 30);
        for (int pass = 0; pass < 3; pass++) window.updateWithoutPainting();
        return frame;
    }

    /** Fails when the running animation's two ends snap instead of interpolating. */
    private void assertLerps(WindowFrame frame, String gesture) {
        UITransform from = frame.animationStart();
        UITransform to = frame.animationTarget();
        assertNotNull(gesture + ": no animation started at all", from);
        assertNotNull(gesture + ": no animation started at all", to);

        UITransform quarter = StylePropertyRegistry.TRANSFORM.getInterpolator().interpolate(from, to, 0.25f);
        assertNotEquals(gesture + ": the transform SNAPS -- its two ends have different function lists, "
                + "so it holds still and then jumps at the halfway point", from, quarter);
    }

    @Test
    public void openingLerps() {
        WindowFrame frame = window.openWindow(new WindowFrame("Endpoints"));
        frame.resizeTo(200, 140);
        assertLerps(frame, "open");
    }

    @Test
    public void closingLerps() {
        WindowFrame frame = settledWindow();
        frame.playCloseAnimation(() -> { });
        assertLerps(frame, "close");
    }

    @Test
    public void minimisingLerps() {
        WindowFrame frame = settledWindow();
        frame.playMinimizeAnimation(() -> { });
        assertLerps(frame, "minimise");
    }

    // A maximise is deliberately absent: it no longer animates a transform at all. It drives LAYOUT,
    // for the reason WindowGeometryAnimation gives -- a size change reflows the window's content, so a
    // transform would draw the destination's layout at the source's geometry.
}
