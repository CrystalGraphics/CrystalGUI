package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.Artboard;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.app.uibuilder.canvas.TreePolicy;
import com.crystalgui.core.cursor.Cursor;

import com.crystalgui.app.uibuilder.canvas.TreeSelectTool;
import com.crystalgui.app.uibuilder.canvas.transform.FreeTransformTool;
import com.crystalgui.app.uibuilder.canvas.transform.TransformBox;
import com.crystalgui.app.uibuilder.canvas.transform.TransformOptionsBar;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Grip;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Kind;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;

import dev.vfyjxf.taffy.style.TaffyPosition;
import com.crystalgui.widget.surface.SurfacePolicy;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * <b>L4.5a — Free Transform as a modal state over one selection.</b>
 *
 * <p>The arithmetic is {@code TransformGestureTest}'s. What is asserted here is the part that touches the
 * document: that a live gesture writes nothing until it is committed, that cancelling really does leave no
 * trace, and that a commit is exactly one undo step. Those are the three that would be found late — a
 * preview that quietly dirtied the file, or a cancel that undid to the wrong place.</p>
 */
public class FreeTransformTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private BuilderEditor editor;
    private UIElement node;
    private UIElement host;
    private Disposable commands;

    @Before
    public void openABuilder() {
        UIElementRegistry.bootstrap();
        commands = BuilderCommands.register();
        model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:t.cgui");
        node = new UIElement().layout(l -> l.width(100).height(50));
        model.root().append(node);

        editor = new BuilderEditor(model);
        host = new UIElement().layout(l -> l.width(400).height(300));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
        editor.selection().selectOnly(node);
    }

    @After
    public void releaseCommands() {
        if (commands != null) commands.dispose();
    }

    private TransformBox box() {
        return editor.transformBox();
    }

    private void enterFreeTransform() {
        editor.surface().modes().use(FreeTransformTool.ID);
    }

    /** A live rotation, previewed and not recorded — what every case below starts from. */
    private void rotateLive() {
        box().gesture().press(new Grip(Kind.ROTATE, null));
        box().gesture().rotateBy(0.5f, false);
        box().preview();
    }

    private Transform written() {
        return node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM);
    }

    /** A point in the box overlay's own space, in the window coordinates input is fed in. */
    private int[] worldOf(Vector2f inOverlay) {
        Vector3f at = new Matrix4f(box().box().localToWorld())
                .transformPosition(new Vector3f(inOverlay.x, inOverlay.y, 0f));
        return new int[] {Math.round(at.x), Math.round(at.y)};
    }

    /**
     * <b>A skew follows the pointer, whatever ops the transform happens to be written as.</b>
     *
     * <p>{@code TransformGestureTest} asserts the same rule one layer down, but it hands {@code skewBy}
     * a delta already in the node's frame — so nothing covered the step that PUTS it there, which is
     * where the frame can be wrong. Asserted in the overlay's own space, on where the handle lands,
     * because that is the space the hand is in.</p>
     *
     * <p>The transform is the scratch document's {@code #hint}: ops in canonical order, so the gesture
     * reads them straight rather than decomposing, and chosen by the tool itself over several gestures.
     * They compose to very nearly the identity — the box draws square — while the rotation op alone is
     * −68°. Measuring the lean through the rotation op therefore projects the pointer onto axes the box
     * is not drawn on, and the edge leaves the hand at an angle. Every other node in that document has a
     * rotation op that IS its apparent orientation, which is why one element misbehaved and the rest
     * were perfect.</p>
     */
    @Test
    public void aSkewFollowsThePointerWhateverTheOpsAreWrittenAs() {
        Transform woundUp = Transform.IDENTITY
                .then(Transform.Op.translate(LengthPercent.px(-2.3140755f), LengthPercent.px(-1.0744351f)))
                .then(Transform.Op.rotate(-1.1991656f))
                .then(Transform.Op.skew(-1.1886246f, 1.2132671f))
                .then(Transform.Op.scale(0.3441459f, 0.36104363f));
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.transform(woundUp));
        document.update(W, H);

        enterFreeTransform();
        document.update(W, H);

        Vector2f before = box().handleAt(Spot.TOP);
        assertNotNull(before);
        box().press(new Grip(Kind.SKEW, Spot.TOP));
        box().dragTo(before.x + 30f, before.y, 30f, 0f, false, false, false);
        document.update(W, H);

        Vector2f after = box().handleAt(Spot.TOP);
        assertNotNull(after);
        assertEquals("the dragged edge did not end up under the pointer",
                before.x + 30f, after.x, 0.5f);
        assertEquals("a horizontal drag moved the edge vertically", before.y, after.y, 0.5f);
    }

    /** The control: the same drag on an untransformed node, which is the case that always worked. */
    @Test
    public void andStillFollowsItWithNoTransformAtAll() {
        enterFreeTransform();
        document.update(W, H);

        Vector2f before = box().handleAt(Spot.TOP);
        assertNotNull(before);
        box().press(new Grip(Kind.SKEW, Spot.TOP));
        box().dragTo(before.x + 30f, before.y, 30f, 0f, false, false, false);
        document.update(W, H);

        Vector2f after = box().handleAt(Spot.TOP);
        assertNotNull(after);
        assertEquals(before.x + 30f, after.x, 0.5f);
        assertEquals(before.y, after.y, 0.5f);
    }

    /**
     * <b>The rotate band draws its arrow, and the arrow goes when the node does.</b>
     *
     * <p>A native cursor cannot turn, so the rotate arrow is drawn and the cursor under it is a plain
     * hand — Paint.NET's split. What that buys in smoothness it owes in lifetime: the art is cleared by
     * a per-frame hook the node owns, and a node removed mid-gesture never gets the frame that would
     * clear it, so the arrow would stay on screen with nothing left to take it down.</p>
     */
    @Test
    public void theRotateBandDrawsItsArrowAndTakesItAwayAgain() {
        enterFreeTransform();
        document.update(W, H);

        Vector2f corner = box().handleAt(Spot.TOP_RIGHT);
        assertNotNull(corner);
        float bandX = corner.x + 8f;
        float bandY = corner.y - 8f;
        assertEquals("the fixture must actually be over the rotate band",
                Kind.ROTATE, box().grip(bandX, bandY, false).kind());
        assertEquals("the band takes a hand; the arrow is drawn, not presented",
                Cursor.GRAB, TransformBox.cursorFor(box().grip(bandX, bandY, false)));

        box().hoverAt(bandX, bandY);
        document.frame(0.016f, W, H);

        assertNotNull("nothing was drawn at the pointer over the rotate band",
                document.input().cursorDecoration());

        document.remove(host);
        document.frame(0.016f, W, H);

        assertNull("the node went and left its arrow behind",
                document.input().cursorDecoration());
    }

    /**
     * <b>The arrow keeps following the hand for the whole rotation, not just the grab.</b>
     *
     * <p>A drag does not come through {@code pointerMoved}: {@code pointerDown} hands the pointer to
     * {@code Drag}, which drives {@link TransformBox#dragTo} until it ends. So an angle read from the
     * HOVER position is written once, at the press, and then never again — the arrow points where the
     * hand was when you grabbed and stays there for the rest of the turn, which is the one stretch
     * anybody is looking at it.</p>
     */
    @Test
    public void theRotationArrowFollowsTheHandForTheWholeGesture() {
        enterFreeTransform();
        document.update(W, H);

        Vector2f corner = box().handleAt(Spot.TOP_RIGHT);
        assertNotNull(corner);
        box().hoverAt(corner.x + 8f, corner.y - 8f);
        float atGrab = box().rotationArtAngle();

        box().press(new Grip(Kind.ROTATE, Spot.TOP_RIGHT));
        box().setDragging(true);
        // A quarter of the way round the box, which is what the Drag listener reports.
        box().dragTo(corner.x - 60f, corner.y + 60f, -60f, 60f, false, false, false);

        assertNotEquals("the arrow was left pointing where the hand grabbed",
                atGrab, box().rotationArtAngle(), 0.05f);
    }

    /** The box opens on the selection and takes the surface with it. */
    @Test
    public void ctrlTOpensTheBoxOnTheSelection() {
        enterFreeTransform();

        assertTrue("the box did not open", box().isActive());
        assertSame(node, box().target());
        assertEquals(FreeTransformTool.ID, editor.surface().modes().currentId());
    }

    /**
     * <b>A live gesture writes nothing.</b>
     *
     * <p>The preview is the box's compositor override, which sits above the cascade and is withdrawn with
     * a null. If it went through inline style instead, every frame of a drag would dirty the document and
     * cancelling would need an undo — which is the whole reason the two channels are kept apart.</p>
     */
    @Test
    public void aLiveGestureLeavesTheDocumentAlone() {
        long clean = model.version();
        enterFreeTransform();
        rotateLive();
        document.update(W, H);

        assertEquals("a preview must not touch the document", clean, model.version());
        assertNotNull("the preview is the box override, so it has to be on the box",
                node.box().transform());
        assertTrue("nothing should have reached the cascade yet",
                written() == null || written().isIdentity());
    }

    /** Cancel drops the override and leaves nothing behind — not even an undo step. */
    @Test
    public void escapeWritesNothingAtAll() {
        long clean = model.version();
        enterFreeTransform();
        rotateLive();

        box().cancel();
        document.update(W, H);

        assertFalse(box().isActive());
        assertEquals("cancel put a step in the history", clean, model.version());
        assertTrue("the override outlived the gesture that needed it",
                node.box().transform().isIdentity());
    }

    /** Commit writes the transform once, and the box goes down. */
    @Test
    public void enterWritesOneEdit() {
        enterFreeTransform();
        rotateLive();
        long before = model.version();

        box().commit();
        document.update(W, H);

        assertFalse(box().isActive());
        assertTrue("the box is down, so its override has to be withdrawn",
                node.box().transform().equals(written()));
        Transform committed = written();
        assertNotNull("nothing reached the cascade", committed);
        assertFalse("the commit wrote an identity", committed.isIdentity());
        assertTrue("a gesture is one step, not none", model.version() > before);

        model.history().undo();
        document.update(W, H);
        assertTrue("one undo has to take the whole gesture",
                written() == null || written().isIdentity());
    }

    /**
     * <b>A scale is a RENDER scale, and that is the whole difference from the resize handles.</b>
     *
     * <p>The handles write {@code width}/{@code height}: the box changes, Taffy relayouts, the siblings
     * move and the label stays the size it was. This writes {@code transform}, which layout never sees —
     * so the box keeps its measured size and everything drawn inside it, label included, is scaled with
     * it. Two gestures that looked the same on screen would mean one of them is writing the wrong
     * property, so both halves are asserted: the layout box unchanged, and the painted extent doubled.</p>
     */
    @Test
    public void aScaleIsRenderedNotLaidOut() {
        UIElement child = new UIElement().layout(l -> l.width(20).height(10));
        node.append(child);
        document.update(W, H);
        float laidOut = node.box().width();
        float childBefore = worldWidth(child);

        enterFreeTransform();
        box().gesture().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().gesture().scaleTo(new Vector2f(200f, 100f), false, false);
        box().preview();
        document.update(W, H);

        assertEquals("a transform must never reflow — this is the resize handles' job, not this one",
                laidOut, node.box().width(), 0.01f);
        assertEquals("the subtree did not scale, so nothing was rendered through the transform",
                childBefore * 2f, worldWidth(child), 0.5f);
    }

    /** How wide a node is ON SCREEN, which is where a transform shows up and the layout box does not. */
    private static float worldWidth(UIElement element) {
        Matrix4f world = element.box().localToWorld();
        Vector3f left = world.transformPosition(new Vector3f(0f, 0f, 0f));
        Vector3f right = world.transformPosition(new Vector3f(element.box().width(), 0f, 0f));
        return right.x - left.x;
    }

    /**
     * <b>The same, on a real widget — which is a shadow host.</b>
     *
     * <p>{@code Button} paints its background from its OWN box and its label from a part inside a shadow
     * root, and a shadow root composes through a different branch of {@code BoxTree}. So "the subtree
     * scaled" proved on a plain child does not prove it here, and the case that is actually being used is
     * the one with the second code path under it.</p>
     */
    @Test
    public void aScaleReachesAWidgetsOwnBoxAndItsShadowParts() {
        Button button = new Button("Save");
        model.root().append(button);
        document.update(W, H);
        editor.selection().selectOnly(button);

        float ownBefore = worldWidth(button);
        UIElement label = button.shadowRoot().composedChildren().isEmpty()
                ? null : button.shadowRoot().composedChildren().get(0);
        assertNotNull("a Button should have a shadow part to measure", label);
        float labelBefore = worldWidth(label);
        float laidOut = button.box().width();

        enterFreeTransform();
        box().gesture().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().gesture().scaleTo(new Vector2f(button.box().width() * 2f,
                button.box().height() * 2f), false, false);
        box().preview();
        document.update(W, H);

        assertEquals("a transform must never reflow the widget",
                laidOut, button.box().width(), 0.01f);
        assertEquals("the widget's own box did not scale",
                ownBefore * 2f, worldWidth(button), 0.5f);
        assertEquals("the shadow part did not scale, so the label would stay its own size",
                labelBefore * 2f, worldWidth(label), 0.5f);
    }

    /**
     * <b>A stationary pointer gives a stationary answer.</b>
     *
     * <p>The scale is measured through a mapping that contains the translate, and holding the anchor
     * still WRITES the translate every frame. Measured through the live mapping, each frame moves the
     * space the next one is measured in: the same pointer keeps producing a new scale, one axis collapses
     * and the other runs away. Dragging out and down came back as narrow and tall.</p>
     *
     * <p>Asserted as idempotence because that is the property the defect breaks and a hand cannot check:
     * the same drag applied twice has to mean the same thing.</p>
     */
    @Test
    public void repeatingADragMeansTheSameThing() {
        enterFreeTransform();
        // The overlay is shown by `begin`, so it has no box of its own until the next layout -- and
        // every space here is measured in the overlay's coordinates.
        document.update(W, H);
        Vector2f handle = box().handleAt(Spot.BOTTOM_RIGHT);
        assertNotNull("the box has to be placed before it can be dragged", handle);
        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));

        float toX = handle.x + 60f;
        float toY = handle.y + 30f;
        box().dragTo(toX, toY, 60f, 30f, false, false, false);
        float firstX = box().gesture().scaleX();
        float firstY = box().gesture().scaleY();

        for (int i = 0; i < 8; i++) box().dragTo(toX, toY, 60f, 30f, false, false, false);

        assertEquals("the scale walked while the pointer stood still", firstX,
                box().gesture().scaleX(), 0.0001f);
        assertEquals(firstY, box().gesture().scaleY(), 0.0001f);
        assertTrue("dragging the corner out has to make it bigger, not smaller", firstX > 1f);
        assertTrue(firstY > 1f);
    }

    /**
     * <b>A handle nudged a few pixels snaps back to the layout box</b> — the outline the transform is
     * read against is a target too, so "this edge where it started" is one snap.
     */
    @Test
    public void aHandleNudgedFromWhereItStartedSnapsBack() {
        enterFreeTransform();
        document.update(W, H);
        Vector2f handle = box().handleAt(Spot.RIGHT);
        assertNotNull(handle);
        box().press(new Grip(Kind.SCALE, Spot.RIGHT));

        box().dragTo(handle.x + 5f, handle.y, 5f, 0f, false, false, true);
        assertEquals("back to the width it had", 1f, box().gesture().scaleX(), 0.0001f);

        box().dragTo(handle.x + 5f, handle.y, 5f, 0f, false, false, false);
        assertTrue("and with snapping off the same nudge scales it", box().gesture().scaleX() > 1f);
    }

    /**
     * <b>Shift latches a move's axis</b>, as the out-of-flow move's does: chosen once the hand has
     * committed, and held when the pointer later crosses the diagonal — where deciding it afresh every
     * frame flipped it.
     */
    @Test
    public void shiftLatchesAMovesAxis() {
        enterFreeTransform();
        document.update(W, H);
        Vector2f from = box().handleAt(Spot.TOP_LEFT);
        assertNotNull(from);
        box().press(new Grip(Kind.MOVE, null));

        box().dragTo(from.x + 40f, from.y + 6f, 40f, 6f, true, false, false);
        assertEquals("the minor axis is dropped", 0f, box().gesture().translateY(), 0.01f);

        box().dragTo(from.x + 40f, from.y + 60f, 40f, 60f, true, false, false);
        assertEquals("and stays dropped past the diagonal", 0f, box().gesture().translateY(), 0.01f);
        assertTrue(box().gesture().translateX() > 0f);
    }

    /**
     * <b>The reported case, end to end: a button in a row, dragged by its corner.</b>
     *
     * <p>Driven through the box's own press-and-drag rather than by setting the gesture's numbers, so the
     * spaces and the anchor compensation are all exercised. What is asserted is the property the report
     * is about: the button's LAYOUT size and its parent's are the same before and after. A transform that
     * reflowed would move the row and the label beside it, which is a resize wearing a transform's name.
     * </p>
     */
    @Test
    public void draggingACornerDoesNotReflowTheRow() {
        UIElement row = new UIElement().layout(l -> l.width(300).height(40)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        UIElement label = new UIElement().layout(l -> l.width(80).height(20));
        Button button = new Button("Save");
        row.append(label, button);
        model.root().append(row);
        document.update(W, H);
        editor.selection().selectOnly(button);

        float rowHeight = row.box().height();
        float rowWidth = row.box().width();
        float buttonWidth = button.box().width();
        float buttonHeight = button.box().height();
        float labelX = label.box().x();

        enterFreeTransform();
        document.update(W, H);
        Vector2f handle = box().handleAt(Spot.BOTTOM_RIGHT);
        assertNotNull(handle);
        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().dragTo(handle.x + 50f, handle.y + 40f, 50f, 40f, false, false, false);
        document.update(W, H);

        assertEquals("the button's own layout width changed — that is a resize",
                buttonWidth, button.box().width(), 0.01f);
        assertEquals("the button's own layout height changed — that is a resize",
                buttonHeight, button.box().height(), 0.01f);
        assertEquals("the row reflowed around it", rowHeight, row.box().height(), 0.01f);
        assertEquals(rowWidth, row.box().width(), 0.01f);
        assertEquals("a sibling moved, so the layout saw the transform",
                labelX, label.box().x(), 0.01f);
        assertTrue("nothing was scaled at all", box().gesture().scaleX() > 1f);
    }

    /**
     * <b>The committed pivot survives the element being resized.</b>
     *
     * <p>Written in pixels it does not: it is measured against the size the element had at the time, and
     * a later resize leaves it pointing somewhere else. Measured in a running harness at 115px on a box
     * that had become 59 wide — an origin outside the element, which makes every later scale translate
     * the box rather than grow it.</p>
     */
    @Test
    public void theCommittedPivotIsAFractionOfTheBox() {
        node.layout(l -> l.width(200f).height(100f));
        document.update(W, H);

        enterFreeTransform();
        box().gesture().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().gesture().scaleTo(new Vector2f(300f, 150f), false, false);
        box().commit();
        document.update(W, H);

        LengthPercent originX =
                node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
        assertNotNull(originX);
        assertEquals("the centre of the box it was committed on", 100f, originX.resolve(200f), 0.5f);

        // The element is resized afterwards, exactly as the harness run did.
        node.layout(l -> l.width(60f));
        document.update(W, H);
        assertEquals("the pivot has to follow the box, not the size it was written at",
                30f, originX.resolve(60f), 0.5f);
    }

    /**
     * <b>Chrome still answers for itself.</b>
     *
     * <p>Design picking corrects what the box tree found; it does not replace the question. Answering it
     * from scratch by walking the artboard means a press on a resize handle resolves to whatever document
     * element happens to be behind it — so the tool selects that element and consumes the press, and the
     * handles stop dragging while a corner selects the parent.</p>
     */
    @Test
    public void aPressOnChromeIsNotRedirectedIntoTheDocument() {
        Artboard artboard = new Artboard(model);
        document.append(artboard);
        UIElement inside = new UIElement().layout(l -> l.width(40f).height(20f));
        model.root().append(inside);
        UIElement chrome = new UIElement().layout(l -> l.width(6f).height(6f));
        document.append(chrome);
        document.update(W, H);

        TreePolicy policy = new TreePolicy(artboard);

        assertSame("something drawn over the canvas has to keep the press it was given",
                chrome, policy.pickAt(chrome, 0f, 0f));
        assertSame("a hit on the document's own content is corrected to the layout box",
                inside, policy.pickAt(inside,
                        inside.box().x() + 2f, inside.box().y() + 2f));
    }

    /**
     * <b>What a press means where, which is the whole of what the cursor promises.</b>
     *
     * <p>Rotate has no handle drawn for it — the band outside a corner IS the affordance — so it has to
     * live somewhere a press means nothing else. Reaching inside the box it took the corner region from
     * Move, and pressing near a corner to drag the element spun it instead. The pivot had the mirror
     * fault: its grab radius was the crosshair's whole span rather than half of it, so it claimed a
     * circle twice the width of the mark and could be picked up from empty box.</p>
     */
    @Test
    public void eachRegionMeansOneThing() {
        enterFreeTransform();
        document.update(W, H);
        Vector2f corner = box().handleAt(Spot.BOTTOM_RIGHT);
        Vector2f centre = box().toViewport(node.box().width() / 2f, node.box().height() / 2f);
        assertNotNull(corner);
        assertNotNull(centre);

        assertEquals("on the handle itself", Kind.SCALE,
                box().grip(corner.x, corner.y, false).kind());
        assertEquals("just outside the corner", Kind.ROTATE,
                box().grip(corner.x + 12f, corner.y + 12f, false).kind());
        // THE BOUNDARY, which is the whole complaint: a grab radius wider than the dot does not make
        // scaling easier to hit, it pushes rotate out of reach. Six pixels clear of a six-pixel dot has
        // to be rotate already.
        assertEquals("rotate has to begin at the edge of the dot, not well clear of it",
                Kind.ROTATE, box().grip(corner.x + 4.5f, corner.y + 4.5f, false).kind());
        assertEquals("a press well inside the box moves it, however near a corner it is",
                Kind.MOVE, box().grip(corner.x - 14f, corner.y - 14f, false).kind());
        assertEquals("the pivot's own mark", Kind.PIVOT, box().grip(centre.x, centre.y, false).kind());
        assertEquals("well clear of the crosshair, so it is box and means Move",
                Kind.MOVE, box().grip(centre.x + 8f, centre.y, false).kind());
    }

    /** Ctrl over an EDGE advertises a skew; over a corner it stays a scale, since a distort is refused. */
    @Test
    public void ctrlOverAnEdgeAdvertisesASkew() {
        enterFreeTransform();
        document.update(W, H);
        Vector2f edge = box().handleAt(Spot.TOP);
        Vector2f corner = box().handleAt(Spot.TOP_RIGHT);
        assertNotNull(edge);
        assertNotNull(corner);

        assertEquals(Cursor.SKEW, TransformBox.cursorFor(box().grip(edge.x, edge.y, true)));
        assertEquals("without the modifier it is still a scale",
                Cursor.NS_RESIZE, TransformBox.cursorFor(box().grip(edge.x, edge.y, false)));
        assertEquals("a free corner is not affine, so Ctrl there stays a scale",
                Cursor.NESW_RESIZE, TransformBox.cursorFor(box().grip(corner.x, corner.y, true)));
    }

    /**
     * <b>A gesture never outlives the box it was made on.</b>
     *
     * <p>The drag flag used to survive {@code close()}, so a drag that ended without a release — which
     * {@code SurfaceMode} produces for any drag finished off the canvas — left the next Ctrl+T opening
     * onto a box that believed it was mid-drag. The per-frame cursor is skipped while dragging, so every
     * cursor on the canvas stayed dead for the rest of the session.</p>
     */
    @Test
    public void aBoxNeverOpensBelievingItIsMidDrag() {
        enterFreeTransform();
        document.update(W, H);
        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().setDragging(true);

        // Left without a release, exactly as a drag off the edge of the canvas leaves it.
        box().commit();
        editor.surface().modes().use(TreeSelectTool.ID);
        document.update(W, H);

        editor.selection().selectOnly(node);
        enterFreeTransform();
        document.update(W, H);
        assertTrue("the box did not reopen", box().isActive());
        assertEquals("a fresh box is not holding a gesture", Kind.NONE, box().gesture().grip().kind());
    }

    /**
     * <b>Ctrl+Z steps back one adjustment and stays in the box.</b>
     *
     * <p>Photoshop's rule. It cannot be the document's undo: the whole transform is a single edit written
     * on commit, so while the box is up nothing of it has reached the document — a Ctrl+Z falling through
     * would undo whatever happened BEFORE the transform started.</p>
     */
    @Test
    public void ctrlZStepsBackOneAdjustmentWithoutLeaving() {
        long clean = model.version();
        enterFreeTransform();
        document.update(W, H);

        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().gesture().scaleTo(new Vector2f(200f, 100f), false, false);
        box().release();
        float afterFirst = box().gesture().scaleX();

        box().press(new Grip(Kind.ROTATE, Spot.TOP_RIGHT));
        box().gesture().rotateBy(0.5f, false);
        box().release();
        assertEquals(2, box().history().undoDepth());

        assertTrue(box().history().undo());
        assertEquals("the rotation should be gone", 0f, box().gesture().rotation(), 0.001f);
        assertEquals("and the scale before it untouched", afterFirst, box().gesture().scaleX(), 0.001f);
        assertTrue("the box has to stay up", box().isActive());
        assertEquals("and nothing may reach the document", clean, model.version());

        assertTrue(box().history().undo());
        assertTrue("back to where it opened", box().gesture().isIdentity());
        assertFalse("nothing left to step back", box().history().undo());

        assertTrue("and forward again", box().history().redo());
        assertEquals(afterFirst, box().gesture().scaleX(), 0.001f);
    }

    /** A press that turns out to be a click must not cost a step. */
    @Test
    public void aPressThatMovedNothingIsNotAStep() {
        enterFreeTransform();
        document.update(W, H);

        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().release();

        assertEquals(0, box().history().undoDepth());
        assertFalse(box().history().undo());
    }

    /**
     * <b>A scrub on the bar is one step of the box's history, and Mod+Z steps it back from the field the
     * scrub left focused</b> — through the ordinary {@code edit.undo}, which finds the tool's history while
     * it is current, so nothing reaches the document.
     */
    @Test
    public void aScrubIsOneStepAndModZStepsItBackFromTheFocusedField() {
        long clean = model.version();
        enterFreeTransform();
        document.update(W, H);
        NumberControl angle = editor.options().fieldFor(Kind.ROTATE);
        int[] at = centreOf(angle.parentElement().children().get(0));

        press(at[0], at[1]);
        move(at[0] + 20, at[1]);
        move(at[0] + 40, at[1]);
        release(at[0] + 40, at[1]);
        float scrubbed = box().gesture().rotation();
        assertNotEquals("the scrub turned the box", 0f, scrubbed, 1e-6f);
        assertEquals("a whole scrub is one step", 1, box().history().undoDepth());
        assertTrue("and it left the field focused", angle.field().isFocused());

        assertTrue("Mod+Z was not handled", chord(CgKeyCodes.KEY_Z, CgModifiers.CTRL));
        releaseModifiers();
        assertEquals("Mod+Z from the field stepped the box back", 0f, box().gesture().rotation(), 1e-4f);
        assertTrue("the box is still up", box().isActive());
        assertEquals("and the document was never touched", clean, model.version());

        document.focus().requestFocus(editor.surface());
        assertTrue(chord(CgKeyCodes.KEY_Y, CgModifiers.CTRL));
        releaseModifiers();
        assertEquals("Mod+Y from the canvas steps it forward again", scrubbed, box().gesture().rotation(), 1e-4f);
    }

    /**
     * <b>The box's cursor is the canvas's, and stops at its edge.</b>
     *
     * <p>The tool re-decides the cursor every frame from where the pointer last was on the canvas, and a
     * pointer that leaves for another panel reports nowhere — so an override that applied everywhere held
     * the whole window in a move cursor over the inspector. It is withheld out there rather than dropped,
     * so coming back needs no new gesture.</p>
     */
    @Test
    public void theCursorStopsAtTheSurfacesEdge() {
        UIElement elsewhere = new UIElement().layout(l -> l.width(200).height(200));
        document.append(elsewhere);
        enterFreeTransform();
        frame();

        // INSIDE, but off the pivot at the middle and clear of the handles: both answer a cursor of
        // their own, and what is being asserted here is the plain one.
        Vector2f left = box().handleAt(Spot.LEFT);
        Vector2f right = box().handleAt(Spot.RIGHT);
        assertNotNull(left);
        assertNotNull(right);
        int[] inside = worldOf(new Vector2f(left.x + (right.x - left.x) * 0.7f, right.y));
        move(inside[0], inside[1]);
        frame();
        assertEquals("inside the box, the canvas says what a press would do",
                Cursor.MOVE, document.input().currentCursor());

        int[] outside = centreOf(elsewhere);
        move(outside[0], outside[1]);
        frame();
        assertEquals("another panel is not the canvas's to point at",
                Cursor.DEFAULT, document.input().currentCursor());

        move(inside[0], inside[1]);
        frame();
        assertEquals("and it is back on return", Cursor.MOVE, document.input().currentCursor());
    }

    /**
     * <b>A handle off the page still takes a press.</b>
     *
     * <p>{@code SurfaceMode} offers a press only where the policy says the surface owns it, and outside
     * the artboard {@code TreePolicy} answers TREE — right for a marquee, which belongs to the page, and
     * wrong for a modal box whose handles are wherever the gesture has put them. Rotate an element near
     * the edge and half of them are over empty plane, where they did nothing at all.</p>
     */
    @Test
    public void aModalToolClaimsPressesOffThePage() {
        Artboard artboard = new Artboard(model);
        document.append(artboard);
        UIElement offThePage = new UIElement().layout(l -> l.width(4f).height(4f));
        document.append(offThePage);
        document.update(W, H);

        assertEquals("the gate the box has to be exempt from",
                SurfacePolicy.PressOwner.TREE, new TreePolicy(artboard).ownerOf(offThePage));
        assertTrue("so the tool has to claim everything",
                new FreeTransformTool(editor.surface(), box(), editor.options()).claimsEveryPress());
        assertFalse("and an ordinary tool must not — a marquee belongs to the page",
                new TreeSelectTool(editor.surface()).claimsEveryPress());
    }

    /**
     * <b>What floats over the canvas keeps its own press.</b>
     *
     * <p>A modal tool claims every press on the surface, and the surface is asked by POSITION — so the
     * overflow popover, which hangs off the toolbar and over the plane, had its presses taken by the box
     * underneath: scrubbing a field in it translated the element instead. The same is true of any menu,
     * dropdown or tooltip over a canvas, which is why the rule is the top layer's rather than the bar's.</p>
     */
    @Test
    public void aModalToolYieldsToWhatFloatsOverTheCanvas() {
        enterFreeTransform();
        document.update(W, H);
        // INSIDE THE BOX BUT OFF THE PIVOT, which sits at the middle and answers a grip of its own --
        // dragging that moves the crosshair, not the element, so it is no control for this.
        Vector2f left = box().handleAt(Spot.LEFT);
        Vector2f right = box().handleAt(Spot.RIGHT);
        assertNotNull(left);
        assertNotNull(right);
        int[] over = worldOf(new Vector2f(left.x + (right.x - left.x) * 0.7f, right.y));

        UIElement floating = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(over[0] - 20f).top(over[1] - 20f).width(40).height(40));
        document.append(floating);
        document.promote(floating);
        frame();

        press(over[0], over[1]);
        move(over[0] + 30, over[1]);
        release(over[0] + 30, over[1]);

        assertEquals("the press belonged to what was on top, not to the box under it",
                0f, box().gesture().translateX(), 0.01f);
        assertTrue("and the box is still up", box().isActive());

        // THE CONTROL, in the same fixture and at the same point: without something on top the identical
        // drag has to move the box. Otherwise the assertion above passes for any reason the press failed
        // to arrive -- which is most of the ways a press test goes wrong.
        document.demote(floating);
        document.remove(floating);
        frame();

        press(over[0], over[1]);
        move(over[0] + 30, over[1]);
        release(over[0] + 30, over[1]);
        assertTrue("with nothing over it, the same drag is the box's",
                Math.abs(box().gesture().translateX()) > 1f);
    }

    /** <b>The bar shows what a drag did, and a typed number reaches the gesture.</b> */
    @Test
    public void theOptionsBarWorksBothWays() {
        enterFreeTransform();
        document.update(W, H);
        TransformOptionsBar bar = editor.options();

        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().gesture().scaleTo(new Vector2f(node.box().width() * 2f,
                node.box().height() * 2f), false, false);
        box().release();
        bar.sync();
        assertEquals("the bar has to show what the drag did",
                200d, bar.fieldFor(Kind.SCALE).getValue(), 0.5d);

        // Through the FIELD, which is what typing does: setValue is the programmatic path and
        // deliberately does not announce, or a sync would be read straight back as an edit.
        bar.fieldFor(Kind.ROTATE).field().setText("90");
        assertEquals("and a typed angle has to reach the gesture",
                Math.PI / 2d, box().gesture().rotation(), 0.001d);
        assertTrue("nothing typed may reach the document either", box().isActive());
    }

    /**
     * <b>The grid is the pivot</b>: a cell puts it there without moving the box, and a typed scale then
     * holds it still.
     *
     * <p>A drag knows which handle it grabbed and holds the opposite edge; a typed 300% knows nothing, so it
     * scales about the pivot — Photoshop's reference point, which is what the grid places.</p>
     */
    @Test
    public void theGridPlacesThePivotAndATypedScaleHoldsIt() {
        enterFreeTransform();
        document.update(W, H);
        TransformOptionsBar bar = editor.options();
        Vector2f corner = box().gesture().apply(0f, 0f);

        bar.placePivot(-1, -1);
        assertEquals("the pivot is on the top-left", 0f, box().gesture().originX(), 0.01f);
        assertEquals(0f, box().gesture().originY(), 0.01f);
        Vector2f placed = box().gesture().apply(0f, 0f);
        assertEquals("and putting it there did not move the box", corner.x, placed.x, 0.01f);
        assertEquals(corner.y, placed.y, 0.01f);

        bar.fieldFor(Kind.SCALE).field().setText("300");
        Vector2f after = box().gesture().apply(0f, 0f);
        assertEquals("a typed scale holds the pivot still", corner.x, after.x, 0.01f);
        assertEquals(corner.y, after.y, 0.01f);
        assertEquals(3f, box().gesture().scaleX(), 0.01f);
    }

    /**
     * <b>X and Y are the element's own movement, and choosing a reference point is not one.</b>
     *
     * <p>They were the translate, which placing the pivot rewrites in order to hold the box still — so the
     * numbers jumped by that compensation while nothing on screen moved, and two cells on one motionless
     * box disagreed for no reason a designer could see. Measured at the centre, because a corner swings
     * when the box turns in place and that is not a move either.</p>
     */
    @Test
    public void theBarsPositionIsTheElementsOwnMovement() {
        enterFreeTransform();
        document.update(W, H);
        TransformOptionsBar bar = editor.options();
        bar.sync();
        assertEquals("nothing has moved it yet", 0d, bar.fieldFor(Kind.MOVE).getValue(), 0.01d);

        // Turned about the middle, where the box stays put. About a corner it would genuinely swing, and
        // reporting that is the point of measuring the element rather than the transform's own numbers.
        bar.fieldFor(Kind.ROTATE).field().setText("30");
        assertEquals("turning in place is not a move", 0d, bar.fieldFor(Kind.MOVE).getValue(), 0.5d);

        bar.placePivot(-1, -1);
        assertEquals("nor is choosing a cell", 0d, bar.fieldFor(Kind.MOVE).getValue(), 0.01d);

        bar.fieldFor(Kind.MOVE).field().setText("40");
        float half = box().gesture().width() * 0.5f;
        assertEquals("typed, it moves the element itself",
                40f, box().gesture().apply(half, box().gesture().height() * 0.5f).x - half, 0.01f);
    }

    /**
     * <b>The pivot has numbers of its own</b> — a percentage of the box, so the nine cells are the round
     * values of the same pair — and typing one moves it without moving the box.
     */
    @Test
    public void thePivotHasItsOwnNumbers() {
        enterFreeTransform();
        document.update(W, H);
        TransformOptionsBar bar = editor.options();
        bar.sync();
        assertEquals("it starts in the middle", 50d, bar.fieldFor(Kind.PIVOT).getValue(), 0.01d);

        bar.placePivot(1, 1);
        assertEquals("a cell is the same pair, rounder", 100d, bar.fieldFor(Kind.PIVOT).getValue(), 0.01d);

        // TYPED LAST, because a field holding an edit nobody landed is left alone by every sync -- the
        // config kit's rule, so asserting a readout after typing into it would assert the typing.
        Vector2f corner = box().gesture().apply(0f, 0f);
        bar.fieldFor(Kind.PIVOT).field().setText("0");
        assertEquals("typed to the left edge", 0f, box().gesture().originX(), 0.01f);
        assertEquals("and the box stayed where it was", corner.x, box().gesture().apply(0f, 0f).x, 0.01f);
    }

    /**
     * <b>A scrub is worth the same wherever the field started.</b>
     *
     * <p>The rate used to follow the value's magnitude, so the same 120 pixels of hand moved the pivot
     * 3.6% from zero and 36% from a hundred — the bottom of every field on this bar, which is where they
     * all begin, crawled badly enough to read as the number being stuck rather than slow.</p>
     */
    @Test
    public void aScrubsRateDoesNotDependOnWhereItStarted() {
        enterFreeTransform();
        document.update(W, H);
        NumberControl pivotX = editor.options().fieldFor(Kind.PIVOT);

        assertEquals("sixty pixels is sixty percent, from zero", 60d, scrubBy(pivotX, 0d, 60), 0.5d);
        assertEquals("and the same sixty from halfway", 110d, scrubBy(pivotX, 50d, 60), 0.5d);
    }

    /** Drags a field's label right by {@code pixels}, from {@code from}, and answers where it landed. */
    private double scrubBy(NumberControl control, double from, int pixels) {
        control.setValue(from);
        int[] at = centreOf(control.parentElement().children().get(0));
        press(at[0], at[1]);
        move(at[0] + 8, at[1]);
        move(at[0] + pixels, at[1]);
        Double landed = control.getValue();
        release(at[0] + pixels, at[1]);
        return landed == null ? 0d : landed;
    }

    /** <b>Linked, W takes H with it at the ratio the two had</b> — Photoshop's chain. Unlinked, each is its own. */
    @Test
    public void linkedWidthAndHeightScaleTogether() {
        enterFreeTransform();
        document.update(W, H);
        TransformOptionsBar bar = editor.options();

        bar.fieldFor(Kind.SCALE).field().setText("200");
        assertEquals("unlinked, H stays", 1f, box().gesture().scaleY(), 0.001f);

        bar.setLinked(true);
        bar.fieldFor(Kind.SCALE).field().setText("300");
        assertEquals(3f, box().gesture().scaleX(), 0.001f);
        assertEquals("linked, H keeps the ratio the two had", 1.5f, box().gesture().scaleY(), 0.001f);
    }

    /**
     * <b>A number typed over the canvas lands in the bar and survives the frame; Enter lands it, and the
     * next Enter commits the transform</b> — Photoshop's options bar.
     *
     * <p>Through {@code consumeKeyboardEvent}, the character arriving after its key as GLFW sends it, with
     * a whole frame between keystrokes: the bar re-reads the box every frame, and a field it overwrote on
     * each one could not be typed into at all.</p>
     */
    @Test
    public void aNumberTypedOverTheCanvasLandsOnEnterAndTheNextEnterCommits() {
        enterFreeTransform();
        document.frame(0f, W, H);
        box().press(new Grip(Kind.ROTATE, null));
        box().release();

        typeOverTheCanvas("45");
        NumberControl angle = editor.options().fieldFor(Kind.ROTATE);
        assertEquals("the frame overwrote what was typed", "45", angle.field().getText());

        key(CgKeyCodes.KEY_RETURN, true);
        assertTrue("the first Enter lands the number and leaves the box up", box().isActive());
        assertEquals(Math.PI / 4d, box().gesture().rotation(), 0.001d);
        assertFalse("and hands the keyboard back", angle.field().isFocused());

        key(CgKeyCodes.KEY_RETURN, true);
        assertFalse("the next Enter commits", box().isActive());
        enterFreeTransform();
        document.update(W, H);
        assertEquals("and what was typed is what was committed",
                Math.PI / 4d, box().gesture().rotation(), 0.001d);
    }

    /** <b>A number still being typed lands when the tool changes</b>: leaving by any route but Escape keeps the work. */
    @Test
    public void aNumberStillBeingTypedLandsWhenTheToolChanges() {
        enterFreeTransform();
        document.frame(0f, W, H);
        box().press(new Grip(Kind.ROTATE, null));
        box().release();

        typeOverTheCanvas("30");
        editor.surface().modes().use(TreeSelectTool.ID);
        enterFreeTransform();
        document.update(W, H);
        assertEquals("the typed number was dropped with the tool",
                Math.PI / 6d, box().gesture().rotation(), 0.001d);
    }

    /** <b>Opening Free Transform swaps the toolbar row's page, and the canvas under it stays put.</b> */
    @Test
    public void openingFreeTransformDoesNotMoveTheCanvas() {
        withDefaultStyles();
        document.update(W, H);
        float before = editor.surface().box().y();
        assertSame(editor.toolbar(), editor.contextToolbar().shown());

        enterFreeTransform();
        document.update(W, H);
        assertSame(editor.options(), editor.contextToolbar().shown());
        assertEquals("the canvas moved when the numbers appeared",
                before, editor.surface().box().y(), 0.01f);

        editor.surface().modes().use(TreeSelectTool.ID);
        assertSame(editor.toolbar(), editor.contextToolbar().shown());
    }

    /** Each digit as its key and then its character, with a frame after each. */
    private void typeOverTheCanvas(String digits) {
        for (char c : digits.toCharArray()) {
            key(c == '0' ? CgKeyCodes.KEY_0 : CgKeyCodes.KEY_1 + (c - '1'), true);
            document.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(c, 0, true, false, 0L));
            document.frame(0f, W, H);
        }
    }

    /** <b>Transform Again gives the next element the last one's treatment.</b> */
    @Test
    public void transformAgainRepeatsTheLastCommit() {
        UIElement second = new UIElement().layout(l -> l.width(60f).height(30f));
        model.root().append(second);
        document.update(W, H);

        assertFalse("nothing committed yet", box().hasSomethingToRepeat());
        enterFreeTransform();
        document.update(W, H);
        box().gesture().press(new Grip(Kind.ROTATE, null));
        box().gesture().rotateBy(0.4f, false);
        box().commit();
        document.update(W, H);

        assertTrue(box().hasSomethingToRepeat());
        assertTrue(box().transformAgain(second));
        document.update(W, H);

        Transform repeated = second.getStyle().computed().get(StylePropertyRegistry.TRANSFORM);
        assertNotNull("the second element got nothing", repeated);
        assertEquals("and it is the same transform", written().toString(), repeated.toString());

        LengthPercent pivot =
                second.getStyle().computed().get(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
        assertNotNull(pivot);
        assertEquals("the pivot has to be a fraction of the NEW box, not the old one's pixels",
                30f, pivot.resolve(60f), 0.5f);
    }

    /**
     * <b>Convert to Size rewrites a scale as width and height.</b>
     *
     * <p>Narrow on purpose: a scale on something that MOVES is what the property is for, so only a
     * transform that is nothing but a scale is offered the conversion.</p>
     */
    @Test
    public void convertToSizeRewritesAScaleAsABox() {
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(),
                g -> g.transform(Transform.scale(2f, 3f)));
        document.update(W, H);
        float width = node.box().width();
        float height = node.box().height();

        assertTrue(TransformBox.isScaleStandingInForSize(node));
        assertTrue(box().convertToSize(node));
        document.update(W, H);

        assertEquals("the size the scale was faking", width * 2f, node.box().width(), 0.5f);
        assertEquals(height * 3f, node.box().height(), 0.5f);
        assertTrue("and the transform is gone", written() == null || written().isIdentity());
        assertFalse("so there is nothing left to convert", TransformBox.isScaleStandingInForSize(node));
    }

    /** A rotation is not a size standing in for anything, so it is left alone. */
    @Test
    public void aRotationIsNotOfferedTheConversion() {
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(),
                g -> g.transform(Transform.rotate(0.4f)));
        document.update(W, H);

        assertFalse(TransformBox.isScaleStandingInForSize(node));
        assertFalse(box().convertToSize(node));
    }

    /** Leaving the tool keeps the work — Photoshop's rule, and the safe one. */
    @Test
    public void switchingToolsCommits() {
        enterFreeTransform();
        rotateLive();

        editor.surface().modes().use(TreeSelectTool.ID);
        document.update(W, H);

        assertFalse(box().isActive());
        assertNotNull("switching tools threw the gesture away", written());
        assertFalse(written().isIdentity());
    }
}
