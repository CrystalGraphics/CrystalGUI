package com.crystalgui.ui.dom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>The fixture twin, proven against the verbs a ported test will use</b> — {@code plan_m6.md} §2.3.
 *
 * <p>A fixture nobody has driven is a fixture that compiles. 164 test files are going to move onto
 * this one, so what it can actually do is asserted here rather than discovered at widget #3.</p>
 */
public class FixtureTwinTest extends UiDocumentTestBase {

    /** Build, lay out, read a settled box — the shape of every geometry test in the suite. */
    @Test
    public void buildingAndReadingGeometry() {
        UIElement panel = at("panel", 10f, 20f, 100f, 50f);
        document.append(panel);
        layoutOnly();

        assertEquals(10f, boxOf(panel).x(), 0.01f);
        assertEquals(20f, boxOf(panel).y(), 0.01f);
        assertEquals(100f, boxOf(panel).width(), 0.01f);
    }

    /**
     * Hit-testing works with NO PAINT having happened, which the old fixture could not offer.
     *
     * <p>The old engine's world matrices were written by {@code drawSubtree}, so a test that had not
     * painted read stale ones — the audit's §2, and the reason "clicks land where things were drawn"
     * was a promise kept by two caches agreeing rather than by construction.</p>
     */
    @Test
    public void hitTestingNeedsNoPaint() {
        UIElement panel = at("panel", 10f, 20f, 100f, 50f);
        document.append(panel);
        layoutOnly();

        assertSame(panel, hit(50f, 40f));
        // Outside it is the DOCUMENT, not null: the document's box is the viewport, so it is what a
        // click on bare background lands on -- the same answer the old engine's root element gave,
        // and what "clicking bare desktop deselects" is built on. Null means outside the SURFACE.
        assertSame(document, hit(500f, 400f));
    }

    /**
     * A press goes through the platform sink at a POINT — never dispatched at a node.
     *
     * <p>What makes focus, the hover chain and keymap resolution real: four separate old-engine rows
     * record a bug that {@code sendInputEvent} could not see, because dispatching at an element skips
     * every one of them.</p>
     */
    @Test
    public void aPressIsDeliveredThroughTheSinkAtAPoint() {
        UIElement button = at("button", 0f, 0f, 100f, 40f);
        document.append(button);
        layoutOnly();
        frame();

        List<String> got = new ArrayList<>();
        onTarget(button, MouseEvent.Down.class, (n, e) -> got.add("down"));
        onTarget(button, MouseEvent.Up.class, (n, e) -> got.add("up"));

        click(50f, 20f);
        assertEquals(List.of("down", "up"), got);
    }

    /** A key press reports whether anything consumed it, which is what a host acts on. */
    @Test
    public void aKeyPressReportsConsumption() {
        UIElement field = at("field", 0f, 0f, 100f, 20f);
        document.append(field);
        layoutOnly();
        frame();

        assertTrue("nothing focused, nothing consumed", !keyPress(CgKeyCodes.KEY_A));
    }

    /** The composed view crosses shadow boundaries; the class query does not. */
    @Test
    public void composedCrossesShadowAndTheQueryDoesNot() {
        UIElement host = new UIElement().setId("host");
        UIElement part = new UIElement().setId("part");
        part.addClass("marked");
        host.attachShadow().append(part);
        document.append(host);

        assertTrue("the composed walk sees it", composed(host).contains(part));
        assertEquals("the light query does not", List.of(), allWithClass(host, "marked"));
    }
}
