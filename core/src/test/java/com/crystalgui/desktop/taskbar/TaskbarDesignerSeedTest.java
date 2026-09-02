package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;

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
public class TaskbarDesignerSeedTest extends UiDocumentTestBase {
    /**
     * ANIMATIONS OFF, unless a test turns them on for itself.
     *
     * <p>A window animation defers the thing it animates: `close()` destroys and `hide()`
     * detaches only once the flight has finished, so a test that asserts the state straight
     * after the gesture reads the state BEFORE it. Disabled, the continuation runs
     * synchronously, which is what lets every assertion here be immediate. The tests that are
     * ABOUT the animation enable it themselves and restore this in a finally.</p>
     */
    @Before
    public void quietAnimationsForTheFixture() {
        Desktop.setAnimationsEnabled(false);
    }

    /** AND PUT IT BACK. The flag is STATIC, so leaving it off leaks into every later test in the
     *  run -- a governance test that asks whether every shipped rule still matches something then
     *  finds `taskbar .__entry__.__animating__` matching nothing, because nothing animates. */
    @After
    public void restoreAnimationsAfterTheFixture() {
        Desktop.setAnimationsEnabled(true);
    }



    @Before
    public void setUpDesktop() {
        Desktop.setAnimationsEnabled(false);
        UINode root = new UINode().layout(l -> l.width(800).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        // A document, because the compositor takes up no space until one is open — and with no space the
        // taskbar has no box for anything here to measure a radius against.
        Desktop.of(document).addWindow(new WindowFrame("Welcome"));
        settle();
    }

    @After
    public void restoreAnimations() {
        Desktop.setAnimationsEnabled(true);
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    private Taskbar taskbar() {
        Taskbar bar = Desktop.of(document).taskbar();
        assertNotNull("no taskbar on a desktop with a document open", bar);
        return bar;
    }

    /** The resolved top-left radius in px; null — nothing has written one — is a square corner. */
    private float radiusOf(UINode element) {
        Object radius = element.getStyle().getComputed(BorderRadiusProperties.TOP_LEFT_X);
        return radius instanceof LengthPercent r ? r.resolve(element.box().width()) : 0f;
    }

    @Test
    public void openingTheTunerLeavesTheShippedBarSquare() {
        Taskbar bar = taskbar();
        assertEquals("the shipped bar has no radius", 0f, radiusOf(bar), 1e-4f);

        assertNotNull(TaskbarDesigner.open(document));
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

        assertNotNull(TaskbarDesigner.open(document));
        settle();

        assertEquals("the tuner opens on what is on screen, so it must read the radius back",
                8f, radiusOf(bar), 1e-3f);
    }
}
