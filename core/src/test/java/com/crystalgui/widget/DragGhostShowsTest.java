package com.crystalgui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.joml.Vector3f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.DragPreview;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.dnd.DragGhostPresenter;

/**
 * A drag's preview gets a ghost with a box while the drag runs, under the pointer on the first frame, and nowhere once
 * the shell has taken it over or the drag has ended.
 *
 * <p>Asserted on the BOX, not on {@code isDisplayed()} or {@code isPromoted()}: a ghost can pass both and still resolve
 * {@code display: none}, which gives no box and draws nothing.</p>
 */
public class DragGhostShowsTest extends UiDocumentTestBase {

    private static final Drag.Listener NOTHING = new Drag.Listener() {
        @Override
        public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
        }
    };

    @Before
    public void installPresenter() {
        DragGhostPresenter.install();
    }

    private UIElement source() {
        UIElement source = new UIElement();
        document.append(source);
        return source;
    }

    private Drag drag(UIElement source, DragPreview preview) {
        return Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload", Drag.DEFAULT_THRESHOLD_PX, NOTHING)
                .preview(preview);
    }

    private DragGhost ghost() {
        return (DragGhost) document.input().presenter().element();
    }

    @Test
    public void aPreviewHasABoxWhileItsDragRuns() {
        withDefaultStyles();
        UIElement source = source();
        frame();

        Drag drag = drag(source, DragPreview.of("crystalgui:folder", "carrying"));
        DragGhost ghost = ghost();
        frame();
        assertNull("a ghost takes a box before any drag has activated", ghost.box());

        // PAST THE THRESHOLD, because a ghost belongs to a drag and a press that never moved is a click.
        drag.pointerMoved(60f, 60f);
        frame();

        assertNotNull("the ghost has no box while its drag runs, so there is nothing to draw", ghost.box());
        assertEquals("the ghost is not the size of the thing being carried", 16f, ghost.box().width(), 0.01f);
        assertEquals("the ghost is not the size of the thing being carried", 16f, ghost.box().height(), 0.01f);

        drag.cancel();
        frame();
        assertNull("the ghost kept its box after the drag ended", ghost.box());
        assertFalse("the ghost stayed promoted after the drag", document.isPromoted(ghost));
    }

    @Test
    public void aPreviewIsUnderThePointerOnTheFirstFrameItIsShown() {
        withDefaultStyles();
        UIElement source = source();
        frame();

        Drag drag = drag(source, DragPreview.of("crystalgui:folder", "carrying"));
        drag.pointerMoved(300f, 200f);
        frame();

        DragGhost ghost = ghost();
        assertNotNull(ghost.box());
        Vector3f at = ghost.box().localToWorld().transformPosition(new Vector3f());
        assertTrue("the ghost is not under the pointer on its first frame: " + at,
                Math.abs(at.x - 300f) < 32f && Math.abs(at.y - 200f) < 32f);
        drag.cancel();
        frame();
    }

    @Test
    public void aDragWithNoPreviewDrawsNoGhost() {
        withDefaultStyles();
        UIElement source = source();
        frame();

        Drag drag = Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload", Drag.DEFAULT_THRESHOLD_PX, NOTHING);
        drag.pointerMoved(60f, 60f);
        frame();

        assertNull("a drag with no preview shows nothing", document.input().dragPreview());
        drag.cancel();
    }

    /** The shell draws the ghost over a window's client, so the source's own stays hidden: it would be cut off at the edge. */
    @Test
    public void noGhostIsDrawnWhileTheShellCarriesIt() {
        withDefaultStyles();
        UIElement source = source();
        frame();

        DragPreview preview = DragPreview.of("crystalgui:folder", "carrying");
        Drag drag = drag(source, preview);
        document.input().handOverGhost(true);
        drag.pointerMoved(60f, 60f);
        frame();

        assertNull("the document drew a ghost the shell was carrying", ghost().box());
        assertSame("the shell reads the drag's preview", preview, document.input().dragPreview());

        drag.cancel();
        assertNull("the preview outlived its drag", document.input().dragPreview());
        assertFalse("the hand-over outlived its drag", document.input().ghostHandedOver());
    }
}
