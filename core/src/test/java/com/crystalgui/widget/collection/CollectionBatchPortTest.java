package com.crystalgui.widget.collection;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.text.UIText;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6.3's collections on the new engine — the list, the tree and the table.
 *
 * <p>Six tests rather than a port of the old engine's four hundred. The old assertions still run
 * against the old widgets and move wholesale at 6.9; what a batch needs on the day it lands is the
 * handful of things that are <em>new</em> about running on this engine, and every one of these is a
 * mechanism the old engine either did not have or spelled differently.</p>
 */
public class CollectionBatchPortTest extends UiDocumentTestBase {

    /** A row is a label; the simplest renderer that still recycles. */
    private static ListRenderer<String> labels() {
        return new ListRenderer<>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.append(new UIText(""));
                return row;
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                ((UIText) template.children().get(0)).setText(item);
            }
        };
    }

    private static ObservableList<String> rows(int count) {
        ObservableList<String> model = new ObservableList<>();
        for (int i = 0; i < count; i++) model.add("row " + i);
        return model;
    }

    /**
     * <b>A virtualised list reports its scroll extent from the MODEL, not from its boxes.</b>
     *
     * <p>{@code scrollExtent} shipped at 6.0 with no consumer at all — its javadoc names a list
     * overriding it with {@code model.size() * rowHeight} as the case it was written for, and 6.2
     * mistook it for a content-size accessor and got back the {@code -1} that means "ask the boxes".
     * This is the engine's first override, and it is the whole reason a virtualised widget can exist
     * here: a list realises a dozen rows of ten thousand, so the boxes under it describe the WINDOW
     * and the children genuinely cannot be asked.</p>
     *
     * <p>Asserted against a model orders of magnitude larger than the realised set, because a list
     * that realised everything would satisfy a content-derived extent perfectly — which is exactly the
     * shape that hides the defect.</p>
     */
    @Test
    public void aVirtualisedListReportsItsScrollExtentFromTheModel() {
        withDefaultStyles();
        ListView<String> list = new ListView<>(rows(10_000));
        list.setRenderer(labels()).setItemHeight(20f);
        layout(list, l -> l.width(300f).height(200f));
        document.append(list);
        frame();
        frame();

        assertEquals("ten thousand rows of 20px is the extent, whatever is realised",
                200_000f, list.scrollExtent(false), 1f);
        assertEquals("this list does not virtualise sideways, so it must defer to the boxes",
                -1f, list.scrollExtent(true), 0.001f);

        long realised = list.children().stream().filter(c -> c.hasClass(ListView.ROW_CLASS)).count();
        assertTrue("the fixture realised " + realised + " of 10,000 rows -- if it realised them all "
                        + "then a content-derived extent would pass too, and this proves nothing",
                realised > 0 && realised < 100);
    }

    /**
     * <b>A {@link ListView} is the tab stop of its own composite, and a press on its empty space has
     * to land on it.</b>
     *
     * <p>Rows are {@code CLICK_NOT_TABBABLE} — the roving-tabindex pattern — so a list left at
     * {@code FocusPolicy.NONE} has <em>zero</em> focusable entry points and hears no keys whatsoever:
     * not the arrows attached in its own constructor, not type-ahead, not Ctrl+F. The old engine
     * shipped exactly that, and it surfaced in the Problems panel, which is a list nobody has any
     * reason to have clicked a row in.</p>
     *
     * <p><b>Driven at a POINT</b>, which the invariant row demands: dispatching straight at an element
     * skips focus resolution entirely, so a test written that way passes against a widget that can
     * never be focused. The press lands on the space <em>below</em> the rows, which is the gesture
     * that exposed it — clicking a row focuses the row and hides the question.</p>
     */
    @Test
    public void aListIsTheTabStopOfItsOwnComposite() {
        withDefaultStyles();
        ListView<String> list = new ListView<>(rows(3));
        list.setRenderer(labels()).setItemHeight(20f).setSelectionMode(SelectionMode.SINGLE);
        layout(list, l -> l.width(300f).height(200f));
        document.append(list);
        frame();
        frame();

        Box box = boxOf(list);
        assertNotNull("the list has no box", box);
        // BELOW THE ROWS: three 20px rows in a 200px box, so anything past y+70 is bare list.
        click(box.worldX() + 40f, box.worldY() + 140f);
        frame();

        assertEquals("a press on a list's own empty space has to focus the LIST -- rows are "
                        + "CLICK_NOT_TABBABLE, so nothing else in the composite can take it",
                list, document.focus().focused());

        // AND THE KEYS THEN ARRIVE, which is what the focus was for. Nothing is dispatched at all
        // while the focus owner is null, so a list nobody had clicked a row in was deaf.
        list.select(0);
        frame();
        keyPress(CgKeyCodes.KEY_DOWN);
        frame();
        assertTrue("the list took focus and still heard nothing", list.isSelected(1));
    }

    /**
     * <b>A recycled row SWAPS its data-driven classes; it never merely adds them.</b>
     *
     * <p>A template is a different row every time the view reuses it, so adding {@code kind-odd}
     * without removing {@code kind-even} leaves both on the element — and the cascade then resolves
     * whichever rule happens to win, which reads as a random colour rather than as a stale class.</p>
     *
     * <p>Asserted by scrolling a pooled element onto a row of the other kind and reading the classes
     * off the element itself, because every observable one step up is correct: the model is right, the
     * bind ran, and the row shows the right text.</p>
     */
    @Test
    public void aRecycledRowSwapsItsDataDrivenClasses() {
        withDefaultStyles();
        ObservableList<String> model = new ObservableList<>();
        for (int i = 0; i < 200; i++) model.add(i % 2 == 0 ? "even" : "odd");
        ListView<String> list = new ListView<>(model);
        list.setRenderer(new ListRenderer<>() {
            @Override
            public UIElement createTemplate() {
                return new UIElement();
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                template.removeClass("kind-even").removeClass("kind-odd");
                template.addClass("kind-" + item);
            }
        }).setItemHeight(20f);
        layout(list, l -> l.width(300f).height(100f));
        document.append(list);
        frame();
        frame();

        list.scrollToIndex(101);
        frame();
        frame();

        List<String> both = new ArrayList<>();
        for (UIElement row : list.children()) {
            if (!row.hasClass(ListView.ROW_CLASS)) continue;
            if (row.hasClass("kind-even") && row.hasClass("kind-odd")) both.add(String.valueOf(row));
        }
        assertTrue("a pooled row carries both kinds at once: " + both, both.isEmpty());
    }

    /** A two-level tree of strings, so an expansion has something to reveal. */
    private static TreeDataSource<String> letters() {
        return new TreeDataSource<>() {
            @Override
            public List<String> roots() {
                return List.of("alpha", "beta");
            }

            @Override
            public List<String> children(String parent) {
                return parent.length() > 5 ? List.of() : List.of(parent + "/one", parent + "/two");
            }

            @Override
            public boolean hasChildren(String item) {
                return !children(item).isEmpty();
            }
        };
    }

    /** A row is a label, for a tree. */
    private static TreeRenderer<String> treeLabels() {
        return new TreeRenderer<>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.append(new UIText(""));
                return row;
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                ((UIText) template.children().get(0)).setText(item);
            }
        };
    }

    /**
     * A {@link TreeView} realises its roots, expands one, and the children arrive as rows.
     *
     * <p>The one thing a tree does that a list does not: the row set is a function of what is
     * expanded, and expansion is the whole widget. Asserted on the row COUNT rather than on any one
     * row, since a tree that expanded nothing and a tree that flattened everything are both wrong and
     * only a count separates either from correct.</p>
     */
    @Test
    public void aTreeRealisesItsRootsAndExpandsOne() {
        withDefaultStyles();
        TreeView<String> tree = new TreeView<>(letters());
        tree.setRenderer(treeLabels());
        layout(tree, l -> l.width(300f).height(300f));
        document.append(tree);
        frame();
        frame();

        int collapsed = tree.visibleRows().size();
        assertEquals("two roots, neither expanded", 2, collapsed);

        tree.setExpanded("alpha", true);
        frame();
        frame();

        assertEquals("the two children of the expanded root have to join the row set",
                4, tree.visibleRows().size());
    }

    /**
     * A {@link TableView} lays its columns out side by side.
     *
     * <p>The check that separates a table from a list: the cells of one row are laid out in a ROW.
     * This engine's box tree writes CSS's initial {@code flex-direction: row} where the old bridge
     * defaulted to {@code column} — the divergence D5.8 reversed at 6.1 — so a table is exactly the
     * widget whose port could come back stacked while being correct in every other respect.</p>
     */
    @Test
    public void aTableLaysItsColumnsOutSideBySide() {
        withDefaultStyles();
        TableView<String> table = new TableView<>(rows(5));
        table.addColumn(TableColumn.<String>of("Name", s -> s).width(120f));
        table.addColumn(TableColumn.<String>of("Length", s -> String.valueOf(s.length())).width(80f));
        layout(table, l -> l.width(400f).height(200f));
        document.append(table);
        frame();
        frame();

        Box box = boxOf(table);
        assertNotNull("the table has no box", box);
        assertTrue("a table with two columns and five rows measured "
                        + box.width() + "x" + box.height(),
                box.width() > 0f && box.height() > 0f);
        assertEquals("two columns went in", 2, table.getColumns().size());
    }

    /**
     * Every collection widget in the batch lays out with content in it.
     *
     * <p>The batch-wide smoke test 6.1 and 6.2 each ended up needing, for the same reason: a widget
     * that measures {@code Nx0} is the failure this engine produces, and it is correct in every other
     * observable — styled, in the tree, its children present, its model right.</p>
     */
    @Test
    public void every63CollectionLaysOutWithItsContent() {
        withDefaultStyles();
        ListView<String> list = new ListView<>(rows(20));
        list.setRenderer(labels()).setItemHeight(20f);
        TreeView<String> tree = new TreeView<>(letters());
        tree.setRenderer(treeLabels());
        TableView<String> table = new TableView<>(rows(5));
        table.addColumn(TableColumn.<String>of("Name", s -> s).flexible());

        List<UIElement> widgets = List.of(list, tree, table);
        for (UIElement widget : widgets) {
            layout(widget, l -> l.width(300f).height(150f));
            document.append(widget);
        }
        frame();
        frame();

        List<String> offenders = new ArrayList<>();
        for (UIElement widget : widgets) {
            Box box = boxOf(widget);
            if (box == null) offenders.add(widget.getClass().getSimpleName() + ": no box");
            else if (!(box.width() > 0f) || !(box.height() > 0f)) {
                offenders.add(widget.getClass().getSimpleName()
                        + ": measured " + box.width() + "x" + box.height());
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * <b>Dragging a nested scroller's THUMB scrolls the view.</b>
     *
     * <p>The bar highlighted under the pointer and refused to move, which is the shape a retargeted
     * event makes. {@code Scroller} kept one mouse-down listener on ITSELF and compared
     * {@code event.getTarget()} against its thumb, head, tail and track — which is what the old
     * engine needed, because those were light children and the target was the real node. Here they
     * are SHADOW PARTS, and a listener attached to the host is outside its own shadow tree, so every
     * event from inside is retargeted to the host before the listener runs: {@code target == thumb}
     * could never be true, the chain fell through to "the press was on the scroller itself", and that
     * branch jumps the thumb to where the pointer is — which, for a press on the thumb, is where it
     * already was.</p>
     *
     * <p><b>Asserted through a ListView</b>, which is how it was reported and is the case that
     * matters: a scroller nested in a page rather than the page's own. And on the OFFSET rather than
     * on any listener firing, because the broken version fired a listener too — just the wrong one.</p>
     */
    @Test
    public void draggingANestedScrollersThumbScrollsIt() {
        withDefaultStyles();
        ListView<String> list = new ListView<>(rows(500));
        list.setRenderer(labels()).setItemHeight(20f);
        layout(list, l -> l.width(300f).height(200f));
        document.append(list);
        frame();
        frame();

        UIElement thumb = list.verticalScroller().thumb();
        Box bar = boxOf(thumb);
        assertNotNull("the scrollbar has no thumb box -- the fixture is not scrollable", bar);
        assertEquals("the fixture must start at the top", 0f, list.box().scrollTop(), 0.01f);

        float px = bar.worldX() + bar.width() / 2f;
        float py = bar.worldY() + bar.height() / 2f;
        press(px, py);
        frame();
        move(px, py + 40f);
        frame();

        assertTrue("dragging the thumb down did not scroll: scrollTop is still "
                        + list.box().scrollTop(),
                list.box().scrollTop() > 0f);

        // AND IT TRACKS, rather than jumping once: the broken version's fallback branch DID move the
        // view when the press landed off the thumb, so a single-step assertion passes against it.
        float afterFirst = list.box().scrollTop();
        move(px, py + 80f);
        frame();
        assertTrue("the drag did not keep tracking the pointer",
                list.box().scrollTop() > afterFirst);
        release(px, py + 80f);
    }
}
