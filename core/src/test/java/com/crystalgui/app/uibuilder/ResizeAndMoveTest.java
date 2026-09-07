package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.CanvasRects;
import com.crystalgui.app.uibuilder.canvas.MoveOutOfFlow;
import com.crystalgui.app.uibuilder.canvas.Snap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>L4.5 — what a resize and a move are allowed to write.</b>
 *
 * <p>The gestures themselves are driven by {@code Drag}, which needs a live pointer; what is asserted
 * here is the arithmetic underneath them — which alignment a proposed position takes, and which inset a
 * move is allowed to write. Those are the parts that are wrong silently: a snap that never fires looks
 * like a designer with an unsteady hand, and writing {@code left} on a right-anchored node moves it
 * again the next time the parent resizes.</p>
 */
public class ResizeAndMoveTest extends UiDocumentTestBase {

    private UIElement parent;
    private UIElement moving;
    private UIElement fixed;

    @Before
    public void layOutTwoBoxes() {
        parent = new UIElement().layout(l -> l.width(400).height(300));
        moving = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(10f).top(10f).width(50f).height(20f));
        fixed = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(100f).top(80f).width(60f).height(40f));
        parent.append(moving, fixed);
        document.append(parent);
        document.update(W, H);
    }

    /** <b>Only a positioned node is movable.</b> An in-flow one is placed by its parent. */
    @Test
    public void anInFlowNodeIsNotSomethingADragMayPosition() {
        UIElement inFlow = new UIElement().layout(l -> l.width(30).height(10));
        parent.append(inFlow);
        document.update(W, H);

        assertTrue(MoveOutOfFlow.isMovable(moving));
        assertFalse("dragging this means reorder, which is a different gesture",
                MoveOutOfFlow.isMovable(inFlow));
    }

    /** A proposed position near a sibling's left edge takes it, and says which line it took. */
    @Test
    public void aPositionNearASiblingEdgeSnapsToIt() {
        Snap.Result snapped = Snap.of(moving, 97f, 200f, 6f);

        assertEquals("did not snap to the sibling's left edge", 100f, snapped.x(), 0.01f);
        assertEquals("the other axis had nothing to snap to and must not move",
                200f, snapped.y(), 0.01f);
        assertFalse("a snap that draws no guide cannot be explained", snapped.guides().isEmpty());
    }

    /** Out of range, nothing moves — which is what makes the snap usable rather than magnetic. */
    @Test
    public void aPositionOutOfRangeIsLeftAlone() {
        Snap.Result snapped = Snap.of(moving, 80f, 200f, 6f);

        assertEquals(80f, snapped.x(), 0.01f);
        assertTrue(snapped.guides().isEmpty());
    }

    /** Centres count too: aligning to the middle of a sibling is the same gesture. */
    @Test
    public void aCentreCountsAsAnAlignment() {
        // The sibling's centre is at x = 130; a 50-wide box centred there starts at 105.
        Snap.Result snapped = Snap.of(moving, 107f, 5f, 6f);

        assertEquals(105f, snapped.x(), 0.01f);
    }

    /** The parent's own content box is an alignment, and beats a sibling at the same distance. */
    @Test
    public void theParentsEdgeIsAnAlignment() {
        Snap.Result snapped = Snap.of(moving, 3f, 200f, 6f);

        assertEquals("flush with the container is what somebody aiming at zero meant",
                0f, snapped.x(), 0.01f);
    }

    /**
     * <b>Withdrawing a size is not hugging, and on its own it does the opposite.</b>
     *
     * <p>Double-clicking a handle returns that axis to content-sizing. The obvious spelling — clear the
     * width — is wrong: an item with no definite cross size is STRETCHED by {@code align-items}, so it
     * fills its container. That is Figma's <em>Fill</em>, and it is what made a double-click on the
     * middle-right handle blow a 278px box out to the full artboard width.</p>
     *
     * <p>{@code fit-content} does not rescue it: this engine stretches that as readily as {@code auto},
     * which is measured below rather than assumed, since CSS says otherwise and the difference is
     * invisible until something is wide enough to notice. Opting out of stretch is the operative half,
     * and once it is done either spelling hugs.</p>
     */
    @Test
    public void hugHasToOptOutOfStretch() {
        UIElement column = new UIElement().layout(l -> l.width(400f).height(300f)
                .flexDirection(FlexDirection.COLUMN));
        UIElement cleared = child(column, l -> l.widthAuto());
        UIElement fitted = child(column, l -> l.widthFitContent());
        UIElement hugging = child(column, l -> l.widthAuto().alignSelf(AlignItems.FLEX_START));
        document.append(column);
        document.update(W, H);

        assertEquals("no definite cross size is stretched — this is Fill, not Hug",
                400f, cleared.box().width(), 0.5f);
        assertEquals("fit-content is stretched here too, whatever CSS says about it",
                400f, fitted.box().width(), 0.5f);
        assertEquals("opting out of stretch is what actually hugs the content",
                60f, hugging.box().width(), 0.5f);
    }

    /** A 60-wide child inside a node styled as asked, appended to {@code parent}. */
    private static UIElement child(UIElement parent,
                                   java.util.function.Consumer<com.crystalgui.style.LayoutGroup> style) {
        UIElement node = new UIElement().layout(l -> {
            l.height(20f);
            style.accept(l);
        });
        node.append(new UIElement().layout(l -> l.width(60f).height(10f)));
        parent.append(node);
        return node;
    }

    /**
     * <b>The resize handles live on the LAYOUT box, whatever the element's transform draws.</b>
     *
     * <p>They write {@code width} and {@code height}, so that is the rectangle they have to be drawn on
     * and the space their drag has to be measured in. Placed through {@code localToWorld} they wrap the
     * element as transformed — which on a scaled element is the same rectangle the Free Transform box
     * shows, so committing a scale looked exactly like a resize and the two gestures could not be told
     * apart. Nothing to do with Free Transform either: a {@code transform: scale(2)} from a stylesheet
     * produces it identically.</p>
     */
    @Test
    public void aDragOnATransformedElementIsMeasuredInTheElementsOwnPixels() {
        UIElement scaled = new UIElement().layout(l -> l.width(50f).height(20f));
        StyleGroup.inlinePipeline(scaled.getStyle().getGeneralGroup(),
                g -> g.transform(Transform.scale(2f, 4f)));
        parent.append(scaled);
        document.update(W, H);

        // WHAT THE HANDLES SEE. They edit `width`/`height`, so both the rectangle they are drawn on and
        // the space their drag is measured in are the LAYOUT box -- the element's own transform divided
        // out of both. Drawn through localToWorld instead, a scaled element gets a handle box identical
        // to the Free Transform box's, and the two gestures stop being tellable apart.
        float[] drawn = CanvasRects.of(scaled, parent);
        float[] layout = CanvasRects.ofLayout(scaled, parent);
        assertNotNull(drawn);
        assertNotNull(layout);
        assertEquals("the drawn box carries the 2x", 100f, drawn[2], 0.5f);
        assertEquals("the layout box is what a resize edits", 50f, layout[2], 0.5f);
        assertEquals(80f, drawn[3], 0.5f);
        assertEquals(20f, layout[3], 0.5f);

        Matrix4f frame = CanvasRects.layoutFrame(scaled, parent);
        assertNotNull("both are laid out, so there is a mapping", frame);
        Vector2f moved = CanvasRects.toLocalDelta(frame, 100f, 100f);
        assertEquals("a drag on the layout box is measured in layout pixels", 100f, moved.x, 0.01f);
        assertEquals(100f, moved.y, 0.01f);
    }

    /**
     * <b>The mouse picks the same rectangle the chrome is drawn on.</b>
     *
     * <p>The engine's hit test inverts {@code localToWorld}, so it answers about what is PAINTED — right
     * for a running UI, wrong for a designer. Selecting by the drawn box means dragging a rectangle Taffy
     * knows nothing about: the ordering and stability the box tree gives you stop applying to the thing
     * you are manipulating. So design-time picking asks the layout box, and then the outline, the
     * handles, the hover and the pointer all agree.</p>
     */
    @Test
    public void designPickingUsesTheLayoutBoxAndNotWhatIsDrawn() {
        UIElement scaled = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(40f).height(20f));
        StyleGroup.inlinePipeline(scaled.getStyle().getGeneralGroup(),
                g -> g.transform(Transform.scale(4f, 4f)));
        parent.append(scaled);
        document.update(W, H);

        Box box = scaled.box();
        // Its own top-left corner, which both readings agree is inside.
        assertTrue(CanvasRects.layoutContains(scaled, box.x() + 2f, box.y() + 2f));

        // A point the DRAWING covers and the layout box does not: 4x on a 40x20 box paints out to 160
        // wide about its centre, so x = 100 is well inside the picture and well outside the element.
        assertFalse("picking the painted overflow selects a rectangle the layout never made",
                CanvasRects.layoutContains(scaled, box.x() + 100f, box.y() + 2f));
    }

    /**
     * <b>A right-anchored node keeps being right-anchored.</b>
     *
     * <p>Writing {@code left} instead moves it now and moves it again the moment the parent resizes,
     * which is how a designed panel drifts.</p>
     */
    @Test
    public void aRightAnchoredNodeIsMovedByItsOwnInset() {
        assertFalse("a node stating left is moved by left", MoveOutOfFlow.anchorsRight(moving));

        UIElement pinned = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .right(20f).bottom(30f).width(40f).height(20f));
        parent.append(pinned);
        document.update(W, H);

        assertTrue("stating right and not left means right is the anchor",
                MoveOutOfFlow.anchorsRight(pinned));
        assertTrue(MoveOutOfFlow.anchorsBottom(pinned));
    }
}
