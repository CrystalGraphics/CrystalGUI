package com.crystalgui.app.uibuilder.inspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.PseudoClasses;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.WorldRect;

/**
 * <b>L3.8 and L3.9 — the overlay follows, and an edit on a live element lands.</b>
 */
public class InspectOverlayAndEditsTest extends UiDocumentTestBase {

    private UIElement boxed(float width) {
        UIElement element = new UIElement().layout(l -> l
                .width(width).height(50)
                .marginLeft(6).marginTop(4)
                .paddingLeft(3).paddingTop(3).paddingRight(3).paddingBottom(3));
        document.append(element);
        document.update(W, H);
        return element;
    }

    // ── L3.8 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void theOverlayMeasuresTheFourBoxesOutward() {
        UIElement target = boxed(120f);
        BoxModelOverlay overlay = BoxModelOverlay.over(document);
        overlay.follow(target);

        WorldRect border = overlay.borderBox();
        WorldRect margin = overlay.marginBox();
        WorldRect content = overlay.contentBox();
        assertNotNull(border);
        assertEquals("the border box is the element's own", 120f, border.width(), 0.01f);
        assertTrue("the margin box is outside it", margin.width() > border.width());
        assertTrue("and the content box inside", content.width() < border.width());
        assertEquals("padding taken off both sides", border.width() - 6f, content.width(), 0.01f);
    }

    /** A resize moves the element, and the overlay is re-measured off the new layout. */
    @Test
    public void theOverlayFollowsAResize() {
        UIElement target = boxed(120f);
        BoxModelOverlay overlay = BoxModelOverlay.over(document);
        overlay.follow(target);
        float before = overlay.borderBox().width();

        target.layout(l -> l.width(240f));
        document.update(W, H);
        overlay.measure();

        assertEquals("it re-read the new geometry", 240f, overlay.borderBox().width(), 0.01f);
        assertTrue(overlay.borderBox().width() > before);
    }

    /**
     * <b>A null box is an ordinary state, not an error.</b>
     *
     * <p>The thing being inspected is somebody else's live screen: it hides, freezes and is removed while
     * being looked at, and every one of those makes {@code box()} null.</p>
     */
    @Test
    public void anUnlaidOutTargetDrawsNothingRatherThanThrowing() {
        BoxModelOverlay overlay = BoxModelOverlay.over(document);

        overlay.follow(new UIElement());
        assertNull("nothing measured for an element in no document", overlay.borderBox());

        overlay.follow(null);
        overlay.measure();
        assertNull("and nothing for no target at all", overlay.borderBox());
    }

    // ── L3.9 ────────────────────────────────────────────────────────────────────────────────────

    /** Toggling {@code :hover} on the picked node restyles it, with no pointer anywhere near it. */
    @Test
    public void forcingHoverRestylesThePickedNode() {
        UIElement target = boxed(120f);
        target.addClass("probe");
        document.styleEngine().addStylesheet(StyleSheet.parse(".probe:hover { color: #FF0000 }"));
        document.update(W, H);
        assertFalse("not red to begin with", isRed(target));

        target.forceState(PseudoClasses.HOVER, true);
        document.update(W, H);

        assertTrue("the :hover rule applies", isRed(target));
    }

    @Test
    public void anInlineEditLandsOnTheLiveElementAndCanBeTakenBack() {
        UIElement target = boxed(120f);
        document.styleEngine().addStylesheet(StyleSheet.parse("* { color: #00FF00 }"));
        document.update(W, H);

        assertTrue("the sheet decides to begin with", LiveEdits.setInline(
                target, StylePropertyRegistry.COLOR, "#FF0000"));
        document.update(W, H);
        assertTrue("and the inline value wins", isRed(target));
        assertTrue(LiveEdits.hasInline(target, StylePropertyRegistry.COLOR));

        LiveEdits.clearInline(target, StylePropertyRegistry.COLOR);
        document.update(W, H);
        assertFalse("cleared, so the sheet decides again", isRed(target));
    }

    /** A value that does not parse changes nothing — clearing the property instead would look like the
     * edit worked and the value was empty. */
    @Test
    public void aMalformedEditIsRefusedRatherThanApplied() {
        UIElement target = boxed(120f);
        LiveEdits.setInline(target, StylePropertyRegistry.COLOR, "#FF0000");
        document.update(W, H);

        assertFalse("refused", LiveEdits.setInline(
                target, StylePropertyRegistry.COLOR, "not a colour"));
        document.update(W, H);
        assertTrue("and the last good value is still there", isRed(target));
    }

    private static boolean isRed(UIElement element) {
        Integer colour = element.getStyle().getComputed(StylePropertyRegistry.COLOR);
        return colour != null && colour == 0xFFFF0000;
    }
}
