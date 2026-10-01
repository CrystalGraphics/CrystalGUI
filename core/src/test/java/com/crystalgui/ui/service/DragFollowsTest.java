package com.crystalgui.ui.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/** What a drag declares moves with the pointer is declared from activation to the drag's end, however it ends. */
public class DragFollowsTest extends UiDocumentTestBase {

    private UIElement source, moved, container;

    private Drag drag(float threshold) {
        source = new UIElement();
        moved = new UIElement();
        container = new UIElement();
        document.append(source);
        document.append(container);
        container.append(moved);
        frame();
        return Drag.start(source, 100f, 100f, CgMouseCodes.LEFT_BUTTON, null, threshold, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
            }
        }).follows(moved, container);
    }

    @Test
    public void aThresholdDragDeclaresOnlyOnceItActivates() {
        Drag drag = drag(Drag.DEFAULT_THRESHOLD_PX);
        assertNull("a drag that has not activated moved its element early", document.input().pointerFollower());

        drag.pointerMoved(200f, 100f);
        assertSame(moved, document.input().pointerFollower());
        assertSame(container, document.input().pointerFollowerWithin());
        assertTrue("a follower records under a node of its own", moved.box().willChangeTransform());
    }

    @Test
    public void aCancelledDragWithdrawsTheDeclaration() {
        Drag drag = drag(0f);
        assertSame(moved, document.input().pointerFollower());

        drag.cancel();
        assertNull(document.input().pointerFollower());
        assertNull(document.input().pointerFollowerWithin());
        assertFalse(moved.box().willChangeTransform());
    }
}
