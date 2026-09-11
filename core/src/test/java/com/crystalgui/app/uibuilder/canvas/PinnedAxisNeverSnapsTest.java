package com.crystalgui.app.uibuilder.canvas;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>Shift's pinned axis is never offered to the solver.</b>
 *
 * <p>The reported bug, and it was both axes rather than one. {@code MoveOutOfFlow} pinned an axis and
 * then handed both to the snapper, which found the pinned one an alignment and wrote it — two writers of
 * one number, with the second having no idea the first existed. Which axis <em>looked</em> locked was
 * decided by which one happened to have a candidate within six pixels, so it read as "vertical is not
 * implemented" on one document and as "horizontal is not implemented" on another.</p>
 *
 * <p>Each case here puts a sibling edge deliberately in range of the PINNED axis. Before the fix, that
 * is the edge the box lands on.</p>
 */
public class PinnedAxisNeverSnapsTest extends UiDocumentTestBase {

    private UIElement parent;
    private UIElement moving;
    private MoveOutOfFlow gesture;

    @Before
    public void layOut() {
        parent = new UIElement().layout(l -> l.width(400).height(300));
        moving = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(10f).top(10f).width(50f).height(20f));
        // Two lures, each two pixels off the axis the other case pins.
        UIElement lureY = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(200f).top(12f).width(30f).height(10f));
        UIElement lureX = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(12f).top(200f).width(30f).height(10f));
        parent.append(moving, lureY, lureX);
        document.append(parent);
        document.update(W, H);

        gesture = new MoveOutOfFlow(null, null);
    }

    /** Dragging sideways: the vertical is pinned, and there is an edge two pixels from it. */
    @Test
    public void aSidewaysDragCannotBeSnappedVertically() {
        gesture.dragged(moving, 10f, 10f, 1f, 60f, 3f, CgModifiers.SHIFT);
        document.update(W, H);

        assertEquals("the solver moved the axis Shift had pinned",
                10f, moving.box().y(), 0.01f);
    }

    /** And down: the horizontal is pinned, with an edge two pixels from it. */
    @Test
    public void aDownwardDragCannotBeSnappedHorizontally() {
        gesture.dragged(moving, 10f, 10f, 1f, 3f, 60f, CgModifiers.SHIFT);
        document.update(W, H);

        assertEquals("the solver moved the axis Shift had pinned",
                10f, moving.box().x(), 0.01f);
    }

    /** And with no constraint the same lure IS taken, or the two tests above prove nothing. */
    @Test
    public void withoutShiftTheSameLureIsTaken() {
        gesture.dragged(moving, 10f, 10f, 1f, 60f, 3f, 0);
        document.update(W, H);

        assertEquals("unconstrained, the box should have snapped to the edge two pixels away",
                12f, moving.box().y(), 0.01f);
    }
}
