package com.crystalgui.ui;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.SelectionMode;
import com.crystalgui.ui.elements.table.SortOrder;
import com.crystalgui.ui.elements.table.TableColumn;
import com.crystalgui.ui.elements.table.TableView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.1.5 — the table.
 *
 * <h3>Display-side assertions first, deliberately</h3>
 * <p>Three separate bugs this session hid behind tests that read the <em>model</em>: {@code ListView}
 * never re-bound its rows on a model change, and both the list's and the tree's suites passed throughout
 * because every one of them asked the data rather than the screen. So the first thing asserted here is
 * what a cell actually says, and {@link #cellsShowTheSortedOrderNotJustTheModel()} is the test that
 * would have caught all three.</p>
 */
public class TableViewTest extends UiTestBase {

    private record File(String name, long size) {
    }

    private UIWindow window;
    private ObservableList<File> source;
    private TableView<File> table;
    private TableColumn<File> nameColumn;
    private TableColumn<File> sizeColumn;

    private TableView<File> build(String... names) {
        source = new ObservableList<>();
        long size = 100;
        for (String name : names) {
            source.add(new File(name, size));
            size -= 10;
        }

        table = new TableView<>(source);
        table.setItemHeight(10f);
        table.layout(l -> l.width(200).height(120));

        nameColumn = TableColumn.<File>of("Name", File::name).width(100).sortable();
        sizeColumn = TableColumn.<File>of("Size", f -> String.valueOf(f.size()))
                .width(60).sortable(Comparator.comparingLong(File::size));
        table.addColumn(nameColumn);
        table.addColumn(sizeColumn);

        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        root.addChild(table);
        window = new UIWindow(Ui.of(root));
        window.init(400, 400);
        settle();
        return table;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    /** What the NAME cell of the realised row at {@code index} actually displays. */
    private String displayedName(int index) {
        UIElement row = table.realisedRows().get(index);
        assertNotNull("row " + index + " is not realised", row);
        UIElement cell = row.getChildren().get(0);
        return ((UIText) cell.getChildren().get(0)).getText();
    }

    private List<String> modelOrder() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < table.getModel().size(); i++) out.add(table.getModel().get(i).name());
        return out;
    }

    // ── The display, first ──────────────────────────────────────────────────

    /**
     * <b>The test that would have caught the three bugs this session.</b>
     *
     * <p>Sorting changes the model; that is easy and every previous suite checked it. What matters is
     * whether the rows on screen change with it.</p>
     */
    @Test
    public void cellsShowTheSortedOrderNotJustTheModel() {
        build("charlie", "alpha", "bravo");
        assertEquals("charlie", displayedName(0));

        table.toggleSort(nameColumn);
        settle();

        assertEquals(List.of("alpha", "bravo", "charlie"), modelOrder());
        assertEquals("and the screen agrees", "alpha", displayedName(0));
        assertEquals("bravo", displayedName(1));
        assertEquals("charlie", displayedName(2));
    }

    @Test
    public void aSourceChangeReBindsTheCells() {
        build("a", "b", "c");
        assertEquals("a", displayedName(0));

        source.set(0, new File("REPLACED", 1));
        settle();

        assertEquals("REPLACED", displayedName(0));
    }

    /**
     * <b>The header must not sit on top of the first row.</b>
     *
     * <p>It lives inside the scrollport — that is what lets it be scroll-exempt and stay pinned — so the
     * rows have to begin below it. Reducing {@code getClientHeight()} alone fixes how many rows fit, not
     * where they sit, and the first version painted the header straight over row 0.</p>
     */
    @Test
    public void rowsStartBelowTheHeader() {
        build("a", "b", "c");
        UIElement firstRow = table.realisedRows().get(0);
        assertNotNull(firstRow);
        assertTrue("row 0 top was " + firstRow.getRuntimeCache().getY()
                        + ", which is inside the header",
                firstRow.getRuntimeCache().getY() >= table.getRuntimeCache().getY() + 12f);
    }

    /**
     * <b>Header cells must line up with the cells below them.</b>
     *
     * <p>A flexible column's width comes from {@code getClientWidth()}, which is zero until the first
     * layout — so a header built when the columns were added gave every flexible column no width, and the
     * header then sat at completely different offsets from its own rows. The rows escaped it only because
     * they bind after layout.</p>
     */
    @Test
    public void headerCellsMatchTheColumnWidthsAfterLayout() {
        build("a", "b");
        // Reconfigured after addColumn, so the table has to be told — TableColumn's fluent setters are
        // meant for before it, and this is the path a restored column layout takes.
        nameColumn.width(0).flexible();
        table.refreshColumns();
        settle();
        settle();

        UIElement header = null;
        for (UIElement child : table.getChildren()) {
            if (child.hasClass(TableView.HEADER_CLASS)) header = child;
        }
        assertNotNull(header);

        List<Float> widths = table.resolvedWidths();
        assertEquals("the flexible header cell has the flexible column's real width",
                widths.get(0), header.getChildren().get(0).getRuntimeCache().getWidth(), 1.5f);
        assertTrue("which is not zero", widths.get(0) > 10f);
    }

    /**
     * <b>Every header must stay clickable after a sort.</b>
     *
     * <p>Reported as "I clicked Kind and now I cannot click Name or Size". The cause was not the sort at
     * all: the header rebuild was guarded by {@code Math.abs(width - headerBuiltForWidth) > 0.5f} with
     * that field initialised to {@code NaN}, and <b>every comparison against NaN is false</b> — so it
     * never fired on the first layout, the flexible column resolved to zero width, and all three header
     * cells stacked at the same x. Whichever drew last swallowed every click.</p>
     */
    @Test
    public void everyHeaderStaysClickableAfterASort() {
        build("charlie", "alpha", "bravo");
        settle();

        table.toggleSort(sizeColumn);
        settle();
        assertSame(sizeColumn, table.getSortedColumn());

        table.toggleSort(nameColumn);
        settle();
        assertSame("clicking a different header must still work", nameColumn, table.getSortedColumn());
        assertEquals(SortOrder.ASCENDING, table.getSortOrder());
    }

    /** The header cells must occupy distinct positions — the symptom that made the above unclickable. */
    @Test
    public void headerCellsDoNotOverlap() {
        build("a", "b");
        settle();
        settle();

        UIElement header = null;
        for (UIElement child : table.getChildren()) {
            if (child.hasClass(TableView.HEADER_CLASS)) header = child;
        }
        assertNotNull(header);

        float previousRight = -1f;
        int cells = 0;
        for (UIElement child : header.getChildren()) {
            if (!child.hasClass(TableView.HEADER_CELL_CLASS)) continue;
            float left = child.getRuntimeCache().getX();
            assertTrue("header cell " + cells + " starts at " + left
                    + ", inside the one before it", left >= previousRight - 0.5f);
            previousRight = left + child.getRuntimeCache().getWidth();
            cells++;
        }
        assertEquals("both columns have a header cell", 2, cells);
    }

    /**
     * <b>A drag must not push later columns off the edge.</b>
     *
     * <p>Reported as "I can move Kind completely out of the element". There is no divider left out there
     * to drag it back with — the same trap {@code minWidth} closes at the other end.</p>
     */
    @Test
    public void aColumnCannotBeDraggedWideEnoughToEvictItsNeighbours() {
        build("a", "b");
        settle();

        table.resizeColumnTo(nameColumn, 10_000f);

        float total = 0f;
        for (float width : table.resolvedWidths()) total += width;
        assertTrue("columns total " + total + " against a client width of " + table.getClientWidth(),
                total <= table.getClientWidth() + 0.5f);
        var last = table.getColumns().get(table.getColumns().size() - 1);
        assertTrue("and the last column keeps at least its minimum",
                last.getWidth() >= last.getMinWidth() - 0.5f);
    }

    /**
     * <b>A cell must not spill into its neighbour.</b>
     *
     * <p>Narrow the Name column and its text drew straight over the Size cell — {@code asset_0} and
     * {@code 0} rendering as {@code asse0_0}, which reads as corruption rather than as an overflow. Cells
     * clip, and their text ellipsises so the truncation is legible <em>as</em> truncation.</p>
     */
    @Test
    public void cellsClipRatherThanSpillingIntoTheNextColumn() {
        build("a-very-long-asset-name-indeed", "b");
        settle();

        UIElement row = table.realisedRows().get(0);
        UIElement nameCell = row.getChildren().get(0);
        UIElement sizeCell = row.getChildren().get(1);

        assertEquals("a cell clips its content",
                com.crystalgui.style.property.visual.Overflow.HIDDEN,
                nameCell.getStyle().getGeneralGroup().overflow());
        assertTrue("and the two cells do not overlap",
                sizeCell.getRuntimeCache().getX()
                        >= nameCell.getRuntimeCache().getX() + nameCell.getRuntimeCache().getWidth() - 0.5f);
    }

    /**
     * <b>Interacting with the header must not destroy the element being interacted with.</b>
     *
     * <p>The freeze reported from the harness: click a header to sort and no header could be clicked or
     * dragged afterwards. Sorting rebuilt the header, which detached the very cell whose mouse-down had
     * called it; a divider drag did the same on every frame. A detached element with the pointer still
     * captured on it swallows <em>every</em> subsequent pointer event, because that is exactly what
     * pointer capture is for — so clicks and drags both died together.</p>
     *
     * <p>Sorting and resizing now update the existing cells in place. This asserts the property that
     * matters: the same element instances survive.</p>
     */
    @Test
    public void sortingAndResizingKeepTheSameHeaderElements() {
        build("charlie", "alpha", "bravo");
        settle();

        List<UIElement> before = new ArrayList<>(headerOf().getChildren());
        assertFalse(before.isEmpty());

        table.toggleSort(sizeColumn);
        settle();
        assertEquals("sorting must not recreate the header", before, headerOf().getChildren());

        table.resizeColumnTo(nameColumn, 80f);
        settle();
        assertEquals("nor must resizing", before, headerOf().getChildren());

        table.toggleSort(nameColumn);
        settle();
        assertSame("and a different header still sorts", nameColumn, table.getSortedColumn());
    }

    /** The markers still move, even though the elements do not. */
    @Test
    public void sortMarkersMoveBetweenTheExistingCells() {
        build("a", "b");
        settle();

        table.toggleSort(nameColumn);
        assertTrue(headerCell(0).hasClass(TableView.SORTED_ASC_CLASS));

        table.toggleSort(sizeColumn);
        assertFalse("the old column's marker is cleared",
                headerCell(0).hasClass(TableView.SORTED_ASC_CLASS));
        assertTrue("and the new one carries it", headerCell(1).hasClass(TableView.SORTED_ASC_CLASS));
    }

    /**
     * <b>The reproduction, driven through the input handler rather than the API.</b>
     *
     * <p>Every other test in this class calls {@code toggleSort} directly, which is the side the code was
     * written from — and the freeze reported from the harness ("Size is green but I cannot click any other
     * header") was invisible to all of them. Clicking means hit-testing, and hit-testing is what the
     * header rebuild broke: it detached the cell under the cursor mid-dispatch.</p>
     */
    @Test
    public void clickingOneHeaderThenAnotherSortsBoth() {
        build("charlie", "alpha", "bravo");
        settle();
        // The very first mouse event in a fresh window has no hover baseline to diff against, so it
        // dispatches nothing. A real pointer has always moved first; this stands in for that.
        moveTo(1, 1);

        clickHeader(1);
        settle();
        assertSame("the first click sorts", sizeColumn, table.getSortedColumn());

        clickHeader(0);
        settle();
        assertSame("and the header is still live afterwards", nameColumn, table.getSortedColumn());

        clickHeader(1);
        settle();
        assertSame("and keeps being live", sizeColumn, table.getSortedColumn());
    }

    private void moveTo(int x, int y) {
        var input = window.getInputHandler();
        input.consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                x, y, 0, 0, -1, false, 0f, 0L));
        input.beginFrame();
        input.endFrame();
    }

    /** A real press+release at the centre of a header cell, through hit-testing. */
    private void clickHeader(int columnIndex) {
        UIElement cell = headerCell(columnIndex);
        float scale = window.getUiScale();
        int x = Math.round((cell.getRuntimeCache().getX() + cell.getRuntimeCache().getWidth() / 2f) * scale);
        int y = Math.round((cell.getRuntimeCache().getY() + cell.getRuntimeCache().getHeight() / 2f) * scale);

        var input = window.getInputHandler();
        input.consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                x, y, 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                x, y, 0, 0, 0, false, 0f, 2L));
        input.beginFrame();
        input.endFrame();
    }

    /**
     * <b>A header cell must sit exactly over the column beneath it.</b>
     *
     * <p>The dividers used to be in the header's flex flow, so the header ran
     * {@code (columns - 1) × dividerWidth} wider than the rows and every cell after the first drifted
     * right of its own column — three pixels, which reads as padding rather than as a bug. Dividers are
     * absolutely positioned now; this compares the two directly, which is the only way the drift is
     * visible at all.</p>
     */
    @Test
    public void headerCellsLineUpWithTheCellsBeneathThem() {
        build("alpha", "bravo");
        settle();
        settle();

        UIElement row = table.realisedRows().get(0);
        assertNotNull(row);
        for (int i = 0; i < table.getColumns().size(); i++) {
            float headerLeft = headerCell(i).getRuntimeCache().getX();
            float cellLeft = row.getChildren().get(i).getRuntimeCache().getX();
            assertEquals("column " + i + " header is not over its cells",
                    cellLeft, headerLeft, 0.5f);
        }
    }

    /**
     * <b>A flexible column may not be squeezed below its own minimum.</b>
     *
     * <p>{@code width(0).flexible()} means the column's entire width is share-of-leftover, so growing a
     * fixed neighbour drove it to literally zero — the gallery's Name column rendered as a sliver of
     * clipped text. The floor was missing only on the flexible branch of {@code resolvedWidth}, which is
     * also the branch {@code maxWidthFor} reserves {@code minWidth} against, so the drag ceiling was
     * computed against a minimum layout did not honour.</p>
     */
    @Test
    public void aFlexibleColumnStopsAtItsMinimumRatherThanCollapsing() {
        build("alpha", "bravo");
        settle();

        TableColumn<File> flexible = table.getColumns().get(0);
        flexible.width(0).flexible();
        // Squeeze the table until the FIXED columns alone overrun it, so there is no leftover left to
        // share. Driving it through resizeColumnTo instead proves nothing: maxWidthFor already reserves
        // this very minimum, so that route never reaches the branch with the missing floor.
        table.layout(l -> l.width(40));
        settle();
        settle();

        assertTrue("no leftover to share", table.getClientWidth() < 100f);

        assertTrue("flexible column collapsed to " + table.resolvedWidths().get(0),
                table.resolvedWidths().get(0) >= flexible.getMinWidth() - 0.5f);
    }

    private UIElement headerOf() {
        for (UIElement child : table.getChildren()) {
            if (child.hasClass(TableView.HEADER_CLASS)) return child;
        }
        throw new AssertionError("no header");
    }

    private UIElement headerCell(int columnIndex) {
        int seen = 0;
        for (UIElement child : headerOf().getChildren()) {
            if (!child.hasClass(TableView.HEADER_CELL_CLASS)) continue;
            if (seen++ == columnIndex) return child;
        }
        throw new AssertionError("no header cell " + columnIndex);
    }

    // ── Sorting ─────────────────────────────────────────────────────────────

    /**
     * <b>Three states, not two.</b>
     *
     * <p>Explorer and Finder cycle ascending/descending forever and never let you get back. The third
     * click costs nothing here because the unsorted view <em>is</em> the source order, which the table
     * keeps anyway — it never mutates the caller's list.</p>
     */
    @Test
    public void aThirdClickRestoresTheOriginalOrder() {
        build("charlie", "alpha", "bravo");

        table.toggleSort(nameColumn);
        assertEquals(SortOrder.ASCENDING, table.getSortOrder());
        assertEquals(List.of("alpha", "bravo", "charlie"), modelOrder());

        table.toggleSort(nameColumn);
        assertEquals(SortOrder.DESCENDING, table.getSortOrder());
        assertEquals(List.of("charlie", "bravo", "alpha"), modelOrder());

        table.toggleSort(nameColumn);
        assertEquals(SortOrder.NONE, table.getSortOrder());
        assertNull(table.getSortedColumn());
        assertEquals("back to the order the caller gave us",
                List.of("charlie", "alpha", "bravo"), modelOrder());
    }

    /** <b>Sorting must never reorder the caller's list.</b> A table has no business rearranging somebody
     * else's data because a header was clicked — and it is what makes the third state possible at all. */
    @Test
    public void sortingNeverTouchesTheSource() {
        build("charlie", "alpha", "bravo");
        table.toggleSort(nameColumn);
        settle();

        List<String> sourceOrder = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) sourceOrder.add(source.get(i).name());
        assertEquals(List.of("charlie", "alpha", "bravo"), sourceOrder);
    }

    /** Sorting a numeric column by its rendered text puts 100 before 20. The comparator overload exists
     * for exactly this, and it is worth a test because the wrong one still *looks* sorted. */
    @Test
    public void aComparatorColumnSortsByValueNotByText() {
        build("a", "b", "c");   // sizes 100, 90, 80
        table.toggleSort(sizeColumn);

        assertEquals("ascending by number: 80, 90, 100", List.of("c", "b", "a"), modelOrder());
    }

    @Test
    public void switchingColumnsStartsAscendingAgain() {
        build("a", "b", "c");
        table.toggleSort(nameColumn);
        table.toggleSort(nameColumn);
        assertEquals(SortOrder.DESCENDING, table.getSortOrder());

        table.toggleSort(sizeColumn);
        assertEquals("a new column starts over rather than inheriting the old direction",
                SortOrder.ASCENDING, table.getSortOrder());
        assertSame(sizeColumn, table.getSortedColumn());
    }

    /**
     * <b>{@code asset_2} before {@code asset_10}, not after.</b>
     *
     * <p>A plain string sort is <em>correct</em> — {@code asset_1077} really does precede {@code asset_2}
     * lexicographically — and wrong to every human looking at a file list. Explorer, Finder and every
     * file manager compare digit runs as numbers, so this is the option that should be easy to reach.</p>
     */
    @Test
    public void naturalOrderSortsDigitRunsAsNumbers() {
        build("asset_1077", "asset_2", "asset_20", "asset_3");
        TableColumn<File> natural = TableColumn.<File>of("Natural", File::name).naturalOrder();
        table.addColumn(natural);

        table.toggleSort(natural);

        assertEquals(List.of("asset_2", "asset_3", "asset_20", "asset_1077"), modelOrder());
    }

    /** And the plain text sort still does the lexicographic thing, so the difference is a choice rather
     * than an accident. */
    @Test
    public void plainTextSortIsStillLexicographic() {
        build("asset_1077", "asset_2", "asset_20", "asset_3");
        table.toggleSort(nameColumn);

        assertEquals(List.of("asset_1077", "asset_2", "asset_20", "asset_3"), modelOrder());
    }

    @Test
    public void anUnsortableColumnIgnoresClicks() {
        build("a", "b");
        TableColumn<File> plain = TableColumn.of("Plain", File::name);
        table.addColumn(plain);

        table.toggleSort(plain);
        assertEquals(SortOrder.NONE, table.getSortOrder());
        assertNull(table.getSortedColumn());
    }

    // ── Selection survives a re-sort, which is why it keys on the item ──────

    /**
     * <b>The divergence from {@code ListView}, and the reason for it.</b>
     *
     * <p>The list selects by index, which is right for it and for the tree — a {@code TreeRow} is a
     * record rebuilt on every flatten and has no stable identity. A table's rows are the caller's own
     * objects and do. The difference is not academic: sorting moves every index, so index-based selection
     * would leave a user who selected three files owning three <em>different</em> ones after one header
     * click.</p>
     */
    @Test
    public void selectionFollowsTheItemAcrossASort() {
        build("charlie", "alpha", "bravo");
        table.setSelectionMode(SelectionMode.MULTIPLE);
        table.select(0);      // charlie
        assertEquals(java.util.Set.of(new File("charlie", 100)), table.getSelectedItems());

        table.toggleSort(nameColumn);
        settle();

        assertEquals("still charlie, not whatever is at index 0 now",
                java.util.Set.of(new File("charlie", 100)), table.getSelectedItems());
        assertEquals("and its index moved to the end", java.util.Set.of(2), table.getSelectedIndices());
    }

    @Test
    public void removingASelectedItemDropsItFromTheSelection() {
        build("a", "b", "c");
        table.select(1);
        assertEquals(1, table.getSelectedItems().size());

        source.removeAt(1);
        settle();

        assertTrue("b is gone, so it cannot stay selected", table.getSelectedItems().isEmpty());
    }

    // ── Columns ─────────────────────────────────────────────────────────────

    @Test
    public void fixedColumnsKeepTheirWidth() {
        build("a");
        List<Float> widths = table.resolvedWidths();
        assertEquals(100f, widths.get(0), 0.5f);
        assertEquals(60f, widths.get(1), 0.5f);
    }

    /** A flexible column absorbs whatever the fixed ones leave over — a "name" column wants the rest, a
     * "size" column wants exactly 60px forever. */
    @Test
    public void aFlexibleColumnAbsorbsTheLeftover() {
        build("a");
        nameColumn.width(0).flexible();
        table.refreshColumns();
        settle();

        List<Float> widths = table.resolvedWidths();
        assertEquals("60 of the viewport is fixed, the rest goes to name",
                table.getClientWidth() - 60f, widths.get(0), 1f);
        assertEquals(60f, widths.get(1), 0.5f);
    }

    /**
     * <b>Dragging a flexible column pins it.</b>
     *
     * <p>Weight decides how leftover space is handed out; a user who has just dragged a column to a width
     * has said they want <em>that</em> width. A column that sprang back on release would be maddening,
     * and every file manager behaves this way.</p>
     */
    @Test
    public void draggingAFlexibleColumnMakesItFixed() {
        build("a");
        nameColumn.width(0).flexible();
        assertTrue(nameColumn.getWeight() > 0f);

        // 100, not 140: the fixture's viewport is 200 and the fixed Size column plus its divider reserve
        // 63 of it, so 140 would be clamped — see aColumnCannotBeDraggedWideEnoughToEvictItsNeighbours.
        table.resizeColumnTo(nameColumn, 100f);

        assertEquals("the weight is gone, so it no longer absorbs leftover space",
                0f, nameColumn.getWeight(), 0.001f);
        assertEquals(100f, nameColumn.getWidth(), 0.5f);
    }

    /** A column dragged to nothing would leave no divider to drag back — so there is a floor. */
    @Test
    public void aColumnCannotBeDraggedBelowItsMinimum() {
        build("a");
        nameColumn.minWidth(30f);

        table.resizeColumnTo(nameColumn, 5f);

        assertEquals(30f, nameColumn.getWidth(), 0.5f);
    }

    // ── The header ──────────────────────────────────────────────────────────

    /** A header that scrolled away with the content would be useless, and making it row 0 of the list
     * would poison every index in the selection model. */
    @Test
    public void theHeaderIsScrollExemptAndNotPartOfTheModel() {
        build("a", "b", "c");
        UIElement header = null;
        for (UIElement child : table.getChildren()) {
            if (child.hasClass(TableView.HEADER_CLASS)) header = child;
        }
        assertNotNull("the table has a header element", header);
        assertTrue("which does not scroll with the rows", header.isScrollExempt());
        assertEquals("and is not a row", 3, table.getModel().size());
    }

    @Test
    public void theHeaderMarksTheSortedColumn() {
        build("a", "b");
        table.toggleSort(nameColumn);
        settle();

        UIElement header = null;
        for (UIElement child : table.getChildren()) {
            if (child.hasClass(TableView.HEADER_CLASS)) header = child;
        }
        UIElement firstCell = header.getChildren().get(0);
        assertTrue("ascending is marked for a theme to draw an arrow from",
                firstCell.hasClass(TableView.SORTED_ASC_CLASS));

        table.toggleSort(nameColumn);
        settle();
        header.getChildren().get(0);
        assertTrue(header.getChildren().get(0).hasClass(TableView.SORTED_DESC_CLASS));
    }

    // ── What it inherits ────────────────────────────────────────────────────

    /** Virtualisation, recycling and the keyboard all come from ListView — this only re-asserts the one
     * that would be most embarrassing to lose. */
    @Test
    public void aHugeTableStillRealisesOnlyAWindowful() {
        source = new ObservableList<>();
        for (int i = 0; i < 50_000; i++) source.add(new File("f" + i, i));
        table = new TableView<>(source);
        table.setItemHeight(10f);
        table.layout(l -> l.width(200).height(120));
        table.addColumn(TableColumn.<File>of("Name", File::name).width(100));

        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        root.addChild(table);
        window = new UIWindow(Ui.of(root));
        window.init(400, 400);
        settle();

        assertEquals(50_000, table.getModel().size());
        assertTrue("realised " + table.realisedCount(), table.realisedCount() < 25);
    }
}
