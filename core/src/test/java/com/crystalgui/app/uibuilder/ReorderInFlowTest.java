package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import org.joml.Vector2f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.app.uibuilder.canvas.DropIndicator;
import com.crystalgui.app.uibuilder.canvas.DropResolver;
import com.crystalgui.app.uibuilder.canvas.ReorderInFlow;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.dnd.SortPlacement;
import com.crystalgui.widget.text.UIText;

/**
 * <b>L4.6 — an in-flow node dragged to another place in the tree.</b>
 *
 * <p>Through the real pointer: a press on the canvas, travel past the threshold, a release. What is asserted
 * is what the file ends up saying and what a user sees while dragging — the carried node dimmed where it
 * stands and never a second copy of it, one undo step for the drop, and everything put back by Escape.</p>
 */
public class ReorderInFlowTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private UIBuilderView editor;
    private Disposable commands;

    private UIElement groupA;
    private UIElement a1;
    private UIElement groupB;
    private UIElement b1;
    private UIElement b2;

    @Before
    public void openABuilder() {
        UIElementRegistry.bootstrap();
        commands = BuilderCommands.register();
        model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:reorder.cgui");
        groupA = new UIElement().layout(l -> l.width(200).height(60));
        a1 = new UIElement().setId("a1").layout(l -> l.width(100).height(20));
        groupB = new UIElement().layout(l -> l.width(200).height(80));
        b1 = new UIElement().setId("b1").layout(l -> l.width(100).height(20));
        b2 = new UIElement().setId("b2").layout(l -> l.width(100).height(20));
        groupA.append(a1);
        groupB.append(b1, b2);
        model.root().append(groupA, groupB);

        editor = new UIBuilderView(model);
        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
    }

    @After
    public void releaseCommands() {
        if (commands != null) commands.dispose();
        releaseModifiers();
    }

    private ReorderInFlow gesture() {
        return editor.reorderGesture();
    }

    /** Where a document node is drawn in this pane; anything else is itself. */
    private UIElement drawn(UIElement node) {
        UIElement shown = editor.shownTree().shown(node);
        return shown != null ? shown : node;
    }

    /** A world point at fractions of where {@code node} is drawn. */
    private float[] at(UIElement node, float fx, float fy) {
        Box box = drawn(node).box();
        Vector2f world = Transform2D.apply(box.localToWorld(), box.width() * fx, box.height() * fy);
        return new float[] {world.x, world.y};
    }

    /** Presses the middle of {@code node} and travels past the threshold, leaving the drag live. */
    private void pickUp(UIElement node) {
        float[] from = at(node, 0.5f, 0.5f);
        press(from[0], from[1]);
        move(from[0] + 6, from[1] + 6);
    }

    private void moveTo(float[] point) {
        move(point[0], point[1]);
    }

    private void releaseAt(float[] point) {
        release(point[0], point[1]);
    }

    /** Inside group B's drop area, below its last child — a drop into it, at the end. */
    private float[] intoGroupBAtTheEnd() {
        return at(groupB, 0.5f, 0.85f);
    }

    @Test
    public void aPressThatDoesNotTravelIsAClick() {
        int depth = model.history().undoDepth();
        float[] centre = at(a1, 0.5f, 0.5f);
        press(centre[0], centre[1]);
        release(centre[0], centre[1]);

        assertEquals("a click wrote something", depth, model.history().undoDepth());
        assertSame(groupA, a1.parentElement());
        assertTrue(gesture().carried().isEmpty());
        assertEquals(List.of(a1), editor.selection().nodes());
    }

    /** The plan's own acceptance case. */
    @Test
    public void theOnlyChildOfAGroupLandsInAnother() {
        pickUp(a1);
        moveTo(intoGroupBAtTheEnd());
        releaseAt(intoGroupBAtTheEnd());

        assertSame(groupB, a1.parentElement());
        assertEquals(List.of(b1, b2, a1), groupB.children());
        assertTrue(groupA.children().isEmpty());
        assertEquals("what landed is what is selected", List.of(a1), editor.selection().nodes());
    }

    /** The other half of the acceptance case: dimmed where it stands, and one of it. */
    @Test
    public void theCarriedNodeIsDimmedInItsCellAndNeverDrawnTwice() {
        pickUp(a1);
        moveTo(intoGroupBAtTheEnd());

        assertEquals(ReorderInFlow.CARRIED_OPACITY, drawn(a1).box().opacity(), 0.001f);
        assertSame("nothing moves before the release", groupA, a1.parentElement());
        assertEquals("a second copy was put in the tree", 3, groupA.children().size() + groupB.children().size());

        releaseAt(intoGroupBAtTheEnd());
        assertEquals("the dimming outlived the drag", 1f, drawn(a1).box().opacity(), 0.001f);
    }

    @Test
    public void oneUndoStepPutsItBack() {
        byte[] before = model.encode();
        pickUp(a1);
        moveTo(intoGroupBAtTheEnd());
        releaseAt(intoGroupBAtTheEnd());

        assertTrue(model.history().undo());
        assertSame(groupA, a1.parentElement());
        assertEquals(new String(before, StandardCharsets.UTF_8), new String(model.encode(), StandardCharsets.UTF_8));
    }

    /** GrapesJS's band: a pointer in a container's edge means beside it, further in means into it. */
    @Test
    public void theEdgeBandMeansBesideAndTheInsideMeansInto() {
        pickUp(a1);

        moveTo(at(groupB, 0.5f, 0.02f));
        DropResolver.Drop beside = gesture().drop();
        assertNotNull(beside);
        assertSame("in the band, the drop is the root's", model.root(), beside.target());
        assertEquals("before group B", model.root().indexOf(groupB), beside.index());

        moveTo(at(groupB, 0.5f, 0.5f));
        DropResolver.Drop into = gesture().drop();
        assertNotNull(into);
        assertSame(groupB, into.target());
        assertNotNull("the indicator draws a line for it", DropIndicator.lineOf(editor.surface().dropIndicator().drop()));

        release(0, 0);
    }

    @Test
    public void escapePutsEverythingBack() {
        int depth = model.history().undoDepth();
        pickUp(a1);
        moveTo(intoGroupBAtTheEnd());
        key(CgKeyCodes.KEY_ESCAPE, true);
        releaseAt(intoGroupBAtTheEnd());

        assertSame(groupA, a1.parentElement());
        assertEquals(depth, model.history().undoDepth());
        assertEquals(1f, drawn(a1).box().opacity(), 0.001f);
        assertNull("the indicator stayed up", editor.surface().dropIndicator().drop());
    }

    /** Alt at release copies; the original stays and the copy's id is its own. */
    @Test
    public void altDuplicates() {
        pickUp(a1);
        TestPlatformService.install();
        TestPlatformService.holdModifiers(CgModifiers.ALT);
        moveTo(intoGroupBAtTheEnd());
        releaseAt(intoGroupBAtTheEnd());
        releaseModifiers();

        assertSame("the original moved", groupA, a1.parentElement());
        assertEquals(3, groupB.children().size());
        UIElement copy = groupB.children().get(2);
        assertNotSame(a1, copy);
        assertFalse("the copy shares the original's id", copy.id().equals(a1.id()));
        assertEquals(List.of(copy), editor.selection().nodes());
    }

    /** The ghost names what is carried: a built-in kind bare, its id, a count for several, a plus for a copy. */
    @Test
    public void theGhostLabelNamesWhatIsCarried() {
        assertEquals("<element> #a1", ReorderInFlow.labelFor(List.of(a1), false));
        assertEquals("+ 2 elements", ReorderInFlow.labelFor(List.of(b1, b2), true));
    }

    /** A text leaf draws its own content: pointing into one lands beside it, never inside. */
    @Test
    public void aTextLeafIsNeverATarget() {
        UIText label = new UIText("label");
        label.layout(l -> l.width(100).height(40));
        groupB.append(label);
        frame();

        DropResolver.Drop drop = DropResolver.forPane(editor.surface())
                .resolve(List.of(a1), at(label, 0.5f, 0.5f)[0], at(label, 0.5f, 0.5f)[1]);
        assertNotNull(drop);
        assertSame(groupB, drop.target());
        assertEquals(SortPlacement.Side.AFTER, drop.against().side());
    }

    /** A drop where nothing may land writes nothing. */
    @Test
    public void aReleaseOffTheDocumentPutsEverythingBack() {
        int depth = model.history().undoDepth();
        pickUp(a1);
        move(-200, -200);
        assertNull(gesture().drop());
        release(-200, -200);

        assertSame(groupA, a1.parentElement());
        assertEquals(depth, model.history().undoDepth());
    }

    /** A dragged selection lands together, in order, and the release does not collapse it to one. */
    @Test
    public void aSelectionMovesTogetherAndStaysSelected() {
        editor.selection().replaceWith(List.of(b1, b2));
        pickUp(b2);
        float[] intoA = at(groupA, 0.5f, 0.85f);
        moveTo(intoA);
        releaseAt(intoA);

        assertEquals(List.of(a1, b1, b2), groupA.children());
        assertEquals(List.of(b1, b2), editor.selection().nodes());
    }

    /**
     * A selection dragged and dropped back where it started stays selected.
     *
     * <p>The drag has ended before its release reaches the select tool, whose own check for "was that a
     * drag" then finds none and collapses a press on an already-selected node to that node.</p>
     */
    @Test
    public void aSelectionDroppedWhereItStartedStaysSelected() {
        editor.selection().replaceWith(List.of(b1, b2));
        float[] centre = at(b2, 0.5f, 0.5f);
        press(centre[0], centre[1]);
        move(centre[0] + 12, centre[1]);
        move(centre[0], centre[1]);
        release(centre[0], centre[1]);

        assertEquals(List.of(b1, b2), groupB.children());
        assertEquals(List.of(b1, b2), editor.selection().nodes());
    }

    /** Held near the viewport's edge, a drag pans the plane every frame; let go, and it stops. */
    @Test
    public void aDragHeldAtTheEdgePansThePlane() {
        UIElement viewport = editor.surface().surface().element();
        pickUp(a1);
        float[] edge = at(viewport, 0.99f, 0.5f);
        moveTo(edge);
        float before = editor.surface().surface().panX();
        frame(1f / 60f);
        frame(1f / 60f);
        float panned = editor.surface().surface().panX();
        assertTrue("the plane did not move toward the edge", panned < before);

        releaseAt(edge);
        frame(1f / 60f);
        assertEquals("panning outlived the drag", panned, editor.surface().surface().panX(), 0.001f);
    }

    /**
     * <b>The Hierarchy panel shows what a drop did, and what undoing it did</b>, with nothing clicked.
     *
     * <p>The node was selected before the drag and still is after, so the selection never changes — the panel
     * has to reveal it because the DOCUMENT changed. Undo is the same: it changes the tree and, until the
     * model announced it, nothing on the panel moved until a click did.</p>
     */
    @Test
    public void theHierarchyRevealsADropAndFollowsItsUndo() {
        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);
        editor.selection().replaceWith(List.of(a1));
        assertFalse("the fixture needs the target folded", hierarchy.tree().isExpanded(b1));

        pickUp(a1);
        float[] intoB1 = at(b1, 0.5f, 0.5f);
        moveTo(intoB1);
        releaseAt(intoB1);
        assertSame(b1, a1.parentElement());

        assertTrue("the moved node's new parent stayed folded", hierarchy.tree().isExpanded(b1));
        assertTrue("the moved node has no row", rowOf(hierarchy, a1) >= 0);
        assertTrue("and its row is not the highlighted one",
                hierarchy.tree().getSelectedIndices().contains(rowOf(hierarchy, a1)));

        assertTrue(model.history().undo());
        assertSame(groupA, a1.parentElement());
        assertEquals("the panel still shows the undone move", rowOf(hierarchy, groupA) + 1, rowOf(hierarchy, a1));
        assertEquals("undoing a drop selects what it moved", List.of(a1), editor.selection().nodes());
    }

    private static int rowOf(HierarchyPanel hierarchy, UIElement node) {
        List<TreeRow<UIElement>> rows = hierarchy.tree().visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() == node) return i;
        }
        return -1;
    }

    /** VS Code's Move Line for a node: along its container, stopping at the end; Copy Line beside it. */
    @Test
    public void altArrowsMoveAndCopyAlongTheContainer() {
        editor.selection().replaceWith(List.of(b1));
        document.focus().requestFocus(editor.surface());

        assertTrue(chord(CgKeyCodes.KEY_DOWN, CgModifiers.ALT));
        releaseModifiers();
        assertEquals(List.of(b2, b1), groupB.children());

        assertTrue(chord(CgKeyCodes.KEY_DOWN, CgModifiers.ALT));
        releaseModifiers();
        assertEquals("the end of the list stops it", List.of(b2, b1), groupB.children());

        assertTrue(chord(CgKeyCodes.KEY_UP, CgModifiers.ALT | CgModifiers.SHIFT));
        releaseModifiers();
        assertEquals(3, groupB.children().size());
        assertSame("the original stays", b1, groupB.children().get(2));
        assertEquals(List.of(groupB.children().get(1)), editor.selection().nodes());
    }
}
