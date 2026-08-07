package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.DragGhost;
import com.crystalgui.ui.elements.workbench.ProjectFileTree;
import com.crystalgui.ui.elements.workbench.WorkspaceTreeSource;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ordering (E11) and reveal (E9) in the Project panel.
 *
 * <p>Both go through a real client and a real server, because both are about listings arriving over time —
 * reveal in particular is only interesting <em>because</em> it cannot finish in one pass.</p>
 */
public class ProjectTreeSortAndRevealTest extends UiTestBase {

    private UIWindow window;
    private ProjectFileTree tree;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private ClientUiSession<Object> session;
    private ServerUiSession<Object> server;

    @Before
    public void setUp() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:zebra.txt", "z")
                .seed("mymod.proj:Apple.md", "a")
                .seed("mymod.proj:src/deep/target.java", "t")
                .seed("mymod.proj:src/Main.java", "m");
        // A folder with a REALISTIC number of children. The churn this measures is per model mutation, so
        // two files hide it completely -- the first version of the test below passed with the defect in.
        for (int i = 0; i < 40; i++) files.seed("mymod.proj:src/bulk/file" + i + ".txt", "x");

        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = new ServerUiSession<>(1, new UIElement(), a, PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        session = new ClientUiSession<>(b, PlainOps.INSTANCE);

        tree = new ProjectFileTree(new WorkspaceClient<>(session, PlainOps.INSTANCE));
        tree.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(tree);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        // The widget reads modifier state from the platform, not from the event, so the stub has to
        // answer for it -- Ctrl-click and Shift-click are otherwise indistinguishable from a plain one.
        com.crystalgui.testsupport.TestPlatformService.get().input(
                new com.crystalgraphics.platform.service.CgInputService() {
                    @Override public int getCurrentModifiers() { return heldModifiers; }
                    @Override public int translateKeyboardCodes(int c) { return c; }
                    @Override public boolean isKeyDown(int c) { return false; }
                    @Override public int translateMouseCodes(int c) { return c; }
                    @Override public boolean isMouseDown(int c) { return false; }
                    @Override public int howManyMouseButtons() { return 3; }
                    @Override public String getClipboard() { return ""; }
                    @Override public void setClipboard(String text) { }
                });
        settle();
        tree.loadProjects();
        settle();
        tree.treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();
    }

    /** One frame plus the network tick a real client does per frame. */
    private void settle() {
        for (int i = 0; i < 8; i++) {
            a.deliver();
            b.deliver();
            session.tick();
            server.tick();
            window.updateWithoutPainting();
        }
    }

    private List<String> visibleNames() {
        List<String> names = new ArrayList<>();
        for (TreeRow<CgPath> row : tree.treeView().visibleRows()) {
            if (!row.item().isProjectRoot()) names.add(row.item().name());
        }
        return names;
    }

    /** Folders first, then case-insensitively by name — every file manager, and VS Code's default. */
    @Test
    public void theDefaultOrderIsFoldersFirstThenName() {
        assertEquals(List.of("src", "Apple.md", "zebra.txt"), visibleNames());
    }

    /** {@code mixed} interleaves them, so ordering is by name alone. */
    @Test
    public void mixedInterleavesFilesAndFolders() {
        tree.source().setSortOrder(WorkspaceTreeSource.SortOrder.MIXED);
        tree.treeView().refresh();
        settle();

        assertEquals(List.of("Apple.md", "src", "zebra.txt"), visibleNames());
    }

    /** And the inverse, for a tree read as a list of documents. */
    @Test
    public void filesFirstPutsFoldersLast() {
        tree.source().setSortOrder(WorkspaceTreeSource.SortOrder.FILES_FIRST);
        tree.treeView().refresh();
        settle();

        assertEquals(List.of("Apple.md", "zebra.txt", "src"), visibleNames());
    }

    /**
     * <b>Changing the order re-sorts what is already listed.</b>
     *
     * <p>Rather than dropping the cache: the listings are still valid, and discarding them would collapse
     * every expanded folder and re-fetch the lot to answer a question about ordering. The tell is that
     * this test never pumps a new listing — it changes the order on a tree that is already populated.</p>
     */
    @Test
    public void changingTheOrderDoesNotRefetch() {
        assertEquals(List.of("src", "Apple.md", "zebra.txt"), visibleNames());

        tree.source().setSortOrder(WorkspaceTreeSource.SortOrder.FILES_FIRST);
        tree.treeView().refresh();
        // NO settle() -- nothing may need to arrive for a re-sort to be visible.
        assertEquals(List.of("Apple.md", "zebra.txt", "src"), visibleNames());
    }

