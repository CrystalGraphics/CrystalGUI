package com.crystalgui.ui;

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
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Compact folders — VS Code's {@code explorer.compactFolders}, IntelliJ's <i>Compact Empty Middle
 * Packages</i>.
 *
 * <h3>Its own fixture, deliberately</h3>
 *
 * <p>Compaction needs a single-child directory chain, and adding one to the shared tree fixture changed
 * the root listing that five unrelated sort and filter tests assert exactly. A feature that needs a
 * different shape of project gets a different project.</p>
 */
public class ProjectTreeCompactFoldersTest extends UiTestBase {

    private UIWindow window;
    private ProjectFileTree tree;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private ClientUiSession<Object> session;
    private ServerUiSession<Object> server;

    @Before
    public void setUp() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                // `chain` holds only `a`, which holds only `b`, which holds one file: the whole run is
                // one row when compaction is on.
                .seed("mymod.proj:chain/a/b/Leaf.java", "l")
                // `wide` has two children, so it must never compact however deep they go.
                .seed("mymod.proj:wide/one/File.java", "1")
                .seed("mymod.proj:wide/two/File.java", "2");

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

        // A settle BEFORE loadProjects: the client session has to open first, and a projects call made
        // before it is addressed to a window the server discards -- silently, with no error at all.
        settle();
        tree.loadProjects();
        settle();
        tree.treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();
    }

    /**
     * Runs the round trips out.
     *
     * <p>Generously, because compaction is answered from <b>listings</b>: {@code chain} cannot be known
     * to have one child until its own listing lands, and the chain grows one directory per response. A
     * single settle would test the tree mid-crawl.</p>
     */
    private void settle() {
        for (int i = 0; i < 40; i++) {
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
            names.add(tree.source().rowLabel(row.item()));
        }
        return names;
    }

    private void expandAll() {
        for (int pass = 0; pass < 6; pass++) {
            for (TreeRow<CgPath> row : new ArrayList<>(tree.treeView().visibleRows())) {
                if (row.expandable()) tree.treeView().setExpanded(row.item(), true);
            }
            settle();
        }
    }

    @Test
    public void aSingleChildChainIsOneRow() {
        expandAll();
        // chain/a/b, not a/b: the carve-out protects the ROOT's row, and `chain` is a child of the root
        // rather than the root itself -- so `a` merges into `chain` exactly as `b` merges into `a`. VS
        // Code shows `src/main/java` for the same reason and never `project/src/main/java`.
        assertTrue("the chain should read as one row: " + visibleNames(),
                visibleNames().contains("chain/a/b"));
        assertFalse("a swallowed directory must not also be a row of its own",
                visibleNames().contains("a"));
        assertFalse(visibleNames().contains("b"));
    }

    /**
     * <b>A top-level folder keeps its own row.</b>
     *
     * <p>VS Code's rule exactly — {@code isIncompressible} refuses to merge a node whose parent is a
     * <b>root</b>. Hiding a project's own folders inside a path is hiding the project.</p>
     */
    @Test
    public void aRootsChildIsNeverSwallowed() {
        expandAll();
        assertTrue("the project's own row is gone", visibleNames().contains("My Project"));
        for (String name : visibleNames()) {
            assertFalse("the root was swallowed into a path: " + name,
                    name.startsWith("My Project/"));
        }
    }

    @Test
    public void aDirectoryWithTwoChildrenNeverCompacts() {
        expandAll();
        List<String> names = visibleNames();
        assertTrue("a branching directory must keep every row: " + names, names.contains("one"));
        assertTrue(names.contains("two"));
    }

    @Test
    public void compactionCanBeTurnedOff() {
        tree.source().setCompactFolders(false);
        expandAll();
        assertTrue("with compaction off every directory is its own row: " + visibleNames(),
                visibleNames().contains("a"));
        assertEquals("and its label is just its name", "b",
                tree.source().rowLabel(CgPath.parse("mymod.proj:chain/a/b")));
    }

    /**
     * <b>Reveal still finds a file inside a chain.</b>
     *
     * <p>The half that is easy to miss: an intermediate directory is not a row at all once compacted, so
     * expanding it sets a flag nothing reads — the reveal walks the whole way down and then finds nothing
     * to select. {@code visibleRowFor} is what maps a path back to the row standing for it.</p>
     */
    @Test
    public void revealFindsAFileInsideACompactedChain() {
        CgPath target = CgPath.parse("mymod.proj:chain/a/b/Leaf.java");
        tree.reveal(target);
        settle();
        settle();

        assertTrue("the file inside the compacted chain was never revealed: " + visibleNames(),
                visibleNames().contains("Leaf.java"));
        assertEquals("and it is not selected", target, tree.selectedPath());
    }

    /** A row that stops being a chain loses its label rather than keeping a path that is no longer true. */
    @Test
    public void aLabelIsDroppedWhenTheChainStops() {
        expandAll();
        assertEquals("chain/a/b", tree.source().rowLabel(CgPath.parse("mymod.proj:chain/a/b")));

        tree.source().setCompactFolders(false);
        expandAll();
        assertEquals("a stale label renders a structure the project does not have", "b",
                tree.source().rowLabel(CgPath.parse("mymod.proj:chain/a/b")));
    }
}
