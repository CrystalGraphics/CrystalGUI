package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.crystalgraphics.platform.input.CgCursor;

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
import com.crystalgui.widget.surface.SurfacePolicy;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;

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
        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
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
        box().dragTo(toX, toY, 60f, 30f, false, false);
        float firstX = box().gesture().scaleX();
        float firstY = box().gesture().scaleY();

        for (int i = 0; i < 8; i++) box().dragTo(toX, toY, 60f, 30f, false, false);

        assertEquals("the scale walked while the pointer stood still", firstX,
                box().gesture().scaleX(), 0.0001f);
        assertEquals(firstY, box().gesture().scaleY(), 0.0001f);
        assertTrue("dragging the corner out has to make it bigger, not smaller", firstX > 1f);
        assertTrue(firstY > 1f);
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
        box().dragTo(handle.x + 50f, handle.y + 40f, 50f, 40f, false, false);
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

        assertEquals(CgCursor.SKEW, TransformBox.cursorFor(box().grip(edge.x, edge.y, true)));
        assertEquals("without the modifier it is still a scale",
                CgCursor.NS_RESIZE, TransformBox.cursorFor(box().grip(edge.x, edge.y, false)));
        assertEquals("a free corner is not affine, so Ctrl there stays a scale",
                CgCursor.NESW_RESIZE, TransformBox.cursorFor(box().grip(corner.x, corner.y, true)));
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
        assertEquals(2, box().undoDepth());

        assertTrue(box().undoStep());
        assertEquals("the rotation should be gone", 0f, box().gesture().rotation(), 0.001f);
        assertEquals("and the scale before it untouched", afterFirst, box().gesture().scaleX(), 0.001f);
        assertTrue("the box has to stay up", box().isActive());
        assertEquals("and nothing may reach the document", clean, model.version());

        assertTrue(box().undoStep());
        assertTrue("back to where it opened", box().gesture().isIdentity());
        assertFalse("nothing left to step back", box().undoStep());

        assertTrue("and forward again", box().redoStep());
        assertEquals(afterFirst, box().gesture().scaleX(), 0.001f);
    }

    /** A press that turns out to be a click must not cost a step. */
    @Test
    public void aPressThatMovedNothingIsNotAStep() {
        enterFreeTransform();
        document.update(W, H);

        box().press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        box().release();

        assertEquals(0, box().undoDepth());
        assertFalse(box().undoStep());
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
                new FreeTransformTool(editor.surface(), box()).claimsEveryPress());
        assertFalse("and an ordinary tool must not — a marquee belongs to the page",
                new TreeSelectTool(editor.surface()).claimsEveryPress());
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
     * <b>The reference widget decides what a typed number holds still.</b>
     *
     * <p>A drag knows which handle it grabbed and holds the opposite edge; a typed 300% knows nothing, so
     * the 3×3 grid is the answer and the bar applies it.</p>
     */
    @Test
    public void theReferencePointHoldsStillWhenTyping() {
        enterFreeTransform();
        document.update(W, H);
        TransformOptionsBar bar = editor.options();
        bar.setAnchor(-1, -1);

        Vector2f before = box().gesture().apply(0f, 0f);
        bar.fieldFor(Kind.SCALE).field().setText("300");
        Vector2f after = box().gesture().apply(0f, 0f);

        assertEquals("the top-left was chosen, so the top-left stays", before.x, after.x, 0.01f);
        assertEquals(before.y, after.y, 0.01f);
        assertEquals(3f, box().gesture().scaleX(), 0.01f);
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
