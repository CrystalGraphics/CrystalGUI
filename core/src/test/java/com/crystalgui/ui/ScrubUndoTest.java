package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.SetNodeFieldEdit;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.config.control.NumberControl;
import com.crystalgui.ui.elements.graph.NodeFieldBinder;
import org.joml.Vector2f;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * P6.3.10 — <b>a whole scrub is one undo step.</b>
 *
 * <h3>The problem this pins</h3>
 * <p>{@code NodeFieldBinder} records an edit on every change, which is right for typing and picking. A
 * scrub emits a value <em>per frame</em>, so without the gesture bracket a two-second drag puts ~120
 * entries on the stack and Ctrl+Z stops meaning anything. The values still have to arrive live, or the
 * node preview would not recompile until the button came up — so "record only at the end" is not
 * available either.</p>
 *
 * <p>The two together are what a held merge run buys. This drives the real binder, the real control and
 * the real stack rather than asserting on the mechanism in isolation, because the failure mode is a
 * <em>wiring</em> one: every piece can be correct while nothing connects them.</p>
 */
public class ScrubUndoTest extends UiTestBase {

    private static final float LABEL = 40f;

    private final AtomicLong clock = new AtomicLong();

    private UIWindow window;
    private UIText label;

    private GraphDocument document;
    private String nodeId;
    private UndoStack undo;

    /** A node with one numeric field, mounted with the label wired as its scrub handle. */
    private NumberControl mount() {
        document = new GraphDocument();
        NodeField field = NodeField.number("Scale", "Scale", "1.0");
        NodeType type = NodeType.of("cg:test/scale").label("Scale").field(field).build();
        NodeData node = document.addNode(type.create(0f, 0f));
        nodeId = node.id();

        undo = new UndoStack().setClock(clock::get);

        UIElement control = NodeFieldBinder.buildControl(field, document, nodeId, undo, null);
        assertTrue("this fixture depends on a NUMBER field building a NumberControl",
                control instanceof NumberControl);
        NumberControl number = (NumberControl) control;

        label = new UIText("X");
        label.layout(l -> l.width(LABEL).height(LABEL));
        number.scrubWith(label);

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(label);
        root.addChild(number);

        window = new UIWindow(Ui.of(root));
        window.init(400, 400);
        frame();
        return number;
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** Physical position of the label's centre, through the element's own transform. */
    private Vector2f centre() {
        var cache = label.getRuntimeCache();
        return Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() / 2f, cache.getY() + cache.getHeight() / 2f);
    }

