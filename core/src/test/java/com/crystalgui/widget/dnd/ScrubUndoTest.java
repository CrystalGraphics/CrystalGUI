package com.crystalgui.widget.dnd;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.SetNodeFieldEdit;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.graph.node.NodeFieldBinder;
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
public class ScrubUndoTest extends UiDocumentTestBase {

    private static final float LABEL = 40f;

    private final AtomicLong clock = new AtomicLong();

    private UIText label;

    private GraphDocument graphDocument;
    private String nodeId;
    private UndoStack undo;

    /** A node with one numeric field, mounted with the label wired as its scrub handle. */
    private NumberControl mount() {
        graphDocument = new GraphDocument();
        NodeField field = NodeField.number("Scale", "Scale", "1.0");
        NodeType type = NodeType.of("cg:test/scale").label("Scale").field(field).build();
        NodeData node = graphDocument.addNode(type.create(0f, 0f));
        nodeId = node.id();

        undo = new UndoStack().setClock(clock::get);

        UINode control = NodeFieldBinder.buildControl(field, graphDocument, nodeId, undo, null);
        assertTrue("this fixture depends on a NUMBER field building a NumberControl",
                control instanceof NumberControl);
        NumberControl number = (NumberControl) control;

        label = new UIText("X");
        label.layout(l -> l.width(LABEL).height(LABEL));
        number.scrubWith(label);

        UINode root = new UINode().layout(l -> l.width(400).height(400));
        root.append(label);
        root.append(number);

        document.append(root);
        frame();
        return number;
    }


    /** Physical position of the label's centre, through the element's own transform. */
    private Vector2f centre() {
        var cache = label.box();
        return Transform2D.apply(cache.localToWorld(),
                cache.width() / 2f, cache.height() / 2f);
    }

    private void press() {
        Vector2f at = centre();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, 0, true, 0f, 1L));
        frame();
    }

    private void moveBy(float dx) {
        Vector2f at = centre();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, -1, false, 0f, -1L));
        frame();
    }

    private void release(float dx) {
        Vector2f at = centre();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, 0, false, 0f, 2L));
        frame();
    }

    private String stored() {
        NodeData live = graphDocument.node(nodeId);
        return live == null ? null : live.properties().get("Scale");
    }

    // ── The property ────────────────────────────────────────────────────────

    /**
     * <b>Many frames of movement, one entry, and undo lands on where the drag started.</b>
     *
     * <p>The clock is stepped past the merge document between frames on purpose. A test that let real time
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
        assertNotEquals("the scrub must actually have changed the graphDocument", before, stored());

        undo.undo();
        assertEquals("undo must land on the value the drag started from", before, stored());
    }

    /**
     * <b>A scrub must not be absorbed by the edit before it.</b>
     *
     * <p>The mirror of the test below, and the harder direction. {@code mergeRunOpen} is true after
     * <em>any</em> push, so a gesture starting right after an ordinary edit inherited that run — and when
     * the earlier edit touched the same node and field, which is the natural order (type a value, then
     * drag it), {@code SetNodeFieldEdit.mergeWith} accepted and swallowed the whole drag.</p>
     *
     * <p>The symptom was that Ctrl+Z appeared to undo <em>something earlier</em> rather than the scrub:
     * one merged entry whose {@code before} came from before the typing. Nothing about the scrub looked
     * wrong — the edit existed, the stack was reachable, and the depth was even plausible.</p>
     */
    @Test
    public void aScrubDoesNotMergeIntoTheEditBeforeIt() {
        mount();
        String original = stored();

        // An ordinary edit first — typed, not dragged, so no gesture brackets it.
        undo.execute(SetNodeFieldEdit.of(graphDocument, nodeId, "Scale", "7.0"));
        assertEquals(1, undo.undoDepth());
        String afterTyping = stored();

        press();
        moveBy(60f);
        release(60f);

        assertEquals("the scrub is its own step, not an addendum to the typing", 2, undo.undoDepth());

        undo.undo();
        assertEquals("undoing the scrub must land on the typed value, not on what preceded it",
                afterTyping, stored());

        undo.undo();
        assertEquals("and the step before it is still the typing", original, stored());
    }

    /** The gesture must also close, or the NEXT unrelated edit is folded into the scrub's step. */
    @Test
    public void theNextEditAfterAScrubIsItsOwnStep() {
        mount();

        press();
        moveBy(60f);
        release(60f);
        assertEquals(1, undo.undoDepth());

        undo.execute(SetNodeFieldEdit.of(graphDocument, nodeId, "Scale", "42.0"));
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

    /** Without a hold the document still governs — the typing rule is not damaged by any of this. */
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
