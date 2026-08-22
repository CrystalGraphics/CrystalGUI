package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A minimise travels.</b>
 *
 * <p>In this package rather than beside the other window tests, because the destination is not visible
 * from outside it: a minimise <em>starts</em> at identity and only reaches the taskbar 400ms later, so
 * neither the computed transform nor any frame of it can answer "does this go anywhere". Asking the
 * animator what it is aiming at is the only check that can.</p>
 *
 * <p>It regressed silently once and the report was precise — <em>"the minimise doesn't move it towards
 * the taskbar, it just fades it out"</em>. The cause was a fallback: with no taskbar button to aim at,
 * the animation gave up on the movement and played a plain close instead. Which is exactly backwards,
 * because the entire information content of a minimise animation is <b>where the window went</b>. GNOME
 * hits the same case and still aims at a corner of the monitor rather than fading in place.</p>
 */
public class WindowMinimizeTravelTest extends UiTestBase {

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

    /** How far a transform moves the thing it is applied to, in logical pixels. */
    private static float travelOf(UITransform transform) {
        float x = 0f;
        float y = 0f;
        for (UITransform.Op op : transform.ops()) {
            if (op.kind() == UITransform.Kind.TRANSLATE) {
                x += op.lx().value;
                y += op.ly().value;
            }
        }
        return (float) Math.hypot(x, y);
    }

    @Test
    public void minimisingAimsSomewhereOtherThanWhereTheWindowIs() {
        WindowFrame frame = window.openWindow(new WindowFrame("Travelling"));
        frame.resizeTo(200, 140).moveTo(40, 30);
        for (int pass = 0; pass < 3; pass++) window.updateWithoutPainting();

        frame.playMinimizeAnimation(() -> { });

        UITransform target = frame.animationTarget();
        assertNotNull("no animation started at all", target);
        assertTrue("the minimise goes nowhere -- it is a fade wearing a minimise's name",
                travelOf(target) > 1f);
    }
}
