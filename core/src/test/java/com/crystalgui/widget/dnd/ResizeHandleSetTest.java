package com.crystalgui.widget.dnd;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.Widgets;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * <b>Which grab handles a {@code resize} value grows, and which one wears the grip.</b>
 *
 * <p>{@code Resizer.Handle.appliesTo} has always stated the rule — a handle exists only if every axis
 * it touches is resizable, so {@code horizontal} yields side edges and no corners — and nothing
 * applied it when BUILDING the set. Every resizable node got the same three handles, and the corner
 * is the one that draws the grip, so a horizontal-only box showed exactly one grabber and that
 * grabber was the one {@code applyResize} refuses. It reads as the box not being resizable at all,
 * which is how it was reported.</p>
 *
 * <p>Asserted on the SET rather than by dragging, because the drag half was already correct: the
 * refusal in {@code applyResize} is what made the dead handle visible instead of wrong.</p>
 */
public class ResizeHandleSetTest extends UiDocumentTestBase {

    private UIElement box;

    @Before
    public void setUp() {
        new Widgets().register();
        box = new UIElement().layout(l -> l.width(100).height(60));
        document.append(box);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    private EnumSet<Resizer.Handle> handlesAfter(String css) {
        document.styleEngine().addStylesheet(StyleSheet.parse(css));
        frame();
        EnumSet<Resizer.Handle> found = EnumSet.noneOf(Resizer.Handle.class);
        for (UIElement child : box.children()) {
            if (child instanceof Resizer resizer) found.add(resizer.handle());
        }
        return found;
    }

    /** In flow, so no leading handles — CSS's own default grabber set. */
    @Test
    public void bothGrowsTheTrailingEdgesAndTheCorner() {
        box.setId("b");
        assertEquals(EnumSet.of(Resizer.Handle.BOTTOM, Resizer.Handle.RIGHT, Resizer.Handle.BOTTOM_RIGHT),
                handlesAfter("#b { resize: both; }"));
    }

    /**
     * <b>The corner survives a single-axis mode</b>, as it does in a browser: a {@code textarea} with
     * {@code resize: horizontal} still shows the bottom-right grabber and changes width alone. It is
     * also the only handle the sheet draws, so dropping it leaves the box resizable with nothing on
     * screen saying so — and painting the grip on the full-length edge strip instead reads as a
     * scrollbar. What the mode constrains is the DRAG, not which handles exist.
     */
    @Test
    public void horizontalKeepsTheRightEdgeAndTheCornerButNotTheBottom() {
        box.setId("h");
        assertEquals(EnumSet.of(Resizer.Handle.RIGHT, Resizer.Handle.BOTTOM_RIGHT),
                handlesAfter("#h { resize: horizontal; }"));
    }

    @Test
    public void verticalKeepsTheBottomEdgeAndTheCornerButNotTheRight() {
        box.setId("v");
        assertEquals(EnumSet.of(Resizer.Handle.BOTTOM, Resizer.Handle.BOTTOM_RIGHT),
                handlesAfter("#v { resize: vertical; }"));
    }

    @Test
    public void noneGrowsNothing() {
        box.setId("n");
        assertTrue(handlesAfter("#n { resize: none; }").isEmpty());
    }

    /** <b>The grip exists in every resizable mode</b>, because the corner does. */
    @Test
    public void theCornerGripExistsInEveryResizableMode() {
        for (String mode : new String[]{"both", "horizontal", "vertical"}) {
            UIElement subject = new UIElement().layout(l -> l.width(100).height(60));
            subject.setId("g" + mode);
            document.append(subject);
            document.styleEngine().addStylesheet(StyleSheet.parse("#g" + mode + " { resize: " + mode + "; }"));
            frame();

            boolean corner = false;
            for (UIElement child : subject.children()) {
                if (child instanceof Resizer r && r.handle() == Resizer.Handle.BOTTOM_RIGHT) corner = true;
            }
            assertTrue("resize: " + mode + " must keep the one handle the sheet draws", corner);
        }
    }

    /**
     * <b>A mode CHANGE re-derives the set.</b> The installer compared whether any handle was present,
     * which is unchanged by `both` becoming `horizontal` — so the stale corner survived, which is the
     * same dead grabber arriving a different way.
     */
    @Test
    public void narrowingTheModeDropsTheHandlesItForbids() {
        box.setId("c");
        assertTrue(handlesAfter("#c { resize: both; }").contains(Resizer.Handle.BOTTOM_RIGHT));

        assertEquals(EnumSet.of(Resizer.Handle.RIGHT, Resizer.Handle.BOTTOM_RIGHT),
                handlesAfter("#c { resize: horizontal !important; }"));
    }
}