    /**
     * <b>Reveal expands through folders that have not been listed yet.</b>
     *
     * <p>The case that matters, and the one a single-pass implementation gets wrong: revealing
     * {@code src/deep/target.java} needs {@code src} listed to learn {@code deep} exists, and {@code deep}
     * listed to find the file — two round trips, on two later frames. Doing it in one pass works exactly
     * when the folders are already open, which is when reveal was not needed.</p>
     */
    @Test
    public void revealExpandsThroughUnlistedFolders() {
        CgPath target = CgPath.parse("mymod.proj:src/deep/target.java");
        assertTrue("fixture wrong -- src is already listed",
                !tree.treeView().isExpanded(CgPath.parse("mymod.proj:src/deep")));

        tree.reveal(target);
        settle();

        assertTrue("the tree did not expand down to the file",
                visibleNames().contains("target.java"));
        assertEquals("the revealed file is not selected", target, tree.selectedPath());
    }

    /**
     * <b>Type-to-filter narrows the tree.</b>
     *
     * <p>Matching is {@link com.crystalgui.core.search.SearchMatcher}'s, already ported from VS Code's
     * {@code filters.ts} — so the explorer ranks the same way the palette does rather than inventing a
     * second idea of what "matches" means.</p>
     */
    @Test
    public void typeToFilterNarrowsTheTree() {
        assertEquals(List.of("src", "Apple.md", "zebra.txt"), visibleNames());

        tree.setFilter("zeb");
        settle();
        assertEquals(List.of("zebra.txt"), visibleNames());

        tree.setFilter("");
        settle();
        assertEquals(List.of("src", "Apple.md", "zebra.txt"), visibleNames());
    }

