package com.crystalgui.app.uibuilder.canvas;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>A margin is not part of the inset.</b> CSS places the MARGIN box at {@code left}, inside the parent's
 * border, while a move solves for the border box. Writing the one straight into the other put a margined
 * node its margin further on than the guides said, and again at the start of every drag.
 */
public class MarginedMoveTest extends UiDocumentTestBase {

    private UIElement inParent(UIElement node) {
        UIElement parent = new UIElement().layout(l -> l.width(400).height(300));
        parent.append(node);
        document.append(parent);
        document.update(W, H);
        return node;
    }

    @Test
    public void aMarginedNodeLandsWhereTheMoveSays() {
        UIElement node = inParent(new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(100f).top(50f).width(30f).height(20f).marginAll(4f)));
        float startX = node.box().x();
        float startY = node.box().y();

        new MoveOutOfFlow(null, null).dragged(node, startX, startY, 1f, 20f, 10f, 0);
        document.update(W, H);

        assertEquals("the border box goes where the move said", startX + 20f, node.box().x(), 0.01f);
        assertEquals(startY + 10f, node.box().y(), 0.01f);
    }

    /** And from the far side, where the margin is the trailing one. */
    @Test
    public void soDoesOneAnchoredByItsFarEdges() {
        UIElement node = inParent(new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .right(100f).bottom(50f).width(30f).height(20f).marginAll(4f)));
        float startX = node.box().x();
        float startY = node.box().y();

        new MoveOutOfFlow(null, null).dragged(node, startX, startY, 1f, 20f, 10f, 0);
        document.update(W, H);

        assertEquals(startX + 20f, node.box().x(), 0.01f);
        assertEquals(startY + 10f, node.box().y(), 0.01f);
    }
}
