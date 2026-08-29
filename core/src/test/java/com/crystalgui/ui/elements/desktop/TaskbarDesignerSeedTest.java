package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Opening the Taskbar Designer must not restyle the taskbar.
 *
 * <p>{@code seedFromCascade} ends by calling {@code applyGeometry}, which writes every geometry value
 * at IMPORTANT origin — so a field it does not seed is imposed on the bar the instant the tuner opens,
 * with no slider touched. The bar ships square and the radius field defaulted to 8, so the corners of a
 * full-width bar were rounded by the act of opening the panel that exists to show what is already on
 * screen: at each end the glass's arc cut away from the screen edge, the raw unblurred world showed
 * through the notch, and the {@code __edge__} hairline ran square across the top of it.</p>
 *
 * <p>The two halves are one test each because either alone passes against a wrong build: asserting the
 * bar stays square is equally satisfied by a designer that writes nothing at all, or by a probe that
 * cannot see a radius in the first place. The second test is that probe's positive control.</p>
 */
public class TaskbarDesignerSeedTest extends UiTestBase {

    private UIWindow window;

    @Before
    public void setUpDesktop() {
        Desktop.setAnimationsEnabled(false);
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        // A window, because the compositor takes up no space until one is open — and with no space the
        // taskbar has no box for anything here to measure a radius against.
        window.openWindow(new WindowFrame("Welcome"));
        settle();
    }

    @After
    public void restoreAnimations() {
        Desktop.setAnimationsEnabled(true);
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private Taskbar taskbar() {
        Taskbar bar = window.desktop().taskbar();
        assertNotNull("no taskbar on a desktop with a window open", bar);
        return bar;
    }

    /** The resolved top-left radius in px; null — nothing has written one — is a square corner. */
    private float radiusOf(UIElement element) {
        Object radius = element.getStyle().getComputed(BorderRadiusProperties.TOP_LEFT_X);
        return radius instanceof LengthPercent r ? r.resolve(element.getRuntimeCache().getWidth()) : 0f;
    }

    @Test
    public void openingTheTunerLeavesTheShippedBarSquare() {
        Taskbar bar = taskbar();
        assertEquals("the shipped bar has no radius", 0f, radiusOf(bar), 1e-4f);

        assertNotNull(TaskbarDesigner.open(window));
        settle();

        assertEquals("opening the tuner must not impose a radius the sheet never asked for",
                0f, radiusOf(bar), 1e-4f);
    }

    @Test
    public void openingTheTunerKeepsARadiusTheBarAlreadyHad() {
        Taskbar bar = taskbar();
        StyleGroup.importantPipeline(bar.getStyle().getGeneralGroup(), g -> g.borderRadius(8f));
        settle();
        // The positive control for the test above: the probe can see a radius when there is one, so a
        // zero there is the bar being square rather than the reader being blind.
        assertEquals("precondition: the bar is rounded", 8f, radiusOf(bar), 1e-3f);

        assertNotNull(TaskbarDesigner.open(window));
        settle();

        assertEquals("the tuner opens on what is on screen, so it must read the radius back",
                8f, radiusOf(bar), 1e-3f);
    }
}
