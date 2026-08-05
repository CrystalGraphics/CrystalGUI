package com.crystalgui.ui;

import com.crystalgui.editor.CrystalEditor;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.graph.shader.ShaderGraphEditor;
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
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * Which panels the editor ships, and where they land.
 *
 * <h3>Asserted against the LAYOUT, not the built widgets</h3>
 *
 * <p>{@code DockLayout} is pure data and needs no window, which is what makes this testable at all: the
 * built tree only exists after a frame, and a frame here attaches the shader graph's previews — which
 * allocate an FBO per node and therefore want a GL context. Reaching for {@code updateWithoutPainting}
 * would test the dock by way of the one thing in the tree that cannot run headlessly.</p>
 */
public class CrystalEditorPanelsTest extends UiTestBase {

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> server = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(new ClientUiSession<>(pair[1], PlainOps.INSTANCE), PlainOps.INSTANCE);
    }

    private static DockLeaf leafOf(CrystalEditor editor, String typeId) {
        return editor.workbench().dock().layout().leafContaining(new DockPanelRef(typeId));
    }

    /**
     * <b>The emitted source opens beside the canvas, not on top of it.</b>
     *
     * <p>The two shared an internal {@code SplitView} and are now two dock panels, and the risk of that
     * move is landing the source in the graph's own strip — where it is a tab, so exactly one of the two is
     * ever visible. Watching the GLSL change as you wire is the entire reason it is on screen, so a panel
     * you must switch away from the graph to read is a panel that is never read.</p>
     *
     * <p>Two different leaves is the observable form of "both at once", and it is what the tab-vs-pane
     * mistake would break while every other assertion here still passed.</p>
     */
    @Test
    public void theEmittedSourceGetsAPaneOfItsOwn() {
        CrystalEditor editor = new CrystalEditor(client());

        DockLeaf graph = leafOf(editor, CrystalEditor.SHADER_GRAPH_TYPE);
        DockLeaf source = leafOf(editor, CrystalEditor.SHADER_SOURCE_TYPE);
        assertNotNull("the shader graph did not open", graph);
        assertNotNull("the emitted source did not open", source);
        assertNotSame("the source is a TAB beside the graph, so only one of them is ever visible",
                graph, source);
        editor.workbench().dock().layout().checkInvariants();
    }

    /** The tab says what the file is. A generated shader still reads best as a file name. */
    @Test
    public void theSourceTabIsNamedForTheFileItIs() {
        CrystalEditor editor = new CrystalEditor(client());
        assertEquals("compiled_graph.shader", editor.workbench().panels()
                .titleOf(new DockPanelRef(CrystalEditor.SHADER_SOURCE_TYPE)));
    }

    /**
     * The panel IS the graph's source editor, not a copy of its text.
     *
     * <p>{@code DockArea} asks the registry for content on every rebuild, so a factory returning anything
     * freshly built would hand back an empty editor after each split, drag or close — and the recompiles
     * would go on landing in an editor nobody is looking at.</p>
     */
    @Test
    public void theSourcePanelIsTheGraphsOwnEditor() {
        CrystalEditor editor = new CrystalEditor(client());
        UIElement built = editor.workbench().panels()
                .create(new DockPanelRef(CrystalEditor.SHADER_SOURCE_TYPE));
        assertSame(editor.shaderGraph().source(), built);
        assertSame("a second build must not produce a second editor",
                built, editor.workbench().panels()
                        .create(new DockPanelRef(CrystalEditor.SHADER_SOURCE_TYPE)));
    }

    /**
     * <b>The inspector shares the source's strip rather than taking a pane.</b>
     *
     * <p>Same leaf, because reading the generated GLSL and adjusting a node's properties are alternatives
     * — a third column would spend the work area on whichever you are not looking at. The graph, by
     * contrast, must stay visible alongside both, so it keeps its own pane.</p>
     */
    @Test
    public void theInspectorIsATabBesideTheEmittedSourceNotAThirdPane() {
        CrystalEditor editor = new CrystalEditor(client());

        DockLeaf source = leafOf(editor, CrystalEditor.SHADER_SOURCE_TYPE);
        DockLeaf inspector = leafOf(editor, CrystalEditor.INSPECTOR_TYPE);
        assertNotNull("the inspector did not open at all", inspector);
        assertSame("the inspector took a pane of its own instead of joining the source's strip",
                source, inspector);
        assertNotSame("the inspector landed in the graph's pane",
                leafOf(editor, CrystalEditor.SHADER_GRAPH_TYPE), inspector);
        editor.workbench().dock().layout().checkInvariants();
    }

    /**
     * It opens <em>behind</em> the source rather than on top of it.
     *
     * <p>A panel that steals its sibling's tab on open is a panel that opens by hiding the thing you were
     * looking at — and the emitted source is what the pane was split off for.</p>
     */
    @Test
    public void theInspectorDoesNotStealTheActiveTabOnOpen() {
        CrystalEditor editor = new CrystalEditor(client());
        DockLeaf leaf = leafOf(editor, CrystalEditor.SHADER_SOURCE_TYPE);
        assertEquals(CrystalEditor.SHADER_SOURCE_TYPE, leaf.activePanel().typeId());
    }

    /**
     * Opening an already-open panel reveals it where it is rather than splitting again.
     *
     * <p>Without this a menu item wired to "show the compiled source" adds a pane every time it is chosen,
     * and each one is a legitimate leaf so nothing complains — the work area just gets narrower.</p>
     */
    @Test
    public void openingItAgainRevealsItRatherThanSplittingAgain() {
        CrystalEditor editor = new CrystalEditor(client());
        int before = editor.workbench().dock().layout().leaves().size();

        editor.workbench().openPanelBeside(new DockPanelRef(CrystalEditor.SHADER_SOURCE_TYPE),
                DockDropZone.SPLIT_RIGHT, 0.28f);

        assertEquals("a second open split the work area again",
                before, editor.workbench().dock().layout().leaves().size());
        editor.workbench().dock().layout().checkInvariants();
    }

    // ── .shadergraph opens as a graph (E24b + the FileDocument seam) ─────────

    /**
     * <b>A {@code .shadergraph} file opens in the graph editor, not in a text editor.</b>
     *
     * <p>Every file used to resolve to {@code FILE_TYPE}, so opening one showed its JSON. The binding is
     * the editor association; the document seam is what then lets it be saved, since save was
     * {@code editor.getText()} against a map of text editors.</p>
     */
    @Test
    public void aShaderGraphFileOpensInTheGraphEditor() {
        CrystalEditor editor = new CrystalEditor(client());

        assertEquals(CrystalEditor.SHADER_GRAPH_FILE_TYPE,
                editor.workbench().refFor(CgPath.parse("mymod.proj:fancy.shadergraph")).typeId());
        assertEquals("an ordinary file must still open in the text editor",
                Workbench.FILE_TYPE,
                editor.workbench().refFor(CgPath.parse("mymod.proj:README.md")).typeId());
    }

    /** And it is a real document: one instance per path, and it is a {@link FileDocument}. */
    @Test
    public void eachGraphFileGetsItsOwnEditor() {
        CrystalEditor editor = new CrystalEditor(client());
        CgPath one = CgPath.parse("mymod.proj:one.shadergraph");
        CgPath two = CgPath.parse("mymod.proj:two.shadergraph");

        FileDocument first = editor.workbench().documentFor(one);
        FileDocument second = editor.workbench().documentFor(two);

        assertTrue("the graph editor is not a FileDocument", first instanceof ShaderGraphEditor);
        assertNotSame("two graph files share one editor -- editing either would edit both", first, second);
        assertSame("asking twice for the same file must give the same document",
                first, editor.workbench().documentFor(one));
    }

    /**
     * <b>A graph file that cannot be loaded is refused, not silently emptied.</b>
     *
     * <p>{@code GraphView} cannot yet adopt a whole document, so {@code adopt} throws — and that refusal
     * is the safety property. A document that accepted the bytes and showed an empty canvas would then
     * differ from the file it failed to read, report itself modified, and let the first Save All write
     * that emptiness over the user's graph.</p>
     */
    @Test
    public void aGraphFileThatCannotLoadIsRefusedRatherThanEmptied() {
        CrystalEditor editor = new CrystalEditor(client());
        CgPath path = CgPath.parse("mymod.proj:fancy.shadergraph");
        FileDocument document = editor.workbench().documentFor(path);

        try {
            document.adopt("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.junit.Assert.fail("expected the graph to refuse a load it cannot perform");
        } catch (RuntimeException expected) {
            assertTrue(String.valueOf(expected.getMessage()),
                    String.valueOf(expected.getMessage()).contains("whole-document adopt"));
        }
    }
}
