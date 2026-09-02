package com.crystalgui.ui;

import com.crystalgui.support.OldEngineSessions;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPlacement;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.graph.shader.ShaderGraphContribution;

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
        ServerUiSession<UIElement, Object> server = OldEngineSessions.serve(1, new UIElement(), pair[0]);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(OldEngineSessions.view(pair[1]), PlainOps.INSTANCE);
    }

    private static DockLeaf leafOf(CrystalEditor editor, String typeId) {
        return editor.workbench().dock().layout().leafContaining(new DockPanelRef(typeId));
    }

    // ── The Inspector fills itself, with nothing clicked ────────────────────

    /**
     * <b>The Inspector is a real element from construction, never a placeholder.</b>
     *
     * <p>The regression this exists for: the panel factory returned an empty box while the inspector did
     * not exist yet, and {@code DockGroup} caches a factory's result <b>permanently</b> — so that box
     * became the panel for the rest of the session. The real inspector, built a moment later, never
     * reached the tree, and the panel was blank until something forced the group to be rebuilt. Reported
     * as "the inspector opens blank, I need to click something first".</p>
     *
     * <p>Asserted as identity against {@code inspector()}, because a placeholder is also an element that
     * lays out perfectly well — which is precisely why the bug was invisible to every other test.</p>
     */
    @Test
    public void theInspectorPanelIsTheInspectorItself() {
        CrystalEditor editor = new CrystalEditor(client());
        UIElement content = editor.workbench().panels()
                .create(new DockPanelRef(CrystalEditor.INSPECTOR_TYPE));
        assertSame("the dock was handed something other than the inspector",
                editor.inspector(), content);
    }

    // The startup follow -- "the inspector fills itself with nothing clicked" -- is deliberately NOT
    // unit-tested here, and that is a statement about the fixture rather than about the behaviour.
    //
    // It needs the dock to have built its groups (the active FILE is derived from the active tab) and the
    // workspace read to have landed over the transport. This class has neither, and a version of the test
    // that stood up both got as far as openPaths=[] -- the read never completed -- which would have made
    // it assert something about the harness pump rather than about the inspector.
    //
    // Verified in the running harness instead. What IS pinned here is the cause of the reported bug: the
    // panel content being the inspector itself rather than a placeholder the dock would cache forever.

    /** The tab says what the file is. A generated shader still reads best as a file name. */
    @Test
    public void theSourceTabIsNamedForTheFileItIs() {
        CrystalEditor editor = new CrystalEditor(client());
        assertEquals("compiled_graph.shader", editor.workbench().panels()
                .titleOf(new DockPanelRef(ShaderGraphContribution.SOURCE_TYPE)));
    }

    // ── The generated shader is a DOCUMENT, one per graph ────────────────────

    /**
     * <b>Five graphs, five generated-shader tabs — not one shared panel.</b>
     *
     * <p>This is the whole reason it stopped being a singleton. A shared panel showing whichever graph is
     * in front cannot be left open beside a second graph, cannot be compared with one, and loses its
     * scroll position on every tab change. Unity's Shader Graph opens "View Generated Shader" per graph
     * for exactly this reason.</p>
     *
     * <p>Distinctness comes from the ref carrying the graph's own path, and {@code DockPanelRef} equality
     * being over type <em>and</em> state — so the same call for the same graph finds its tab instead of
     * opening a second.</p>
     */
    @Test
    public void everyGraphGetsItsOwnGeneratedShaderTab() {
        CrystalEditor editor = new CrystalEditor(client());
        CgPath one = CgPath.parse("mymod.proj:one.shadergraph");
        CgPath two = CgPath.parse("mymod.proj:two.shadergraph");
        ShaderGraphEditor first = (ShaderGraphEditor) editor.workbench().documentFor(one);
        ShaderGraphEditor second = (ShaderGraphEditor) editor.workbench().documentFor(two);

        // Each graph opened as its own tab first, so its generated source has a strip to join.
        editor.workbench().open(DockInput.of(editor.workbench().refFor(one)));
        editor.workbench().open(DockInput.of(editor.workbench().refFor(two)));

        assertTrue("the first graph's generated source did not open", ShaderGraphContribution.showGenerated(editor.workbench(), first));
        assertTrue("the second graph's generated source did not open", ShaderGraphContribution.showGenerated(editor.workbench(), second));

        DockPanelRef refOne = compiledRef("mymod.proj:one.shadergraph");
        DockPanelRef refTwo = compiledRef("mymod.proj:two.shadergraph");
        assertNotNull("no tab for the first graph's source", leafOfRef(editor, refOne));
        assertNotNull("no tab for the second graph's source", leafOfRef(editor, refTwo));
        // TWO TABS, not two panes. They now open in their graph's own strip, so when both graphs share a
        // strip so do their sources -- and asserting different LEAVES would be asserting the old
        // beside-the-graph placement rather than distinctness. What matters is that there are two of them.
        assertNotEquals("both graphs resolved to one generated-source panel", refOne, refTwo);
        assertEquals("a generated source did not join its own graph's strip",
                leafOfRef(editor, editor.workbench().refFor(one)), leafOfRef(editor, refOne));
        assertEquals(leafOfRef(editor, editor.workbench().refFor(two)), leafOfRef(editor, refTwo));
    }

    /** Asking twice finds the tab rather than opening a second onto the same graph. */
    @Test
    public void askingTwiceDoesNotOpenASecondTab() {
        CrystalEditor editor = new CrystalEditor(client());
        ShaderGraphEditor graph = (ShaderGraphEditor) editor.workbench()
                .documentFor(CgPath.parse("mymod.proj:one.shadergraph"));
        ShaderGraphContribution.showGenerated(editor.workbench(), graph);
        int leaves = editor.workbench().dock().layout().leaves().size();

        ShaderGraphContribution.showGenerated(editor.workbench(), graph);
        assertEquals("a second request split the work area again",
                leaves, editor.workbench().dock().layout().leaves().size());
    }

    /** The tab IS that graph's own source editor — not a copy of its text, and not another graph's. */
    @Test
    public void theTabShowsItsOwnGraphsSourceEditor() {
        CrystalEditor editor = new CrystalEditor(client());
        ShaderGraphEditor first = (ShaderGraphEditor) editor.workbench()
                .documentFor(CgPath.parse("mymod.proj:one.shadergraph"));
        ShaderGraphEditor second = (ShaderGraphEditor) editor.workbench()
                .documentFor(CgPath.parse("mymod.proj:two.shadergraph"));

        assertSame("the tab is not the graph's own source editor", first.source(),
                editor.workbench().panels().create(compiledRef("mymod.proj:one.shadergraph")));
        assertSame(second.source(),
                editor.workbench().panels().create(compiledRef("mymod.proj:two.shadergraph")));
    }

    /**
     * Named for its graph, so two of them are told apart on the strip.
     *
     * <p>The title is a rule over the <b>derived resource</b> now, not over a raw path string — and it
     * reads the origin's name, which {@code Resource.name()} already answers with for a derived
     * resource. That is the label half of what VS Code calls {@code ILabelService}.</p>
     */
    @Test
    public void aGeneratedTabIsNamedForItsGraph() {
        assertEquals("fire_compiled.shader", ShaderGraphContribution.titleFor(
                generatedFor("mymod.proj:shaders/fire.shadergraph")));
        assertEquals("noext_compiled.shader", ShaderGraphContribution.titleFor(
                generatedFor("mymod.proj:noext")));
    }

    /**
     * <b>A session saved before this tab's input became a derived resource still resolves.</b>
     *
     * <p>That state used to be the graph's bare path. It parses as a project resource with no origin, and
     * reading it as the origin itself is one line — against invalidating every saved layout that had the
     * tab open, which a version bump would have meant. The forms are unambiguous: a derived resource
     * always has an origin, a bare path never does.</p>
     */
    @Test
    public void anOldStyleGeneratedTabStateStillNamesItsGraph() {
        Resource legacy = Resource.parse("mymod.proj:shaders/fire.shadergraph");
        assertNull("a bare path must not look derived", legacy.origin());
        assertTrue(legacy.isProject());

        Resource current = generatedFor("mymod.proj:shaders/fire.shadergraph");
        assertEquals("both forms name the same graph", legacy, current.origin());
    }

    private static Resource generatedFor(String graphPath) {
        return Resource.derived(ShaderGraphContribution.SOURCE_SCHEME, Resource.parse(graphPath));
    }

    /**
     * <b>It does not open with the editor.</b>
     *
     * <p>It is a document derived from a graph, and at startup no graph is open — so a tab for one would
     * be a tab for nothing. The Inspector, which is a tool window and global, does open.</p>
     */
    @Test
    public void theGeneratedSourceIsNotInTheDefaultLayout() {
        CrystalEditor editor = new CrystalEditor(client());
        assertNull("a generated-source tab opened with no graph to generate from",
                leafOf(editor, ShaderGraphContribution.SOURCE_TYPE));
        // The inspector is a TOOL WINDOW now, so it is in the auxiliary region rather than a dock leaf --
        // it used to be opened into the tree with a SPLIT_RIGHT placement, which made it a leaf the layout
        // could lose. Same assertion, asked of the place it actually lives.
        assertEquals("the inspector did not open", CrystalEditor.INSPECTOR_TYPE,
                editor.workbench().regions()
                        .host(editor.workbench().toolWindowManager()
                                .regionOf(CrystalEditor.INSPECTOR_TYPE)).showing());
    }

    private static DockPanelRef compiledRef(String path) {
        Resource generated = generatedFor(path);
        return new DockPanelRef(ShaderGraphContribution.SOURCE_TYPE)
                .withState(Workbench.PATH_STATE, generated.toString())
                .withState(DockPanelRef.TITLE, ShaderGraphContribution.titleFor(generated));
    }

    private static DockLeaf leafOfRef(CrystalEditor editor, DockPanelRef ref) {
        return editor.workbench().dock().layout().leafContaining(ref);
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

        assertEquals(ShaderGraphContribution.GRAPH_TYPE,
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
     * <b>A graph file round-trips through the document seam.</b>
     *
     * <p>Encode one editor's graph, adopt it into another, and the second must encode identically. That is
     * the whole contract the workbench relies on: {@code encode} is compared against the bytes last read
     * to decide whether a file is modified, so a graph that re-encoded differently after loading would
     * report itself dirty the instant it opened and never stop.</p>
     *
     * <p>Round-tripped through the editors rather than against hand-written JSON deliberately — authoring
     * the serial form here would pin this test to the codec's current spelling rather than to the property
     * that matters.</p>
     */
    @Test
    public void aGraphFileRoundTripsThroughTheDocumentSeam() {
        CrystalEditor source = new CrystalEditor(client());
        FileDocument seed = source.workbench()
                .documentFor(CgPath.parse("mymod.proj:seed.shadergraph"));
        // A blank file opens WITH the starter graph, so this is a populated one -- see
        // ShaderGraphEditor.adopt. Asserted below rather than assumed.
        seed.adopt(new byte[0]);
        byte[] encoded = seed.encode();
        assertTrue("the starter graph encoded to nothing -- this would round-trip vacuously",
                encoded.length > 2);

        CrystalEditor target = new CrystalEditor(client());
        FileDocument document = target.workbench()
                .documentFor(CgPath.parse("mymod.proj:fancy.shadergraph"));
        document.adopt(encoded);

        assertArrayEquals("a loaded graph does not re-encode to what it was given, so it would report "
                + "itself modified the moment it opened", encoded, document.encode());
    }

    /**
     * <b>A malformed graph file is refused, not silently emptied.</b>
     *
     * <p>The safety property the workbench leans on: a document that accepted bytes it could not read
     * would show an empty canvas, differ from the file, report itself modified, and let the first Save All
     * write that emptiness over the user's graph.</p>
     */
    @Test
    public void aMalformedGraphFileIsRefused() {
        CrystalEditor editor = new CrystalEditor(client());
        FileDocument document = editor.workbench()
                .documentFor(CgPath.parse("mymod.proj:broken.shadergraph"));

        try {
            document.adopt("not json at all".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.junit.Assert.fail("expected a malformed graph file to be refused");
        } catch (RuntimeException expected) {
            // The message is the codec's or the parser's; what matters is that it did not quietly succeed.
        }
    }
}
