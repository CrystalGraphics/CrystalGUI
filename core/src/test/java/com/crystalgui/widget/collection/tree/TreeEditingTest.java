package com.crystalgui.widget.collection.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.render.texture.CgUiGradient;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.text.UIText;

/**
 * <b>The tree-editing kit decides; the model performs.</b> Which items a verb acts on, where a paste or a
 * drop lands, and whether it moves or copies — against a model double that only records.
 */
public class TreeEditingTest extends UiDocumentTestBase {

    /** A node of the double's tree. Identity is the node. */
    static final class Node {
        final String name;
        final boolean container;
        Node parent;
        final List<Node> children = new ArrayList<>();

        Node(String name, boolean container, Node... kids) {
            this.name = name;
            this.container = container;
            for (Node kid : kids) {
                kid.parent = this;
                children.add(kid);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Records every verb and performs nothing. */
    static final class RecordingModel implements TreeEditModel<Node> {
        boolean ordered = true;
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean isOrdered() {
            return ordered;
        }

        @Override
        public Node parentOf(Node node) {
            return node.parent;
        }

        @Override
        public int indexOf(Node node) {
            return node.parent == null ? -1 : node.parent.children.indexOf(node);
        }

        @Override
        public boolean isContainer(Node node) {
            return node.container;
        }

        @Override
        public boolean canEdit(Node node) {
            return node.parent != null;
        }

        @Override
        public String nameOf(Node node) {
            return node.name;
        }

        @Override
        public void move(List<Node> nodes, Target<Node> to) {
            calls.add("move " + nodes + " -> " + to.parent() + "@" + to.index());
        }

        @Override
        public void copy(List<Node> nodes, Target<Node> to) {
            calls.add("copy " + nodes + " -> " + to.parent() + "@" + to.index());
        }

        @Override
        public void delete(List<Node> nodes) {
            calls.add("delete " + nodes);
        }

        @Override
        public void rename(Node node, String name) {
            calls.add("rename " + node + " " + name);
        }
    }

    /** root → a (→ a1), b (empty container), c (leaf) */
    private Node a1, a, b, c, root;
    private TreeView<Node> tree;
    private TreeEditing<Node> editing;
    private RecordingModel model;

    @Before
    public void build() {
        a1 = new Node("a1", false);
        a = new Node("a", true, a1);
        b = new Node("b", true);
        c = new Node("c", false);
        root = new Node("root", true, a, b, c);

        tree = new TreeView<>(new TreeDataSource<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(root);
            }

            @Override
            public List<Node> children(Node parent) {
                return parent.children;
            }

            @Override
            public boolean hasChildren(Node node) {
                return !node.children.isEmpty();
            }
        });
        tree.setItemHeight(10f);
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        tree.layout(l -> l.width(100).height(100));
        UIElement host = new UIElement().layout(l -> l.width(100).height(100));
        host.append(tree);
        editing = new TreeEditing<>(tree, host, this::itemForRow, tree::refresh, new TreeClipboard<>());
        model = new RecordingModel();
        editing.setModel(model);
        tree.setRenderer(new TreeRenderer<Node>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                UIText label = new UIText("");
                TextField field = new TextField();
                row.append(label, field);
                editing.installRow(row, null, label, field);
                return row;
            }

            @Override
            public void bind(Node node, TreeRow<Node> row, int index, UIElement template) {
                UIText label = (UIText) template.children().get(0);
                label.setText(node.name);
                editing.bindRow(template, label, (TextField) template.children().get(1), node);
            }
        });
        tree.setExpanded(root, true);
        tree.setExpanded(a, true);
        document.append(host);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private Node itemForRow(UIElement row) {
        int index = tree.indexOfRowElement(row);
        TreeRow<Node> at = index < 0 ? null : tree.rowAt(index);
        return at == null ? null : at.item();
    }

