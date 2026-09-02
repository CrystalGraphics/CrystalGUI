package com.crystalgui.ui;

import com.crystalgui.support.OldEngineSessions;
import com.crystalgui.core.collection.table.SortOrder;
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
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.workbench.ProjectFileTree;
import com.crystalgui.ui.elements.workbench.WorkspaceTreeSource;
import com.crystalgui.ui.elements.workbench.decoration.FileDecoration;
import com.crystalgui.ui.elements.workbench.decoration.FileDecorationProvider;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Icons and decorations on a Project panel row (E7).
 *
 * <p>Through a real client and a real server, like {@code ProjectTreeSortAndRevealTest}, because rows are
 * only interesting once listings have arrived and the view has recycled a template at least once.</p>
 */
public class ProjectTreeIconsTest extends UiTestBase {

    private UIWindow window;
    private ProjectFileTree tree;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private ClientUiSession<UIElement, Object> session;
    private ServerUiSession<UIElement, Object> server;

    @Before
    public void setUp() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:zebra.txt", "z")
                .seed("mymod.proj:Apple.md", "a")
                .seed("mymod.proj:src/Main.java", "m")
                // Shaped like the reported screenshot: a second folder with children of its own, a file
                // with a space in its name, and one with no extension at all.
                .seed("mymod.proj:fah/bababa copy.java", "b")
                .seed("mymod.proj:fah/fafafa", "f")
                .seed("mymod.proj:README.md", "r")
                .seed("mymod.proj:src/mama.glsl", "g");

        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = OldEngineSessions.serve(1, new UIElement(), a);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        session = OldEngineSessions.view(b);

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
        tree.treeView().setExpanded(CgPath.of("mymod.proj", "src"), true);
        tree.treeView().setExpanded(CgPath.of("mymod.proj", "fah"), true);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            a.deliver();
            b.deliver();
            session.tick();
            server.tick();
            window.updateWithoutPainting();
        }
    }

    /** The realised row elements, in visual order. */
    private List<UIElement> rows() {
        return tree.querySelectorAll("." + ProjectFileTree.ROW_CLASS);
    }

    private UIElement iconOf(UIElement row) {
        UIElement icon = row.querySelector("." + ProjectFileTree.ICON_CLASS);
        assertNotNull("the row has no icon slot", icon);
        return icon;
    }

    private static String filetypeClassOf(UIElement element) {
        List<String> found = new ArrayList<>();
        for (String cls : element.getClasses()) {
            if (cls.startsWith("filetype-")) found.add(cls);
        }
        // ONE, not "contains". The recycling defect this guards leaves the previous file's class behind,
        // and a contains() check passes with both on -- see recyclingDoesNotAccumulateFiletypeClasses.
        assertEquals("expected exactly one filetype class, got " + found, 1, found.size());
        return found.get(0);
    }

    private UIElement rowNamed(String name) {
        for (int i = 0; i < rows().size(); i++) {
            CgPath item = tree.treeView().visibleRows().get(i).item();
            if (name.equals(item.name())) return rows().get(i);
        }
        throw new AssertionError("no visible row named " + name + " among " + rows().size());
    }

    /**
     * <b>A file row carries the theme's icon and its own file-type class.</b>
     *
     * <p>Both halves matter and they fail apart: the class alone leaves a blank slot, and the drawable
     * alone leaves every file the same colour with nothing for a stylesheet to target.</p>
     */
    @Test
    public void aFileRowGetsItsIconAndFiletypeClass() {
        UIElement icon = iconOf(rowNamed("Main.java"));
        assertEquals("filetype-java", filetypeClassOf(icon));

        CgUiDrawable overlay = icon.getStyle().getGeneralGroup()
                .getValueSave(StylePropertyRegistry.OVERLAY);
        assertTrue("the icon slot holds " + overlay + ", not a vector icon", overlay instanceof CgUiSvg);

        // Identity against a fresh lookup, which is exact: SvgDocument.of caches per path, so the row can
        // only be holding this instance if it resolved this path. Asserting "not EMPTY" instead would pass
        // on the wrong icon entirely, which is the failure a wrong extension map produces.
        //
        // Through withVariant rather than naming java.svg outright, so this asserts "the java icon" and not
        // "the light java icon" -- the active variant is a global, and pinning one here makes flipping the
        // default fail a test about the extension map for a reason that has nothing to do with it.
        assertSame("the row is not showing the java icon",
                SvgDocument.of(FileIconTheme.toResourcePath(
                        FileIconTheme.withVariant("crystalgui:filetypes/java"))),
                ((CgUiSvg) overlay).getDocument());
    }

    /** A folder gets the folder icon, whatever its name looks like. */
    @Test
    public void aFolderRowGetsTheFolderIcon() {
        assertEquals("filetype-folder", filetypeClassOf(iconOf(rowNamed("src"))));
    }

    /**
     * <b>Every visible row shows an icon — not most of them.</b>
     *
     * <p>Reported from the harness as a tree where a contiguous middle band had icons and the rows above
     * and below did not, which is the shape a per-template defect makes rather than a per-file one.</p>
     */
    @Test
    public void everyVisibleRowGetsAnIcon() {
        List<String> missing = new ArrayList<>();
        List<UIElement> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            CgUiDrawable overlay = iconOf(rows.get(i)).getStyle().getGeneralGroup()
                    .getValueSave(StylePropertyRegistry.OVERLAY);
            if (!(overlay instanceof CgUiSvg)) {
                missing.add(tree.treeView().visibleRows().get(i).item().name() + "=" + overlay);
            }
        }
        assertTrue("rows with no icon: " + missing, missing.isEmpty());

        // AND a box to draw it in. An overlay on a zero-sized element paints nothing, which is
        // indistinguishable from a missing overlay on screen and distinguishable here.
        List<String> unsized = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            UIElement icon = iconOf(rows.get(i));
            float w = icon.getRuntimeCache().getWidth();
            float h = icon.getRuntimeCache().getHeight();
            if (w < 1f || h < 1f) {
                unsized.add(tree.treeView().visibleRows().get(i).item().name() + "=" + w + "x" + h);
            }
        }
        assertTrue("icon slots with no box: " + unsized, unsized.isEmpty());
    }

    /**
     * <b>Rows are still indented by depth.</b>
     *
     * <p>The indent is {@code padding-left}, written by {@code TreeView} at DEFAULT origin. Anything the
     * user-agent sheet says about this row's padding outranks it — so a {@code padding-right} that the
     * parser expands to all four edges would flatten the whole tree, and a flat tree is exactly what the
     * screenshot that prompted this test showed.</p>
     */
    @Test
    public void rowsAreStillIndentedByDepth() {
        // Measured on the ICON's laid-out x, not on the padding value: that is the number a reader of the
        // screen actually sees, and it stays true whichever mechanism produces the indent.
        float root = iconOf(rows().get(0)).getRuntimeCache().getX();
        float shallow = iconOf(rowNamed("src")).getRuntimeCache().getX();
        float deep = iconOf(rowNamed("Main.java")).getRuntimeCache().getX();
        assertTrue("a depth-2 row (" + deep + ") is not indented past a depth-1 row (" + shallow + ")",
                deep > shallow);
        assertTrue("a depth-1 row is not indented past the root", shallow > root);
    }

    /**
     * <b>A recycled row must not accumulate the classes of every file it has ever shown.</b>
     *
     * <p>The template is a <em>different</em> row every time the view reuses it. Adding the new file-type
     * class without removing the old one leaves both on the element, and the cascade then resolves
     * whichever rule happens to win — which reads as a random colour rather than as a stale class, because
     * nothing about the row looks wrong.</p>
     *
     * <p>Re-sorting is what forces the rebind: with folders first, index 1 is {@code Apple.md}; interleaved,
     * it is {@code src}. Same template, different item.</p>
     */
    @Test
    public void recyclingDoesNotAccumulateFiletypeClasses() {
        // Index 0 is the project root. Folders first puts `src` at 1; interleaved puts `Apple.md` there.
        assertEquals("fixture assumption: folders first", "filetype-folder",
                filetypeClassOf(iconOf(rows().get(1))));

        tree.source().setSortOrder(WorkspaceTreeSource.SortOrder.MIXED);
        tree.treeView().refresh();
        settle();

        // filetypeClassOf asserts EXACTLY one, so this fails loudly if `filetype-folder` survived.
        assertEquals("filetype-md", filetypeClassOf(iconOf(rows().get(1))));
    }

    /**
     * <b>A decoration reaches the row, and clears when its provider goes.</b>
     *
     * <p>The clearing half is the one that breaks silently: a decoration written on bind and never removed
     * stays on whatever row the template lands on next, so a single modified file eventually paints half
     * the tree.</p>
     */
    @Test
    public void aDecorationLandsOnTheRowAndClears() {
        CgPath main = CgPath.of("mymod.proj", "src/Main.java");
        FileDecorationProvider provider = new FileDecorationProvider() {
            @Override public String label() {
                return "test";
            }
            @Override public FileDecoration decorationFor(CgPath path) {
                return main.equals(path)
                        ? FileDecoration.of(FileDecoration.WEIGHT_MODIFIED, "decoration-modified", "M", "Modified")
                        : null;
            }
        };
        tree.getDecorations().addProvider(provider);
        tree.treeView().setExpanded(CgPath.of("mymod.proj", "src"), true);
        settle();

        UIElement row = rowNamed("Main.java");
        assertTrue("the decoration class did not reach the row",
                row.hasClass("decoration-modified"));

        tree.getDecorations().removeProvider(provider);
        tree.treeView().refresh();
        settle();

        assertFalse("the decoration outlived its provider",
                rowNamed("Main.java").hasClass("decoration-modified"));
    }
}
