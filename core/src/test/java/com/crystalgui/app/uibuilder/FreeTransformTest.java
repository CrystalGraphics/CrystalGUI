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
import com.crystalgui.app.uibuilder.canvas.TreeSelectTool;
import com.crystalgui.app.uibuilder.canvas.transform.FreeTransformTool;
import com.crystalgui.app.uibuilder.canvas.transform.TransformBox;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Grip;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Kind;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
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