    private int rowOf(Node node) {
        List<TreeRow<Node>> rows = tree.visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() == node) return i;
        }
        throw new AssertionError("no row for " + node);
    }

    private void selectOnly(Node... nodes) {
        tree.select(rowOf(nodes[0]));
        for (int i = 1; i < nodes.length; i++) tree.toggle(rowOf(nodes[i]));
    }

    private UIElement rowElement(Node node) {
        UIElement row = tree.realisedRows().get(rowOf(node));
        assertNotNull(node + " is not realised", row);
        return row;
    }

    // ── The clipboard ───────────────────────────────────────────────────────────────────────────

    @Test
    public void aCutDimsItsRowsAndIsPerformedByThePasteAsAMove() {
        selectOnly(b);
        editing.cut();
        settle();
        assertTrue("the cut row is not marked", rowElement(b).hasClass(TreeEditing.CUT_CLASS));
        assertTrue("a cut moved something before the paste", model.calls.isEmpty());

        selectOnly(a1);
        assertTrue(editing.canPaste());
        editing.paste();
        assertEquals(List.of("move [b] -> a@1"), model.calls);
        assertTrue("a cut survives the paste that spent it", editing.clipboard().isEmpty());
    }

    @Test
    public void aCopyPastesACopyAndStaysForTheNextPaste() {
        selectOnly(a1);
        editing.copy();
        selectOnly(c);
        editing.paste();
        editing.paste();
        assertEquals(List.of("copy [a1] -> root@3", "copy [a1] -> root@3"), model.calls);
    }

    @Test
    public void aPasteNeverLandsInsideWhatItHolds() {
        selectOnly(a);
        editing.cut();
        selectOnly(a1);
        assertFalse("a cut folder would paste into itself", editing.canPaste());
    }

    // ── The selection ───────────────────────────────────────────────────────────────────────────

    @Test
    public void aSelectionInsideASelectedItemTravelsWithIt() {
        selectOnly(a1, a, c);
        editing.delete();
        assertEquals(List.of("delete [a, c]"), model.calls);
    }

    @Test
    public void duplicateLandsJustAfterTheLastSelectedItem() {
        selectOnly(a, b);
        editing.duplicate();
        assertEquals(List.of("copy [a, b] -> root@2"), model.calls);
    }

    @Test
    public void theRootIsNeverEditable() {
        selectOnly(root);
        assertFalse(editing.canCut());
        assertFalse(editing.canDelete());
        assertFalse(editing.canRename());
    }

    // ── Commands and keys ───────────────────────────────────────────────────────────────────────

    @Test
    public void theCommandsFindTheEditingFromARow() {
        selectOnly(c);
        CommandContext fromRow = CommandContext.of(rowElement(c));
        assertTrue("tree.delete cannot see the tree it was invoked in",
                CommandRegistry.global().get(TreeEditCommands.DELETE).isEnabled(fromRow));

        document.focus().requestFocus(tree);
        assertTrue("Delete on the tree was not handled", keyPress(CgKeyCodes.KEY_DELETE));
        assertEquals(List.of("delete [c]"), model.calls);
    }

    // ── Drops ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anOrderedRowIsBeforeIntoAndAfterByQuarter() {
        TreeDragAndDrop<Node> drops = new TreeDragAndDrop<>(editing, new UIElement());
        UIElement row = rowElement(b);
        assertTarget(drops.spotAt(model, row, b, 0.1f), TreeDragAndDrop.DROP_BEFORE_CLASS, root, 1);
        assertTarget(drops.spotAt(model, row, b, 0.5f), TreeDragAndDrop.DROP_INTO_CLASS, b, -1);
        assertTarget(drops.spotAt(model, row, b, 0.9f), TreeDragAndDrop.DROP_AFTER_CLASS, root, 2);
        // A LEAF HAS NO MIDDLE: its halves are before and after.
        UIElement leaf = rowElement(c);
        assertTarget(drops.spotAt(model, leaf, c, 0.4f), TreeDragAndDrop.DROP_BEFORE_CLASS, root, 2);
        assertTarget(drops.spotAt(model, leaf, c, 0.6f), TreeDragAndDrop.DROP_AFTER_CLASS, root, 3);
        // AFTER AN OPEN FOLDER is its first child, which is the row under the line.
        assertTarget(drops.spotAt(model, rowElement(a), a, 0.9f), TreeDragAndDrop.DROP_AFTER_CLASS, a, 0);
    }

    /**
     * Below a group's last row, X picks the depth: over the row it stays its parent's last child, left of its indent
     * it lands after the parent — pragmatic-drag-and-drop's {@code reparent}.
     */
    @Test
    public void belowAGroupsLastRowThePointersXPicksTheDepth() {
        TreeDragAndDrop<Node> drops = new TreeDragAndDrop<>(editing, new UIElement());
        UIElement last = rowElement(a1);   // root > a > a1, and b follows a
        TreeDragAndDrop.Spot<Node> inside = drops.spotAt(model, last, a1, 0.9f, 100f);
        assertTarget(inside, TreeDragAndDrop.DROP_AFTER_CLASS, a, 1);
        assertEquals("the line starts at a1's own indent", 2, inside.level());
        TreeDragAndDrop.Spot<Node> out = drops.spotAt(model, last, a1, 0.9f, 0f);
        assertTarget(out, TreeDragAndDrop.DROP_AFTER_CLASS, root, 1);
        assertEquals("and one level out when it steps out", 1, out.level());
        // THE ROOT TAKES NO SIBLINGS: c ends root's group, and far left is still inside root.
        assertTarget(drops.spotAt(model, rowElement(c), c, 0.9f, 0f), TreeDragAndDrop.DROP_AFTER_CLASS, root, 3);
    }

    /**
     * Under the last row is outside its group, a level per half row-height further down: dragging under is how a person
     * says "not in it", and a deep group is left one level at a time.
     */
    @Test
    public void theSpaceUnderTheLastRowStepsOutALevelPerHalfRow() {
        // root > a > (a1, x1 > x2), so the tail x2 is three deep and ends x1's group, a's, and nothing of root's.
        Node x2 = new Node("x2", false);
        Node x1 = new Node("x1", true, x2);
        x1.parent = a;
        a.children.add(x1);
        root.children.remove(b);
        root.children.remove(c);
        tree.setExpanded(x1, true);
        tree.refresh();
        settle();
        TreeDragAndDrop<Node> drops = new TreeDragAndDrop<>(editing, new UIElement());
        // Rows root, a, a1, x1, x2 at ten pixels each: x2 ends at 50, and a level is every five pixels below it.
        TreeDragAndDrop.Spot<Node> once = drops.spotFor(tree, 90f, 52f);
        assertNotNull("the space under the rows took no drop", once);
        assertTarget(once, TreeDragAndDrop.DROP_AFTER_CLASS, a, 2);
        assertSame("the line is under the last row", rowElement(x2), once.row());
        assertEquals("just under the row is one level out", 2, once.level());

        TreeDragAndDrop.Spot<Node> twice = drops.spotFor(tree, 90f, 57f);
        assertTarget(twice, TreeDragAndDrop.DROP_AFTER_CLASS, root, 1);
        assertEquals("half a row further, two", 1, twice.level());
        // THE ROOT TAKES NO SIBLINGS, however far down.
        assertTarget(drops.spotFor(tree, 90f, 95f), TreeDragAndDrop.DROP_AFTER_CLASS, root, 1);
    }

    /** A row with a sibling below it ends no group, so X changes nothing. */
    @Test
    public void aRowThatEndsNoGroupIgnoresTheX() {
        TreeDragAndDrop<Node> drops = new TreeDragAndDrop<>(editing, new UIElement());
        assertTarget(drops.spotAt(model, rowElement(b), b, 0.9f, 0f), TreeDragAndDrop.DROP_AFTER_CLASS, root, 2);
    }

    @Test
    public void anUnorderedTreeDropsIntoAFolderAndALeafMeansItsFolder() {
        model.ordered = false;
        TreeDragAndDrop<Node> drops = new TreeDragAndDrop<>(editing, new UIElement());
        assertTarget(drops.spotAt(model, rowElement(b), b, 0.1f), TreeDragAndDrop.DROP_INTO_CLASS, b, -1);
        assertTarget(drops.spotAt(model, rowElement(a1), a1, 0.9f), TreeDragAndDrop.DROP_INTO_CLASS, a, -1);
    }

    @Test
    public void aDropIntoItselfOrItsOwnDescendantIsRefused() {
        assertFalse(model.canDrop(List.of(a), a));
        assertFalse(model.canDrop(List.of(root), a));
        assertTrue(model.canDrop(List.of(c), a));
        assertFalse("a leaf takes nothing", model.canDrop(List.of(b), c));
    }

    @Test
    public void aDragRestingOnAClosedBranchOpensItAfterHalfASecond() {
        tree.setExpanded(a, false);
        settle();
        TreeDragAndDrop<Node> drops = new TreeDragAndDrop<>(editing, new UIElement());
        drops.aimAt(drops.spotAt(model, rowElement(a), a, 0.5f));
        drops.tickHover(0.3f);
        assertFalse("opened before the wait was up", tree.isExpanded(a));
        drops.tickHover(0.3f);
        assertTrue("resting on a closed branch never opened it", tree.isExpanded(a));
    }

    @Test
    public void aDragAgainstAnEdgeScrollsByItsDepthIntoTheBandCapped() {
        assertEquals(0f, TreeDragAndDrop.scrollStep(100f, 200f), 0f);
        assertTrue("the top band scrolls up", TreeDragAndDrop.scrollStep(12f, 200f) < 0f);
        assertTrue("deeper is faster",
                TreeDragAndDrop.scrollStep(2f, 200f) < TreeDragAndDrop.scrollStep(20f, 200f));
        assertEquals(-TreeDragAndDrop.SCROLL_MAX_STEP, TreeDragAndDrop.scrollStep(-500f, 200f), 0f);
        assertTrue("the bottom band scrolls down", TreeDragAndDrop.scrollStep(190f, 200f) > 0f);
        assertEquals("a viewport no taller than both bands never scrolls", 0f,
                TreeDragAndDrop.scrollStep(10f, 40f), 0f);
    }

    /** A mark whose gradient failed to parse draws nothing and says nothing, so the sheet is asked. */
    @Test
    public void theDropMarksAndTheCutMarkResolveInTheUserAgentSheet() {
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        rowElement(b).addClass(TreeDragAndDrop.DROP_BEFORE_CLASS);
        rowElement(c).addClass(TreeDragAndDrop.DROP_AFTER_CLASS);
        rowElement(a1).addClass(TreeEditing.CUT_CLASS);
        settle();
        assertTrue(rowElement(b).getStyle().computed().get(StylePropertyRegistry.OUTLINE) instanceof CgUiGradient);
        assertTrue(rowElement(c).getStyle().computed().get(StylePropertyRegistry.OUTLINE) instanceof CgUiGradient);
        assertEquals(0.5f, rowElement(a1).getStyle().computed().get(StylePropertyRegistry.OPACITY), 0.001f);
    }

    private static void assertTarget(TreeDragAndDrop.Spot<Node> spot, String mark, Node parent, int index) {
        assertEquals(mark, spot.mark());
        assertSame(parent, spot.target().parent());
        assertEquals(index, spot.target().index());
    }

    // ── The menu ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aRightClickInsideTheSelectionKeepsItAndOutsideReplacesIt() {
        editing.attachContextMenu(CommandRegistry.global(), () -> ContextMenu.of(MenuId.of("test/tree-editing")));
        selectOnly(a, b);
        int[] onB = centreOf(rowElement(b));
        press(onB[0], onB[1], CgMouseCodes.RIGHT_BUTTON);
        release(onB[0], onB[1], CgMouseCodes.RIGHT_BUTTON);
        settle();
        assertEquals("a right-click on a selected row dropped the rest of the selection",
                Set.of(rowOf(a), rowOf(b)), Set.copyOf(tree.getSelectedIndices()));

        document.dismiss().lightDismiss(null);
        settle();
        int[] onC = centreOf(rowElement(c));
        press(onC[0], onC[1], CgMouseCodes.RIGHT_BUTTON);
        release(onC[0], onC[1], CgMouseCodes.RIGHT_BUTTON);
        settle();
        assertEquals(Set.of(rowOf(c)), Set.copyOf(tree.getSelectedIndices()));
    }
}
