package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.stacking.Isolation;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>A box is hit in CSS's stacking order</b> (CSS 2.1 Appendix E), which the painter draws in: a stacking context's
 * negative {@code z-index} boxes, then its normal flow, then its {@code auto}/{@code 0} and positive boxes, and hit
 * testing the reverse. Two rows: {@code row1} holds {@code save}, and {@code row2} comes after it.
 */
public class StackingOrderTest extends UiDocumentTestBase {

    /** Inside row2, and inside save once save is moved down into it. */
    private static final float X = 40f, Y = 55f;

    private UIElement row1;
    private UIElement row2;
    private UIElement save;

    @Before
    public void twoRows() {
        UIElement page = new UIElement().layout(l -> l.width(200).height(200).flexDirection(FlexDirection.COLUMN));
        row1 = new UIElement().layout(l -> l.width(200).height(40));
        row2 = new UIElement().layout(l -> l.width(200).height(40));
        save = new UIElement().layout(l -> l.width(80).height(30));
        row1.append(save);
        page.append(row1, row2);
        document.append(page);
        document.update(W, H);
    }

    private UIElement at() {
        document.update(W, H);
        Box box = document.boxes().hitTest(X, Y);
        return box == null ? null : box.node();
    }

    private void moveSaveIntoRow2() {
        save.generalStyle(g -> g.transform(Transform.translate(0f, 45f)));
    }

    @Test
    public void aTransformedBoxTakesThePointerOverTheRowAfterIt() {
        assertEquals(row2, at());
        moveSaveIntoRow2();
        assertEquals("a transform is a stacking context, painted over the normal flow after it", save, at());
        Box picked = document.boxes().pick(X, Y, ignored -> false);
        assertEquals("and an editor's pick, which reaches past hit-test, finds it the same way", save, picked.node());
    }

    @Test
    public void aPositionedBoxRisesOutOfARowThatIsNotAContext() {
        save.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(45));
        assertEquals("positioned, it is painted after its context's normal flow", save, at());
    }

    /** A {@code z-index} of 0 is a context and {@code auto} is not; what a context holds stays inside it. */
    @Test
    public void aContextKeepsWhatItHoldsAndAutoDoesNot() {
        moveSaveIntoRow2();
        row2.generalStyle(g -> g.zIndex(0));
        assertEquals("row2 is a context after row1's save in tree order, so over it", row2, at());

        save.generalStyle(g -> g.zIndex(1));
        assertEquals("row1 is `auto`, so save's 1 is ordered among the page's boxes: over row2's 0", save, at());

        row1.generalStyle(g -> g.zIndex(0));
        assertEquals("row1 at 0 holds save inside it, and row2 at 0 comes after", row2, at());
    }

    @Test
    public void aLiftedBoxIsStillClippedByTheBoxesItRoseOutOf() {
        moveSaveIntoRow2();
        row1.generalStyle(g -> g.overflow(Overflow.HIDDEN));
        assertEquals("row1 clips it away below its own edge", row2, at());
    }

    @Test
    public void aLiftedBoxIsNotHitUnderABoxWithHitTestingOff() {
        moveSaveIntoRow2();
        row1.set(Attribute.HIT_TEST, false);
        assertEquals("hit-test is subtree-wide, whichever list the box is painted from", row2, at());
    }

    /** The top layer is painted after the document, over any {@code z-index} in it: a popup over a raised window. */
    @Test
    public void thePromotedAreOverAnyZIndexInTheDocument() {
        UIElement raised = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .width(200).height(200));
        raised.generalStyle(g -> g.zIndex(1_000_000));
        UIElement popup = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .width(100).height(100));
        document.append(raised);
        row1.append(popup);
        document.promote(popup);
        assertEquals(popup, at());
    }

    /**
     * A negative {@code z-index} is under its context's normal flow: under its parent too, unless the parent is the
     * context. {@code isolation} makes it one, and nothing else.
     */
    @Test
    public void aNegativeZIndexIsUnderItsParentUnlessTheParentIsolates() {
        UIElement under = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .width(200).height(40));
        under.generalStyle(g -> g.zIndex(-1));
        row2.append(under);
        assertEquals("under the page's flow, row2 included", row2, at());

        row2.generalStyle(g -> g.isolation(Isolation.ISOLATE));
        assertEquals("row2 is its context, so it is over row2's own background", under, at());
    }
}
