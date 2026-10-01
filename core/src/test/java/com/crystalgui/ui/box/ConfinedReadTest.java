package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * A layout read visits only what the compute wrote. These pin that what a change reaches is still read: a sibling it
 * moves, a box in another branch it resizes, and a scroll range it shrinks.
 */
public class ConfinedReadTest extends UiDocumentTestBase {

    @Test
    public void aSiblingMovedByAGrowingBoxIsRead() {
        UIElement root = new UIElement().layout(l -> l.width(40).height(200));
        UIElement grows = new UIElement().layout(l -> l.width(40).height(10));
        UIElement after = new UIElement().layout(l -> l.width(40).height(10));
        root.append(grows);
        root.append(after);
        document.append(root);
        document.update(W, H);

        grows.layout(l -> l.height(50));
        document.update(W, H);
        assertEquals(50f, grows.box().height(), 0.001f);
        assertEquals(50f, after.box().y(), 0.001f);
    }

    /** A flex share: one child growing shrinks its sibling's share, in a branch nothing touched directly. */
    @Test
    public void aShareTakenFromAnotherBranchIsRead() {
        UIElement row = new UIElement().layout(l -> l.width(100).height(20).flexDirection(FlexDirection.ROW));
        UIElement fixed = new UIElement().layout(l -> l.width(20).height(20));
        UIElement fills = new UIElement().layout(l -> l.height(20).flexGrow(1f));
        UIElement inner = new UIElement().layout(l -> l.widthPercent(100f).height(5));
        fills.append(inner);
        row.append(fixed);
        row.append(fills);
        document.append(row);
        document.update(W, H);
        assertEquals(80f, inner.box().width(), 0.001f);

        fixed.layout(l -> l.width(60));
        document.update(W, H);
        assertEquals(40f, fills.box().width(), 0.001f);
        assertEquals(40f, inner.box().width(), 0.001f);
    }

    @Test
    public void shrinkingContentClampsTheScroll() {
        UIElement scroller = new UIElement().layout(l -> l.width(40).height(20));
        scroller.generalStyle(g -> g.overflow(Overflow.SCROLL));
        UIElement content = new UIElement().layout(l -> l.width(40).height(200));
        scroller.append(content);
        document.append(scroller);
        document.update(W, H);
        scroller.box().setScroll(0f, 150f);
        assertEquals(150f, scroller.scrollTop(), 0.001f);

        content.layout(l -> l.height(50));
        document.update(W, H);
        assertEquals(30f, scroller.scrollTop(), 0.001f);
    }
}
