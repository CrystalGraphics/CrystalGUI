package com.crystalgui.widget.collection.tree;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.ui.input.FocusPolicy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P6.1.4 — the tree.
 *
 * <h3>A tree is a flattened list</h3>
 * <p>So most of what a tree needs was already true: virtualisation, recycling, selection, focus-by-index
 * and the scroll machinery all come from {@code ListView} and are tested there. What is tested <em>here</em>
 * is the part that is genuinely tree-shaped — flattening against an expansion set, and the ARIA keyboard
 * contract, whose Left/Right asymmetry is the thing implementations get wrong.</p>
 */
public class TreeViewTest extends UiDocumentTestBase {

    /** a → a1 (→ a1a, a1b), a2 · b → b1 · c (leaf) */
    private static final Map<String, List<String>> CHILDREN = Map.of(
            "a", List.of("a1", "a2"),
            "a1", List.of("a1a", "a1b"),
            "b", List.of("b1"));

    private TreeView<String> tree;
    private int childrenCalls;

    private TreeView<String> build() {
        childrenCalls = 0;
        TreeDataSource<String> source = new TreeDataSource<>() {
            @Override
            public List<String> roots() {
                return List.of("a", "b", "c");
            }

            @Override
            public List<String> children(String parent) {
                childrenCalls++;
                return CHILDREN.getOrDefault(parent, List.of());
            }

            @Override
            public boolean hasChildren(String item) {
                return CHILDREN.containsKey(item);
            }
        };

        tree = new TreeView<>(source);
        tree.setItemHeight(10f);
        tree.layout(l -> l.width(100).height(100));
        tree.setRenderer(new TreeRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.setFocusPolicy(FocusPolicy.FOCUSABLE);
                return row;
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                template.setId(item);
            }
        });

        UIElement root = new UIElement().layout(l -> l.width(100).height(100));
        root.append(tree);
        document.append(root);
        settle();
        return tree;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private List<String> visible() {
        List<String> out = new ArrayList<>();
        for (TreeRow<String> row : tree.visibleRows()) out.add(row.item());
        return out;
    }

    private void key(int keyCode) {
        UIElement focused = document.focus().focused();
        UIElement target = focused != null ? focused : tree;
        document.input().send(target,
                new com.crystalgui.ui.event.KeyboardEvent.Down(target, keyCode, (char) 0, false, 0, 0L));
        settle();
    }

    // ── Flattening ──────────────────────────────────────────────────────────

    @Test
    public void onlyRootsAreVisibleUntilSomethingIsExpanded() {
        build();
        assertEquals(List.of("a", "b", "c"), visible());
    }

    @Test
    public void expandingSplicesChildrenInPlace() {
        build();
        tree.setExpanded("a", true);
        assertEquals(List.of("a", "a1", "a2", "b", "c"), visible());

        tree.setExpanded("a1", true);
        assertEquals("nested expansion nests", List.of("a", "a1", "a1a", "a1b", "a2", "b", "c"), visible());

        tree.setExpanded("a", false);
        assertEquals("collapsing a hides its whole subtree", List.of("a", "b", "c"), visible());
    }

    /**
     * <b>Expansion state survives a collapse.</b>
     *
     * <p>Held against the caller's items rather than the flattened rows, which are rebuilt wholesale — so
     * re-opening a node restores what was open inside it, exactly as every file explorer does.</p>
     */
    @Test
    public void reExpandingRestoresTheInnerState() {
        build();
        tree.setExpanded("a", true);
        tree.setExpanded("a1", true);
        tree.setExpanded("a", false);
        tree.setExpanded("a", true);

        assertEquals(List.of("a", "a1", "a1a", "a1b", "a2", "b", "c"), visible());
    }

    @Test
    public void depthAndParentAreCarriedOnEachRow() {
        build();
        tree.setExpanded("a", true);
        tree.setExpanded("a1", true);
        List<TreeRow<String>> rows = tree.visibleRows();

        assertEquals(0, rows.get(0).depth());   // a
        assertEquals(1, rows.get(1).depth());   // a1
        assertEquals(2, rows.get(2).depth());   // a1a
        assertEquals("a root has no parent", -1, rows.get(0).parentIndex());
        assertEquals("a1a's parent is a1, at index 1", 1, rows.get(2).parentIndex());
    }

    /** A leaf cannot be opened, and asking is not an error — a caller toggling blindly is normal. */
    @Test
    public void aLeafCannotBeExpanded() {
        build();
        tree.setExpanded("c", true);
        assertFalse(tree.isExpanded("c"));
        assertEquals(List.of("a", "b", "c"), visible());
    }

    /**
     * <b>Children are pulled on expand, not up front.</b>
     *
     * <p>The point of a pull-based source: a file explorer must not read ten thousand directories to show
     * three. {@code hasChildren} decides the twisty without reading anything.</p>
     */
    @Test
    public void childrenAreOnlyAskedForWhenExpanded() {
        build();
        assertEquals("nothing expanded, so nothing read", 0, childrenCalls);

        tree.setExpanded("a", true);
        assertTrue("only a was read", childrenCalls >= 1);
        int afterA = childrenCalls;

        tree.setExpanded("b", false);   // already collapsed — a no-op
        assertEquals(afterA, childrenCalls);
    }

    /**
     * <b>Expanding must change what is on screen, not only what is in the model.</b>
     *
     * <p>Reported from the harness as "pressing Right twice takes me to the next folder" — which was the
     * decisive clue. The second press moving at all proved the node HAD expanded and the model HAD
     * re-flattened; what had not happened was any re-binding, because {@code ListView} only realised
     * indices it was not already holding. Every visible row kept its old contents over a new model.</p>
     *
     * <p>Every other test here passed throughout, because they all read {@code visibleRows()} — the
     * model. This one reads the rows.</p>
     */
    @Test
    public void expandingReBindsTheRowsOnScreen() {
        build();
        assertEquals("a", renderedAt(0));
        assertEquals("b", renderedAt(1));

        tree.setExpanded("a", true);
        settle();

        assertEquals("a", renderedAt(0));
        assertEquals("a1 is now on screen where b used to be", "a1", renderedAt(1));
        assertEquals("a2", renderedAt(2));
        assertEquals("b", renderedAt(3));
    }

    /** And collapsing puts them back. */
    @Test
    public void collapsingReBindsTheRowsOnScreen() {
        build();
        tree.setExpanded("a", true);
        settle();
        assertEquals("a1", renderedAt(1));

        tree.setExpanded("a", false);
        settle();

        assertEquals("b", renderedAt(1));
    }

    /** What the renderer last wrote into the realised row at {@code index} — the display, not the model. */
    private String renderedAt(int index) {
        UIElement row = tree.realisedRows().get(index);
        assertNotNull("row " + index + " is not realised", row);
        return row.id();
    }

    // ── The APG keyboard contract ───────────────────────────────────────────

    /** <b>Right on a collapsed node opens it and does NOT move focus.</b> The asymmetry implementations
     * get wrong, and the reason the table in the plan is written out row by row. */
    @Test
    public void rightOnACollapsedNodeOpensItWithoutMovingFocus() {
        build();
        tree.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_RIGHT);

        assertTrue(tree.isExpanded("a"));
        assertEquals("focus stays on the node that opened", 0, tree.getFocusedIndex());
    }

    @Test
    public void rightOnAnOpenNodeMovesToTheFirstChild() {
        build();
        tree.setExpanded("a", true);
        tree.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_RIGHT);

        assertEquals("a1 is the row after a", 1, tree.getFocusedIndex());
        assertTrue("and it stays open", tree.isExpanded("a"));
    }

    @Test
    public void rightOnALeafDoesNothing() {
        build();
        tree.setFocusedIndex(2);   // c
        settle();

        key(CgKeyCodes.KEY_RIGHT);

        assertEquals(2, tree.getFocusedIndex());
        assertEquals(List.of("a", "b", "c"), visible());
    }

    @Test
    public void leftOnAnOpenNodeClosesIt() {
        build();
        tree.setExpanded("a", true);
        tree.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_LEFT);

        assertFalse(tree.isExpanded("a"));
        assertEquals("focus stays on the node that closed", 0, tree.getFocusedIndex());
    }

    @Test
    public void leftOnAChildMovesToItsParent() {
        build();
        tree.setExpanded("a", true);
        tree.setFocusedIndex(1);   // a1
        settle();

        key(CgKeyCodes.KEY_LEFT);

        assertEquals("up to a", 0, tree.getFocusedIndex());
        assertTrue("without closing anything on the way", tree.isExpanded("a"));
    }

    @Test
    public void leftOnAClosedRootDoesNothing() {
        build();
        tree.setFocusedIndex(1);   // b, collapsed, no parent
        settle();

        key(CgKeyCodes.KEY_LEFT);

        assertEquals(1, tree.getFocusedIndex());
    }

    /** Up/Down move through VISIBLE nodes without opening anything — satisfied by the flattening rather
     * than by any tree code, which is the point of building it this way. */
    @Test
    public void upAndDownWalkTheVisibleNodesOnly() {
        build();
        tree.setExpanded("a", true);
        tree.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_DOWN);
        key(CgKeyCodes.KEY_DOWN);

        assertEquals("a -> a1 -> a2", 2, tree.getFocusedIndex());
        assertEquals("a2", tree.rowAt(2).item());
        assertFalse("and nothing opened on the way", tree.isExpanded("a2"));
    }

    /** The APG's optional asterisk. */
    @Test
    public void asteriskExpandsEverySiblingAtThatLevel() {
        build();
        tree.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_MULTIPLY);

        assertTrue(tree.isExpanded("a"));
        assertTrue(tree.isExpanded("b"));
        assertFalse("c is a leaf and stays alone", tree.isExpanded("c"));
    }

    /**
     * <b>A twisty listener must ask for its row's index at click time.</b>
     *
     * <p>The element it is attached to represents a different row every time it is recycled, so capturing
     * an index in {@code createTemplate} would open whatever node happened to occupy that slot first.
     * This is the tree-shaped instance of the split {@code ListRenderer} exists to enforce.</p>
     */
    @Test
    public void aRowElementReportsTheIndexItCurrentlyRepresents() {
        build();
        UIElement rowOne = tree.realisedRows().get(1);
        assertEquals(1, tree.indexOfRowElement(rowOne));
        assertEquals("b", tree.rowAt(tree.indexOfRowElement(rowOne)).item());

        tree.setExpanded("a", true);
        settle();

        assertEquals("the same element now represents a1, and says so",
                "a1", tree.rowAt(tree.indexOfRowElement(rowOne)).item());
        assertEquals("and an element that is not a row answers -1", -1, tree.indexOfRowElement(tree));
    }

    // ── What it inherits from ListView ──────────────────────────────────────

    /**
     * <b>Virtualisation is inherited, not re-implemented.</b>
     *
     * <p>Expanding a node with a huge subtree must not realise it. This is the single most valuable
     * property of building the tree on the list, and the only one worth re-asserting here — everything
     * else it inherits is covered by {@code ListViewTest}.</p>
     */
    @Test
    public void expandingAHugeSubtreeDoesNotRealiseIt() {
        TreeDataSource<String> big = new TreeDataSource<>() {
            @Override
            public List<String> roots() {
                return List.of("root");
            }

            @Override
            public List<String> children(String parent) {
                List<String> out = new ArrayList<>(20_000);
                for (int i = 0; i < 20_000; i++) out.add("n" + i);
                return out;
            }

            @Override
            public boolean hasChildren(String item) {
                return "root".equals(item);
            }
        };
        TreeView<String> huge = new TreeView<>(big);
        huge.setItemHeight(10f);
        huge.layout(l -> l.width(100).height(100));
        huge.setRenderer(new TreeRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                return new UIElement();
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
            }
        });
        UIElement root = new UIElement().layout(l -> l.width(100).height(100));
        root.append(huge);
        document.append(root);
        for (int i = 0; i < 4; i++) frame();

        huge.setExpanded("root", true);
        for (int i = 0; i < 4; i++) frame();

        assertEquals("20,001 visible nodes", 20_001, huge.visibleRows().size());
        assertTrue("but only a windowful realised, was " + huge.realisedCount(),
                huge.realisedCount() < 20);
    }

    /** Selection is the list's, over flattened indices — so it works, and collapsing simply removes rows
     * that were selected. Worth pinning because "selection survives a re-flatten" is not obvious. */
    @Test
    public void collapsingDropsSelectionsThatNoLongerExist() {
        build();
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        tree.setExpanded("a", true);
        tree.select(1);          // a1
        tree.toggle(2);          // a2
        assertEquals(java.util.Set.of(1, 2), tree.getSelectedIndices());

        tree.setExpanded("a", false);
        settle();

        assertEquals("the tree is three rows now, so nothing past index 2 can be selected",
                3, tree.visibleRows().size());
        for (int index : tree.getSelectedIndices()) {
            assertTrue("stale index " + index, index < 3);
        }
    }

    /**
     * <b>Expanding a node ABOVE the selection must not select a second row.</b>
     *
     * <p>{@code refresh} remembers the selected <em>items</em> and restores them after the re-flatten,
     * which is right — indices do not survive one. What was missing is the clear in between: {@code
     * ListView}'s clamp only discards indices that are now <em>out of range</em>, and an index that is
     * still in range survives pointing at a different row. Restoring on top of that leaves both.</p>
     *
     * <p>Collapsing hid it, because the list shrinks and the stale index usually falls off the end.
     * Expanding is where it bites: everything below moves down, every old index stays valid, and each
     * re-flatten leaves one more row selected. It surfaced as the file tree gaining a selected row on every
     * flip of the search mode — nothing was additive, the selection simply grew by one each time.</p>
     */
    @Test
    public void expandingAboveTheSelectionDoesNotSelectASecondRow() {
        build();
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        settle();

        // a, b, c — with c selected.
        tree.select(2);
        settle();
        assertEquals(java.util.Set.of(2), tree.getSelectedIndices());

        // a, a1, a2, b, c — c has moved to 4, and index 2 is now a2.
        tree.setExpanded("a", true);
        settle();

        assertEquals("expanding above the selection left the old index selected too",
                1, tree.getSelectedIndices().size());
        assertEquals("and the row that stayed selected is not the one that was",
                "c", tree.rowAt(tree.getSelectedIndices().iterator().next()).item());
    }

}