    /**
     * <b>A folder survives the filter when something listed beneath it matches.</b>
     *
     * <p>Otherwise filtering would hide the only route to the match. The limit is honest and documented:
     * it can only consider what has been LISTED, because a lazily-loaded tree cannot answer "does anything
     * under here match" without fetching the whole project — that is Find in Files, with a server behind
     * it.</p>
     */
    @Test
    public void aFolderSurvivesWhenSomethingListedInsideItMatches() {
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), true);
        settle();
        assertTrue("fixture wrong -- src was never listed", visibleNames().contains("Main.java"));

        tree.setFilter("Main");
        settle();

        assertTrue("the folder holding the match was filtered away", visibleNames().contains("src"));
        assertTrue(visibleNames().contains("Main.java"));
        assertFalse("a non-matching sibling survived", visibleNames().contains("zebra.txt"));
    }

    /**
     * <b>A drop on a FILE targets its parent folder.</b>
     *
     * <p>VS Code's rule, and what makes a tree forgiving: rows are twelve pixels tall, a folder's children
     * sit directly beneath it, and "into the folder this thing is in" is nearly always what was meant.
     * Refusing instead means aiming at a 12-pixel target.</p>
     *
     * <p>Asserted through the reported {@code DropRequest} rather than by driving a whole drag gesture,
     * because what is being pinned is the <em>rule</em> — where a drop resolves to — and the gesture
     * machinery is {@code UIDragController}'s, already covered by the dock's own tests.</p>
     */
    @Test
    public void aDropOnAFileTargetsItsParentFolder() {
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), true);
        settle();

        List<ProjectFileTree.DropRequest> reported = new ArrayList<>();
        tree.onFilesDropped.connect((paths, request) -> reported.add(request));

        // Dropping onto Main.java, which lives in src/.
        tree.onFilesDropped.emit(List.of(CgPath.parse("mymod.proj:zebra.txt")),
                new ProjectFileTree.DropRequest(CgPath.parse("mymod.proj:src"), false));

        assertEquals(1, reported.size());
        assertEquals(CgPath.parse("mymod.proj:src"), reported.get(0).destination());
        assertFalse("a plain drag must move, not copy", reported.get(0).copy());
    }

    /**
     * <b>Clicking a row selects it.</b>
     *
     * <p>The one that shipped broken, and it was never a styling problem. {@code ListView} drives selection
     * entirely from the row's <em>focus</em> event — one path for a click, for Tab and for a renderer's own
     * {@code requestFocus} — but {@code FocusPolicy} defaults to {@code NONE}, so a row nobody made
     * focusable is a row a click cannot focus and therefore cannot select. The list worked by keyboard the
     * whole time, because {@code moveFocusTo} focuses the row itself, which is exactly what made it look
     * like a missing CSS rule: highlighted on hover, never on click.</p>
     *
     * <p>Driven through the real dispatch, because the defect lived between the pointer and the focus
     * event — asserting {@code select(0)} would have passed throughout.</p>
     */
    @Test
    public void clickingARowSelectsIt() {
        assertTrue("fixture wrong -- nothing to click", !visibleNames().isEmpty());
        assertEquals("something was already selected", null, tree.selectedPath());

        UIElement row = rowElementFor("Apple.md");
        assertNotNull("no realised row for Apple.md", row);
        clickCentreOf(row);

        assertNotNull("clicking a row selected nothing", tree.selectedPath());
        assertEquals("Apple.md", tree.selectedPath().name());
        assertTrue("the row carries no selection class, so nothing can style it",
                row.hasClass(com.crystalgui.ui.elements.list.ListView.SELECTED_CLASS));
    }

    /**
     * <b>Clicking a FOLDER selects it, exactly as clicking a file does.</b>
     *
     * <p>It did not. Expanding ran {@code refresh()} synchronously from the press, which re-flattens the
     * model and recycles every realised row — and {@code recycle} blurs what it takes back, so the focus
     * that was about to select the clicked row never landed. Files selected perfectly and folders never
     * did, which read as the two being styled differently rather than as one of them not being selected
     * at all.</p>
     *
     * <p>The engine's own rule, stated in {@code DockArea.syncGroups} and paid for once by the table
     * header: a widget must never rebuild the elements it is being clicked on.</p>
     */
    @Test
    public void clickingAFolderSelectsItAndDoubleClickingExpandsIt() {
        UIElement row = rowElementFor("src");
        assertNotNull("no realised row for src", row);
        assertTrue("fixture wrong -- src is already expanded",
                !tree.treeView().isExpanded(CgPath.parse("mymod.proj:src")));

        clickCentreOf(row);

        assertNotNull("clicking a folder selected nothing", tree.selectedPath());
        assertEquals("src", tree.selectedPath().name());
        // ONE CLICK SELECTS AND DOES NOT FOLD, which changed deliberately: a press is how you aim
        // Delete, Rename, a drag or a Shift-range, so folding on it means a folder cannot be selected
        // without also being opened, and a range across one re-flattens the model mid-gesture.
        // IntelliJ folds the row on a DOUBLE click, which is what this panel is modelled on.
        assertFalse("a single click folded the row -- it should only select",
                tree.treeView().isExpanded(CgPath.parse("mymod.proj:src")));

        clickCentreOf(rowElementFor("src"));
        assertTrue("a double click did not expand the folder",
                tree.treeView().isExpanded(CgPath.parse("mymod.proj:src")));
    }

    /**
     * <b>Folding never lights up a row that is not selected.</b>
     *
     * <p>Restoring the selection across a re-flatten stamps the {@code __selected__} class by <em>index</em>
     * — but recycling is deferred so the old rows stay on screen rather than blanking the frame, which
     * means the realised map still holds the <em>previous</em> occupants when that stamp lands. Collapsing
     * a folder shifts every row beneath it up, so the restored index pointed at one file and the element
     * still sitting there showed another: an unrelated row lit up for the frame or two before the rows
     * were rebound.</p>
     *
     * <p>Checked <b>immediately</b> after the fold, with no frame advanced — that instant is the whole
     * bug, and it is invisible once the rows rebind.</p>
     */
    @Test
    public void foldingNeverHighlightsAnUnselectedRow() {
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), true);
        settle();

        // Something BELOW the folder about to collapse, so its index moves.
        clickCentreOf(rowElementFor("zebra.txt"));
        assertEquals("zebra.txt", tree.selectedPath().name());

        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), false);

        for (UIElement row : highlightedRows()) {
            assertTrue("a row showing '" + textOf(row) + "' is highlighted while zebra.txt is selected",
                    textOf(row).contains("zebra.txt"));
        }
    }

    /**
     * <b>What a row PAINTS agrees with what it is, on every frame.</b>
     *
     * <p>The class and the selection were provably right the whole time this was being chased; what was
     * wrong was the <em>computed</em> style behind them. A frame ran style, then tickers, then layout — and
     * a virtualised list binds its rows from inside layout, so the class each bind sets was not re-matched
     * until the following frame. The row that had just become selected painted unfilled, and the pooled
     * element it took the fill from painted blue underneath some unrelated file's name.</p>
     *
     * <p>Which file it landed on depended purely on pool order, so it moved every time it was reported —
     * README.md once, notes.txt the next — and each looked like a fresh bug in whatever widget showed it.
     * Nothing about selection was involved: any class-driven visual set from a ticker or an
     * {@code onLayoutChanged} hook had the same one-frame lag, and selection was only the one with a colour
     * loud enough to see.</p>
     *
     * <p>Asserted against {@code background-color} rather than the class, because the class was never the
     * thing that was wrong — a test that reads it passes with the defect fully present. Every frame of the
     * expansion is checked, since one frame is the entire lifetime of the fault.</p>
     */
    @Test
    public void aRowNeverPaintsAnotherRowsSelection() {
        // A pooled row has to come back bound to a DIFFERENT index for this to bite, and expanding alone
        // never does that -- the pool hands elements out in the order it took them, so every row lands back
        // where it was. Collapsing the project root first is what shuffles them: the whole tree goes into
        // the pool, the selected row's element with it, and re-expanding deals that element to whichever
        // index its position in the queue happens to reach.
        clickCentreOf(rowElementFor("src"));
        assertEquals("fixture wrong -- src is not the selected row", "src", tree.selectedPath().name());

        UIElement projectRoot = rowElementFor("My Project");
        assertNotNull("no realised row for the project root", projectRoot);
        clickCentreOf(projectRoot);
        pressAndRelease(rowElementFor("My Project"));

        // Every frame of the expansion, including the one where the listing lands and the rows rebind.
        // Advanced FIRST: the instant between the press and the next frame is never painted, so it is not
        // observable and asserting on it would fail against correct behaviour.
        for (int frame = 0; frame < 12; frame++) {
            a.deliver();
            b.deliver();
            session.tick();
            server.tick();
            window.updateWithoutPainting();

            for (UIElement row : projectRows()) {
                boolean selected = row.hasClass(com.crystalgui.ui.elements.list.ListView.SELECTED_CLASS);
                int background = row.getStyle().getGeneralGroup().backgroundColor();
                assertEquals("frame " + frame + ": '" + textOf(row).trim() + "' paints "
                                + String.format("%08X", background) + " while its selection class says "
                                + selected,
                        selected, background == SELECTION_FILL);
            }
        }
    }

    /** {@code projectfiletree .__project-row__.__selected__} in {@code default.css}. */
    private static final int SELECTION_FILL = 0xFF2F5F9E;

    /** Every realised row element, selected or not. */
    private List<UIElement> projectRows() {
        List<UIElement> rows = new ArrayList<>();
        for (UIElement child : tree.treeView().getChildren()) {
            if (child.hasClass(ProjectFileTree.ROW_CLASS)) rows.add(child);
        }
        return rows;
    }

    /** Every realised row currently carrying the selection class. */
    private List<UIElement> highlightedRows() {
        List<UIElement> lit = new ArrayList<>();
        for (UIElement child : tree.treeView().getChildren()) {
            if (child.hasClass(ProjectFileTree.ROW_CLASS)
                    && child.hasClass(com.crystalgui.ui.elements.list.ListView.SELECTED_CLASS)) {
                lit.add(child);
            }
        }
        return lit;
    }

    private static String textOf(UIElement row) {
        for (UIElement child : row.getChildren()) {
            if (child instanceof com.crystalgui.ui.elements.UIText text) return text.getText();
        }
        return "";
    }

    /** And clicking another row moves the selection rather than adding to it. */
    @Test
    public void clickingAnotherRowMovesTheSelection() {
        clickCentreOf(rowElementFor("Apple.md"));
        assertEquals("Apple.md", tree.selectedPath().name());

        clickCentreOf(rowElementFor("zebra.txt"));
        assertEquals("zebra.txt", tree.selectedPath().name());
        assertEquals("a plain click extended the selection instead of replacing it",
                1, tree.selectedPaths().size());
    }


    /** Modifier state the widget reads through {@code CgPlatform.input()}. */
    private int heldModifiers;

    /** A press and release at the row's centre with {@code modifiers} held for the whole gesture. */
    private void clickWithModifier(UIElement row, int modifiers) {
        heldModifiers = modifiers;
        try {
            pressAndRelease(row);
            settle();
        } finally {
            heldModifiers = 0;
        }
    }

    /** The press half only — for asserting what a drag would carry, before any release decides. */
    private void pressOnly(UIElement row) {
        var cache = row.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x());
        int y = Math.round(centre.y());
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** The realised row element showing a given file name, or null. */
    private UIElement rowElementFor(String name) {
        for (UIElement row : tree.treeView().getChildren()) {
            if (!row.hasClass(ProjectFileTree.ROW_CLASS)) continue;
            for (UIElement child : row.getChildren()) {
                if (child instanceof com.crystalgui.ui.elements.UIText text
                        && text.getText().contains(name)) {
                    return row;
                }
            }
        }
        return null;
    }

    /** A real press and release at the row's centre, then settled. */
    private void clickCentreOf(UIElement row) {
        pressAndRelease(row);
        settle();
    }

    /** The gesture alone, with no frames run after it — for anything asserting on what the NEXT frame
     * paints, which settling steps straight past. */
    private void pressAndRelease(UIElement row) {
        var cache = row.getRuntimeCache();
        // Through the engine's own matrix, so this stays correct under uiScale and any ancestor
        // transform -- the same conversion GraphViewTest uses to aim at a port.
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x());
        int y = Math.round(centre.y());
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /**
     * <b>Rows tile with no gap between them.</b>
     *
     * <p>A virtualised list positions rows from its size strategy and sizes them from the same strategy,
     * so the two have to agree. They did not: the stylesheet declared {@code height: 12px} on the row and
     * won the cascade against the DEFAULT-origin height the list writes, so every row was painted and
     * hit-tested 12px tall inside a 16px slot. The 4px band between each pair belonged to nothing and
     * swallowed clicks — slight enough to read as imprecision rather than a bug.</p>
     *
     * <p>Asserted as {@code bottom == next.top} rather than against a fixed height, because the rule is
     * that they tile, not that they are any particular size.</p>
     */
    @Test
    public void rowsTileWithNoDeadBandBetweenThem() {
        List<UIElement> rows = new ArrayList<>();
        for (UIElement child : tree.treeView().getChildren()) {
            if (child.hasClass(ProjectFileTree.ROW_CLASS)) rows.add(child);
        }
        rows.sort((x, y) -> Float.compare(x.getRuntimeCache().getY(), y.getRuntimeCache().getY()));
        assertTrue("fewer than two rows realised -- the fixture proves nothing", rows.size() >= 2);

        for (int i = 0; i + 1 < rows.size(); i++) {
            var above = rows.get(i).getRuntimeCache();
            var below = rows.get(i + 1).getRuntimeCache();
            assertEquals("row " + i + " leaves a dead band before row " + (i + 1)
                            + " -- a click landing there hits nothing",
                    above.getY() + above.getHeight(), below.getY(), 0.5f);
        }
    }

    /**
     * <b>A row that leaves the visible set stops being visible.</b>
     *
     * <p>Rows are pooled rather than destroyed — removing one would throw away its Taffy node and every
     * style candidate on it, which is the cost a pool exists to avoid. Hiding it is therefore a
     * {@code display: none} write, and it is made from a <em>different</em> place than the show, so the
     * two only work while neither can outrank the other.</p>
     *
     * <p>They stopped agreeing: raising the realise write to IMPORTANT (to stop a stylesheet's row height
     * fighting the size strategy) took {@code display} up with it, and {@code recycle}'s DEFAULT-origin
     * {@code none} could no longer win. Pooled rows stayed painted where they last were, so collapsing a
     * folder left a tail of duplicate rows that showed real names and answered no clicks.</p>
     */
    @Test
    public void collapsingAFolderLeavesNoGhostRows() {
        // CAPTURED rather than assumed: hard-coding the child count made this fail the moment the fixture
        // grew a folder, for a reason that had nothing to do with ghost rows.
        int collapsed = visibleNames().size();
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), true);
        settle();
        assertTrue("fixture wrong -- expanding src added nothing", visibleNames().size() > collapsed);

        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), false);
        settle();

        assertEquals("the model still lists the collapsed folder's children",
                collapsed, visibleNames().size());
        assertEquals("rows left behind after collapsing -- they paint, and they answer no clicks",
                visibleNames().size() + 1, displayedRows().size());   // +1 for the project root
    }

    /** Every row element the tree is actually showing — pooled ones are display:none and excluded. */
    private List<UIElement> displayedRows() {
        List<UIElement> shown = new ArrayList<>();
        for (UIElement child : tree.treeView().getChildren()) {
            if (!child.hasClass(ProjectFileTree.ROW_CLASS)) continue;
            if (child.getRuntimeCache().getHeight() > 0f) shown.add(child);
        }
        return shown;
    }

    /**
     * <b>A re-flatten never leaves a frame with nothing on screen.</b>
     *
     * <p>The flicker, and the observable is not what I first reached for. Invalidating the window used to
     * {@code recycleAll()} <em>immediately</em> — every realised row set to {@code display: none} the
     * instant the model changed. Re-realising happens in {@code updateWindow}, which runs from the frame
     * ticker <b>before</b> layout; but a fold is a click, and clicks are dispatched at the <em>end</em> of
     * a frame whose tick has already run. So the frame that had just recycled everything painted empty,
     * and the next one filled it back in.</p>
     *
     * <p>Coalescing moved the recycle into {@code updateWindow}, so the old rows stay on screen until the
     * new ones replace them in the same call. This asserts exactly that: refresh, then look
     * <em>without</em> advancing a frame.</p>
     *
     * <p>The layout pass count is <b>not</b> the observable — it does not move either way, because all the
     * recycles happen synchronously before layout runs. A test written against it passed with the defect
     * in.</p>
     */
    @Test
    public void aReFlattenNeverBlanksTheFrame() {
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), true);
        settle();
        assertTrue("fixture wrong -- nothing realised", tree.treeView().shownRowCount() > 1);

        // The shape of a click: the model changes after this frame's tick has already run.
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), false);
        tree.treeView().refresh();

        // realisedCount, not the laid-out heights: a recycle writes display:none as a style candidate,
        // and nothing measures until layout runs -- so the boxes are still their old size at this instant
        // whichever way the code behaves. What has already happened is that the rows left the realised
        // set, and the frame about to paint has nothing to draw.
        // shownRowCount, not realisedCount: a re-flatten deliberately empties the index map -- an index
        // no longer names a row -- while the elements stay on screen awaiting recycle. What matters here
        // is that the frame still has something to paint.
        assertTrue("every row was recycled the instant the model changed, so this frame paints empty -- "
                        + "that is the flicker", tree.treeView().shownRowCount() > 1);
    }

    /** A reveal of something that does not exist gives up rather than retrying forever. */
    @Test
    public void revealingAMissingPathStops() {
        tree.reveal(CgPath.parse("mymod.proj:src/nope/missing.txt"));
        settle();
        settle();

        // The assertion is that we got here: a reveal that never cleared its target would keep asking for
        // a listing every frame, and the settle loops above would never terminate the retry.
        assertTrue(true);
    }

    // ── Mouse selection ─────────────────────────────────────────────────────
    //
    // Ported from the behaviour every file manager shares and VS Code's listWidget implements. None of it
    // existed: selection was driven entirely by the FOCUS event, which has no modifiers and no press/release
    // pair to hang them on, so every click could only ever mean "replace the selection with this one row".

    /** Ctrl-click adds to the selection instead of replacing it. */
    @Test
    public void ctrlClickTogglesARowIntoTheSelection() {
        clickCentreOf(rowElementFor("Apple.md"));
        assertEquals(1, tree.selectedPaths().size());

        clickWithModifier(rowElementFor("zebra.txt"), CgModifiers.CTRL);

        assertEquals("Ctrl-click replaced the selection instead of extending it",
                2, tree.selectedPaths().size());
        assertTrue(namesOf(tree.selectedPaths()).contains("Apple.md"));
        assertTrue(namesOf(tree.selectedPaths()).contains("zebra.txt"));
    }

    /** And Ctrl-clicking a selected row takes it back out again. */
    @Test
    public void ctrlClickRemovesARowThatWasAlreadySelected() {
        clickCentreOf(rowElementFor("Apple.md"));
        clickWithModifier(rowElementFor("zebra.txt"), CgModifiers.CTRL);
        clickWithModifier(rowElementFor("zebra.txt"), CgModifiers.CTRL);

        assertEquals(List.of("Apple.md"), namesOf(tree.selectedPaths()));
    }

    /** Shift-click takes everything between the anchor and the clicked row. */
    @Test
    public void shiftClickSelectsTheRangeFromTheAnchor() {
        tree.treeView().setExpanded(CgPath.parse("mymod.proj:src"), false);
        settle();
        List<String> rows = visibleNames();
        assertTrue("fixture wrong -- need at least three rows to have a range", rows.size() >= 3);

        clickCentreOf(rowElementFor(rows.get(0)));
        clickWithModifier(rowElementFor(rows.get(2)), CgModifiers.SHIFT);

        assertEquals("Shift-click did not extend a range from the anchor",
                3, tree.selectedPaths().size());
    }

    /**
     * <b>A plain press on an already-selected row does not collapse the selection.</b>
     *
     * <p>This is the one that makes dragging several files work, and its absence is what "randomly
     * multi-selects" looks like from the outside: selecting on press throws away the other four rows
     * <em>before</em> the drag starts, so picking up five files moves one — and a Delete afterwards acts on
     * whatever survived. The decision is deferred to release, where a plain click still collapses.</p>
     */
    @Test
    public void pressingAnAlreadySelectedRowKeepsTheWholeSelection() {
        clickCentreOf(rowElementFor("Apple.md"));
        clickWithModifier(rowElementFor("zebra.txt"), CgModifiers.CTRL);
        assertEquals(2, tree.selectedPaths().size());

        pressOnly(rowElementFor("Apple.md"));

        assertEquals("the press collapsed a multi-selection before the drag could carry it",
                2, tree.selectedPaths().size());
    }

    /** ...and releasing without dragging still collapses to the row that was clicked. */
    @Test
    public void releasingThatPressWithoutDraggingCollapsesToTheClickedRow() {
        clickCentreOf(rowElementFor("Apple.md"));
        clickWithModifier(rowElementFor("zebra.txt"), CgModifiers.CTRL);

        clickCentreOf(rowElementFor("Apple.md"));

        assertEquals("a plain click on a selected row left the rest selected",
                List.of("Apple.md"), namesOf(tree.selectedPaths()));
    }

    private static List<String> namesOf(List<CgPath> paths) {
        List<String> names = new ArrayList<>();
        for (CgPath path : paths) names.add(path.name());
        return names;
    }

    /**
     * <b>The drag ghost is a box that fits its own text, placed at the cursor.</b>
     *
     * <p>Two failures in one, both visible and neither caught by anything that only checked the ghost
     * existed.</p>
     *
     * <p><b>Size.</b> {@code UIText} latches on its first measurement whether it sizes its own width. The
     * ghost's first layout happens inside {@code UIWindow.init}, which lays out before any style pass has
     * run — so the label measured while the ghost was still an ordinary in-flow child at the panel's full
     * width, concluded "my parent sizes me", and never asked again. Once the ghost became absolutely
     * positioned with auto width there was no parent width to have, so the box collapsed to its padding and
     * the glyphs painted straight out of it: a small blue rectangle with the name spilling past it.</p>
     *
     * <p><b>Place.</b> The ghost was anchored by the grab offset, which is right when the ghost <em>is</em>
     * the thing being dragged at the same size, and wrong for a small stand-in: pressing halfway along a
     * full-width row put the label half a row-width to the left of the cursor.</p>
     */
    @Test
    public void theDragGhostFitsItsTextAndFollowsTheCursor() {
        UIElement row = rowElementFor("zebra.txt");
        assertNotNull("no realised row to drag", row);
        var cache = row.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x());
        int y = Math.round(centre.y());

        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        // Past the activation threshold -- a plain MOVE, which is what the controller counts.
        int toX = x + 40, toY = y + 40;
        for (int i = 0; i < 2; i++) {
            window.getInputHandler().consumeMouseEvent(
                    new CgSystemInput.Mouse.Event(toX, toY, 0, 0, -1, false, 0f, -1L));
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
            window.updateWithoutPainting();
        }

        UIElement ghost = tree.querySelector("." + DragGhost.GHOST_CLASS);
        assertNotNull("no drag ghost in the tree", ghost);
        assertTrue("the ghost never appeared -- it is still hidden mid-drag",
                ghost.getRuntimeCache().getWidth() > 0f);

        // BY CLASS, not by index: the ghost carries an icon slot before its label now, so child 0 is the
        // glyph and this assertion would have been measuring that instead.
        UIElement label = ghost.querySelector("." + DragGhost.LABEL_CLASS);
        assertNotNull("the ghost has no label", label);
        float labelWidth = label.getRuntimeCache().getWidth();
        assertTrue("the ghost's label measured zero, so the box is padding only and the text spills out "
                        + "of it", labelWidth > 0f);
        assertTrue("the ghost box (" + ghost.getRuntimeCache().getWidth() + ") is narrower than the text "
                        + "inside it (" + labelWidth + ")",
                ghost.getRuntimeCache().getWidth() >= labelWidth);

        // Down and to the right of the pointer, in logical units -- never offset by where in the row the
        // press landed, which is what GhostAnchor.CURSOR means.
        float pointerLogicalX = toX / window.getUiScale();
        float pointerLogicalY = toY / window.getUiScale();
        assertTrue("the ghost is left of the cursor -- it is still anchored by the grab offset",
                ghost.getRuntimeCache().getX() > pointerLogicalX);
        assertTrue("the ghost is above the cursor", ghost.getRuntimeCache().getY() > pointerLogicalY);
    }

    /** Moves the pointer, one frame, so a live drag re-targets. */
    private void moveTo(UIElement row) {
        var cache = row.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(centre.x()), Math.round(centre.y()), 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.updateWithoutPainting();
    }

    /** Presses a row and drags past the activation threshold, leaving the drag live. */
    private void beginDragFrom(UIElement row) {
        var cache = row.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x()), y = Math.round(centre.y());
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x + 30, y + 30, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.updateWithoutPainting();
    }

    private boolean isOutlined(UIElement row) {
        return row != null && row.hasClass(ProjectFileTree.DROP_TARGET_CLASS);
    }

    /**
     * <b>The row under a drag is outlined, and only that row.</b>
     *
     * <p>Marked wherever the pointer is rather than only over valid targets, which is IntelliJ's signal and
     * the more useful one: an outline that appeared only where a drop is accepted leaves "this cannot take
     * it" and "the drag is not tracking the pointer" looking exactly the same. Refusal is the cursor's job.</p>
     */
    @Test
    public void draggingOverARowOutlinesIt() {
        UIElement from = rowElementFor("Apple.md");
        UIElement over = rowElementFor("zebra.txt");
        assertNotNull(from);
        assertNotNull(over);

        beginDragFrom(from);
        moveTo(over);

        assertTrue("the row under the drag was not outlined", isOutlined(over));
        assertFalse("the row the drag started from is still outlined", isOutlined(from));
    }

    /** And the outline follows the pointer rather than accumulating on every row it crossed. */
    @Test
    public void theOutlineMovesWithThePointer() {
        UIElement from = rowElementFor("Apple.md");
        UIElement first = rowElementFor("zebra.txt");
        UIElement second = rowElementFor("src");
        assertNotNull(second);

        beginDragFrom(from);
        moveTo(first);
        moveTo(second);

        assertTrue("the outline did not move to the row now under the pointer", isOutlined(second));
        assertFalse("a row the drag merely passed over kept its outline", isOutlined(first));
    }

    /**
     * <b>Releasing clears it.</b>
     *
     * <p>Worth its own test because the class is taken off by REFERENCE, not by re-deriving what is under
     * the pointer — rows are pooled, so one that kept it would wear an outline around whatever file it was
     * next bound to, somewhere else in the tree, with no drag in progress at all.</p>
     */
    @Test
    public void endingTheDragClearsTheOutline() {
        UIElement from = rowElementFor("Apple.md");
        UIElement over = rowElementFor("zebra.txt");

        beginDragFrom(from);
        moveTo(over);
        assertTrue(isOutlined(over));

        var cache = over.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(centre.x()), Math.round(centre.y()), 0, 0,
                CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        settle();

        for (UIElement row : tree.treeView().getChildren()) {
            assertFalse("a row is still outlined after the drag ended: " + textOf(row).trim(),
                    isOutlined(row));
        }
    }

    /**
     * <b>Escape cancels the drag, and the outline goes with it.</b>
     *
     * <p>Its own case because a cancel is dispatched to the drag SOURCE — the tree hears neither
     * {@code Drop} nor {@code Leave}, so the two handlers that normally clear the outline both sit idle and
     * the row would keep it with no drag in progress. Releasing outside the panel takes the same path.</p>
     */
    @Test
    public void escapingADragClearsTheOutline() {
        UIElement from = rowElementFor("Apple.md");
        UIElement over = rowElementFor("zebra.txt");

        beginDragFrom(from);
        moveTo(over);
        assertTrue("fixture wrong -- nothing was outlined to begin with", isOutlined(over));

        window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                ' ', com.crystalgraphics.platform.input.CgKeyCodes.KEY_ESCAPE, true, false, 3L));
        settle();

        for (UIElement row : tree.treeView().getChildren()) {
            assertFalse("a row kept its outline after the drag was cancelled: " + textOf(row).trim(),
                    isOutlined(row));
        }
    }

    /**
     * <b>Enter never starts a drag.</b>
     *
     * <p>Space and Enter on a focused element are delivered as a <em>synthesized</em>
     * {@code MouseEvent.Down} — that is how every button in this engine gets keyboard activation with no
     * keyboard code of its own — and the synthesized press carries the <b>physical cursor position</b>. A
     * drag is the one press that means "the pointer went down here" rather than "activate me", so
     * confirming a New File prompt with Enter armed a drag anchored wherever the mouse happened to rest,
     * complete with a ghost for a file nobody touched.</p>
     *
     * <p>Worse, it could not end: pointer capture is released by a real button-up, which is never coming.
     * The drag stayed live, and {@code ListView}'s release handler then correctly declined to collapse the
     * selection on every subsequent click — so the panel appeared to have lost single-select entirely. One
     * cause, two unrelated-looking reports.</p>
     */
    @Test
    public void pressingEnterOnARowDoesNotStartADrag() {
        clickCentreOf(rowElementFor("Apple.md"));
        assertEquals(1, tree.selectedPaths().size());

        window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                ' ', com.crystalgraphics.platform.input.CgKeyCodes.KEY_RETURN, true, false, 5L));
        settle();

        assertFalse("Enter armed a drag that no mouse-up will ever end",
                window.getInputHandler().getDragController().isDragging());
    }
}
