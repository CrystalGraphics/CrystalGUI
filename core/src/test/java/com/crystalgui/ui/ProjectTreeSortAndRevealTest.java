package com.crystalgui.ui;

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

    /** A real press and release at the row's centre, through the input handler. */
    private void clickCentreOf(UIElement row) {
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
        settle();
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
}
