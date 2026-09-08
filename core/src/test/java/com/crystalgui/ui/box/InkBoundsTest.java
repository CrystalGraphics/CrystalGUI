package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.testsupport.UiDocumentTestBase;
import dev.vfyjxf.taffy.style.TaffyPosition;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>What a box paints, as opposed to what it measures.</b>
 *
 * <p>Ink bounds size every layer: an {@code opacity} or a mask allocates an offscreen for exactly this
 * rectangle, clears it and composites it back. A box that reaches further than its ink says loses
 * whatever falls outside — and only inside a layer, which is the failure worth pinning. Everything
 * here is about the rectangle, never about pixels.</p>
 */
public class InkBoundsTest extends UiDocumentTestBase {

    private static void inkIs(UIElement element, float x0, float y0, float x1, float y1) {
        Box box = element.box();
        assertEquals("ink left", x0, box.inkX0(), 0.01f);
        assertEquals("ink top", y0, box.inkY0(), 0.01f);
        assertEquals("ink right", x1, box.inkX1(), 0.01f);
        assertEquals("ink bottom", y1, box.inkY1(), 0.01f);
    }

    /** With nothing declared, a box's ink is its border box, in world coordinates. */
    @Test
    public void aPlainBoxInksItsOwnBorderBox() {
        UIElement element = new UIElement().layout(l -> l.width(40).height(20));
        document.append(element);
        document.update(W, H);

        inkIs(element, 0f, 0f, 40f, 20f);
        assertTrue(element.box().hasInk());
    }

    /**
     * A child that hangs outside its parent grows the parent's ink.
     *
     * <p>This is the case a border box cannot answer, and the reason ink bounds are composed
     * bottom-up rather than read per box.</p>
     */
    @Test
    public void anOverflowingChildGrowsItsParent() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement child = new UIElement().layout(l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(30).top(10).width(60).height(60));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        inkIs(parent, 0f, 0f, 90f, 70f);
    }

    /** …unless the parent clips, in which case the overspill is exactly what {@code overflow} removes. */
    @Test
    public void aClippingParentTruncatesIt() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        parent.generalStyle(g -> g.overflow(Overflow.HIDDEN));
        UIElement child = new UIElement().layout(l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(30).top(10).width(60).height(60));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        inkIs(parent, 0f, 0f, 40f, 20f);
    }

    /**
     * An outline is ink and is not layout — CSS puts it outside the border box and takes no space for
     * it, so a layer sized to the border box would clip a focus ring off its own element.
     */
    @Test
    public void anOutlineGrowsTheBoxItRings() {
        UIElement element = new UIElement().layout(l -> l.width(40).height(20));
        element.generalStyle(g -> g.outlineWidth(LengthPercent.px(2f)).outlineOffset(LengthPercent.px(3f)));
        document.append(element);
        document.update(W, H);

        // The offset, plus the stroke that grows outward from it. @see BoxPainter#paintOutline
        inkIs(element, -5f, -5f, 45f, 25f);
    }

    /**
     * A widget that paints by hand says so, because nothing else can see it.
     *
     * @see UIElement#inkOverflow
     */
    @Test
    public void aDeclaredOverflowIsHonoured() {
        UIElement element = new HandPainted().layout(l -> l.width(40).height(20));
        document.append(element);
        document.update(W, H);

        inkIs(element, -4f, -4f, 44f, 24f);
    }

    /** And a declaration on a CHILD reaches the parent, like any other ink. */
    @Test
    public void aDeclaredOverflowReachesTheParent() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        parent.append(new HandPainted().layout(l -> l.width(40).height(20)));
        document.append(parent);
        document.update(W, H);

        inkIs(parent, -4f, -4f, 44f, 24f);
    }

    /** A box laid out to nothing paints nothing, which is what lets the painter skip it outright. */
    @Test
    public void aZeroSizedBoxHasNoInk() {
        UIElement element = new UIElement().layout(l -> l.width(0).height(0));
        document.append(element);
        document.update(W, H);

        assertFalse(element.box().hasInk());
    }

    private static final class HandPainted extends UIElement {
        @Override
        public InkOverflow inkOverflow() {
            return InkOverflow.uniform(4f);
        }
    }
}