    private void press() {
        Vector2f at = centre();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, 0, true, 0f, 1L));
        frame();
    }

    private void moveBy(float dx) {
        Vector2f at = centre();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, -1, false, 0f, -1L));
        frame();
    }

    private void release(float dx) {
        Vector2f at = centre();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, 0, false, 0f, 2L));
        frame();
    }

    private String stored() {
        NodeData live = document.node(nodeId);
        return live == null ? null : live.properties().get("Scale");
    }

    // ── The property ────────────────────────────────────────────────────────

    /**
     * <b>Many frames of movement, one entry, and undo lands on where the drag started.</b>
     *
     * <p>The clock is stepped past the merge window between frames on purpose. A test that let real time
     * pass would coalesce for the wrong reason — because the frames happened to be close together — and
     * would keep passing if the hold were deleted. Stepping it proves the hold is what is doing the work.
     */
    @Test
    public void aWholeScrubIsOneUndoStep() {
        mount();
        String before = stored();

        press();
        for (float dx = 10f; dx <= 120f; dx += 10f) {
            clock.addAndGet(UndoStack.DEFAULT_MERGE_WINDOW_MILLIS * 3);   // "the user paused to look"
            moveBy(dx);
        }
        release(120f);

        assertEquals("a scrub must cost exactly one undo step", 1, undo.undoDepth());
        assertNotEquals("the scrub must actually have changed the document", before, stored());

        undo.undo();
        assertEquals("undo must land on the value the drag started from", before, stored());
    }

    /** The gesture must also close, or the NEXT unrelated edit is folded into the scrub's step. */
    @Test
    public void theNextEditAfterAScrubIsItsOwnStep() {
        mount();

        press();
        moveBy(60f);
        release(60f);
        assertEquals(1, undo.undoDepth());

        undo.execute(SetNodeFieldEdit.of(document, nodeId, "Scale", "42.0"));
        assertEquals("a later edit must not merge into the finished gesture", 2, undo.undoDepth());
    }

    /** Nothing recorded for a press that never travelled — a click is not an edit. */
    @Test
    public void aClickRecordsNothing() {
        mount();
        press();
        release(1f);
        assertEquals(0, undo.undoDepth());
    }

    // ── The mechanism, in isolation ─────────────────────────────────────────

    /**
     * A held run ignores the clock; an unheld one does not. Stated directly because the widget test above
     * can only show the combination, and this is the half that is easy to break while everything still
     * looks green.
     */
    @Test
    public void aHeldMergeRunOutlastsTheWindow() {
        GraphDocument doc = new GraphDocument();
        NodeType type = NodeType.of("cg:test/n").field(NodeField.number("V", "V", "0.0")).build();
        NodeData node = doc.addNode(type.create(0f, 0f));
        UndoStack stack = new UndoStack().setClock(clock::get);

        stack.beginMergeRun();
        stack.execute(SetNodeFieldEdit.of(doc, node.id(), "V", "1"));
        clock.addAndGet(UndoStack.DEFAULT_MERGE_WINDOW_MILLIS * 10);
        stack.execute(SetNodeFieldEdit.of(doc, node.id(), "V", "2"));
        assertEquals("a held run must not be broken by a pause", 1, stack.undoDepth());
        assertTrue(stack.isMergeRunHeld());

        stack.endMergeRun();
        assertFalse(stack.isMergeRunHeld());
        stack.execute(SetNodeFieldEdit.of(doc, node.id(), "V", "3"));
        assertEquals("ending the run must start a fresh step", 2, stack.undoDepth());
    }

    /** Without a hold the window still governs — the typing rule is not damaged by any of this. */
    @Test
    public void anUnheldRunStillBreaksOnAPause() {
        GraphDocument doc = new GraphDocument();
        NodeType type = NodeType.of("cg:test/n").field(NodeField.number("V", "V", "0.0")).build();
        NodeData node = doc.addNode(type.create(0f, 0f));
        UndoStack stack = new UndoStack().setClock(clock::get);

        stack.execute(SetNodeFieldEdit.of(doc, node.id(), "V", "1"));
        clock.addAndGet(UndoStack.DEFAULT_MERGE_WINDOW_MILLIS * 10);
        stack.execute(SetNodeFieldEdit.of(doc, node.id(), "V", "2"));
        assertEquals(2, stack.undoDepth());
    }

    /** Nesting: an inner hold must not close the outer one's run. */
    @Test
    public void holdsNest() {
        UndoStack stack = new UndoStack();
        stack.beginMergeRun();
        stack.beginMergeRun();
        stack.endMergeRun();
        assertTrue("an inner endMergeRun must not release the outer hold", stack.isMergeRunHeld());
        stack.endMergeRun();
        assertFalse(stack.isMergeRunHeld());
    }

    /** An edit type that refuses to merge is unaffected by a hold — the hold relaxes the clock, it does
     * not force unrelated edits together. */
    @Test
    public void aHoldDoesNotForceUnmergeableEditsTogether() {
        UndoStack stack = new UndoStack();
        stack.beginMergeRun();
        stack.push(refusesToMerge());
        stack.push(refusesToMerge());
        assertEquals(2, stack.undoDepth());
        stack.endMergeRun();
    }

    private static Edit refusesToMerge() {
        return new Edit() {
            @Override public void apply() { }
            @Override public void undo() { }
        };
    }
}
