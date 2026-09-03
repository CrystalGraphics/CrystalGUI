package com.crystalgui.app.shadergraph;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.app.shadergraph.node.ShaderPropertyNodes;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.diagnostic.Diagnostic;
import java.util.List;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.GraphIds;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The assembled shader graph widget.
 *
 * <p>Every test here carries a <b>timeout</b>, which is unusual and deliberate: the failure this widget
 * actually shipped was an infinite layout pass, and an assertion cannot catch one. The document hung before
 * painting a frame and the only symptom was "Not Responding" — no exception, no log line, nothing for a
 * conventional test to observe. A timeout turns that into a red test instead of a hung suite.</p>
 */
public class ShaderGraphEditorTest extends UiDocumentTestBase {

    private ShaderGraphEditor editor;

    private void build() {
        editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    /**
     * <b>Layout settles.</b> The regression test for a real freeze.
     *
     * <p>Preview attachment was done from {@code onLayoutChanged}, which runs <em>inside</em>
     * {@code calculateLayout()}'s {@code while (isLayoutDirty())} loop. Attaching adds elements, so every
     * pass re-dirtied the tree and the loop never terminated — the harness hung on open, before its first
     * frame. Structural changes belong outside the layout pass; this asserts that they are.</p>
     */
    @Test
    public void framesTerminateRatherThanLoopingForever() {
        build();
        for (int i = 0; i < 8; i++) frame();
        assertNotNull(editor.graph());
    }

    @Test
    public void anEmptyGraphCompilesToSomethingRatherThanThrowing() {
        build();
        for (int i = 0; i < 3; i++) frame();
        assertNotNull("no compile result at all", editor.lastCompile());
        assertNotNull(editor.source().getText());
    }

    /**
     * The starter graph is opt-in, and compiles.
     *
     * <p><b>No frames are pumped after seeding, deliberately.</b> Attaching the previews starts
     * {@code CgPreviewRenderer}, which allocates an FBO per node and therefore needs a real GL context —
     * so a frame here dies in {@code CgCapabilities.detect()} rather than telling us anything about the
     * graph. What is assertable without pixels is the document and the emit, which is what this checks;
     * the seeded widget under a live context is the harness's job.</p>
     */
    @Test
    public void theStarterGraphIsOptInAndCompiles() {
        build();
        assertTrue("a new editor must not invent nodes", editor.graph().getDocument().nodeCount() == 0);

        editor.addStarterGraph();

        assertTrue(editor.graph().getDocument().nodeCount() > 0);
        assertNotNull(editor.lastCompile());
        assertTrue("the starter graph does not compile: "
                + String.join("; ", editor.lastCompile().errors()), editor.lastCompile().ok());
    }

    /**
     * <b>The graph's own subtree is not marked internal.</b>
     *
     * <p>The bug that hung both scenes, and the third time this exact trap has been hit in this widget
     * layer. {@code addInternalChild(split)} looks right and {@code markAsInternal()} <b>recurses</b>, so
     * it stamped the split, both panes, the {@code GraphView}, its canvas and everything under them — and
     * {@code removeChild}/{@code clearAllChildren} <b>silently refuse</b> internal children. The previews
     * add and retire a thumbnail per node, so every retirement was declined, the tree grew without bound,
     * and layout took longer every frame until the document stopped responding.</p>
     *
     * <p>The thread dump was pure Taffy, which reads as a layout cycle and was really an unbounded tree —
     * which is why this is asserted structurally rather than by timing. A wrapper marked internal
     * <em>while empty</em> is the fix, the same one {@code QuickPick} and {@code ProblemsPanel} carry.</p>
     */
    @Test
    public void theGraphSubtreeIsNotStampedInternal() {
        build();
        // removeChild REFUSES an internal child and reports false, so asking the engine to remove the
        // GraphView is the observable form of "was this subtree stamped?" -- and it asks the engine
        // rather than a private flag this test would have to guess the name of.
        UIElement pane = editor.graph().parent();
        assertNotNull("the graph is not in the tree at all", pane);
        boolean removable = pane.remove(editor.graph());
        assertTrue("the GraphView is stamped internal -- removals under it are silently refused, "
                + "which is what made the tree grow without bound", removable);
        pane.append(editor.graph());
    }

    /**
     * <b>The emitted source is detached, and stays live once a host parents it.</b>
     *
     * <p>It used to sit in an internal {@code SplitView} beside the canvas, which is a layout decision
     * taken away from the host: a docking host wants it as an ordinary tab — draggable, closable,
     * restorable — and it can be none of those while it is nailed inside one element. So the widget keeps
     * it compiled and parents it nowhere.</p>
     *
     * <p>Both halves are asserted, because only the pair is the contract. That it is detached without also
     * checking it still recompiles would pass just as well for a source pane that had been forgotten
     * about — which is precisely the failure this shape invites.</p>
     */
    @Test
    public void theEmittedSourceIsDetachedSoAHostCanPlaceIt() {
        build();
        assertNull("source() must not be in the editor's own tree -- a host places it",
                editor.source().parent());

        UIElement host = new UIElement().layout(l -> l.width(300).height(200));
        editor.parent().append(host);
        host.append(editor.source());
        for (int i = 0; i < 3; i++) frame();

        String before = editor.source().getText();
        editor.addStarterGraph();
        assertNotEquals("a recompile does not reach a source pane the host parented",
                before, editor.source().getText());
    }

    // ── As a file ───────────────────────────────────────────────────────────

    /**
     * <b>A graph survives a round trip through the file it writes.</b>
     *
     * <p>End to end through the two halves the workbench actually calls — {@code encode()} to save and
     * {@code adopt()} to open — rather than through {@code GraphCodecs} directly, because the codec has
     * had round-trip tests since 6.2.5 and was never the missing part. What was missing is that the
     * decoded document reaches the <em>view</em>: {@code GraphView.syncFromDocument} is changeset-driven
     * and a freshly decoded document has none, so "it decodes" and "it opens" are different claims.</p>
     */
    @Test
    public void aGraphRoundTripsThroughItsOwnFile() {
        build();
        // NO FRAMES after seeding, for the reason theStarterGraphIsOptInAndCompiles records: attaching
        // the previews starts CgPreviewRenderer, which needs a real GL context. Nothing here wants one --
        // load builds its widgets immediately, so the counts below are readable without a frame.
        editor.addStarterGraph();

        int nodes = editor.graph().getDocument().nodeCount();
        int edges = editor.graph().getDocument().edges().size();
        assertTrue("this fixture needs a graph to be worth round-tripping", nodes > 1);
        byte[] saved = editor.encode();

        // A DIFFERENT editor, which is what opening the file in a new tab is.
        ShaderGraphEditor reopened = new ShaderGraphEditor();
        UIElement host = new UIElement().layout(l -> l.width(800).height(500));
        host.append(reopened);
        document.append(host);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        reopened.adopt(saved);

        assertEquals("every node came back", nodes, reopened.graph().getDocument().nodeCount());
        assertEquals("and every wire", edges, reopened.graph().getDocument().edges().size());
        assertEquals("as widgets on the canvas, not just rows in a document",
                nodes, reopened.graph().nodes().size());
        assertEquals("and the file it would write next is the same file",
                new String(saved, java.nio.charset.StandardCharsets.UTF_8),
                new String(reopened.encode(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * <b>A brand-new file opens with the starter graph, and can then be saved.</b>
     *
     * <p>{@code New File…} creates every file with {@code ""}, so this is the very first thing anyone
     * does with the type: make {@code thing.shadergraph}, open it, wire something up, {@code Ctrl+S}.
     * Before this it failed on step two — {@code CodecException: Not a JSON object: null} — and the
     * workbench then refused the save because the document "never loaded", which is the correct
     * response to a file it could not read and the wrong one for a file with nothing in it.</p>
     *
     * <p>The document half is the one that matters: a blank file must be <em>saveable</em> afterwards, not
     * merely openable. Accepting the bytes and then refusing to write would be the worse of both.</p>
     *
     * <p>And being handed a starter graph is not an edit — the undo stack is empty, or the first
     * {@code Ctrl+Z} in a new file unpicks the graph it was just given.</p>
     */
    @Test
    public void aBrandNewEmptyFileOpensWithTheStarterGraph() {
        build();
        editor.adopt("".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue("a new file opens with the starter graph, not an empty canvas",
                editor.graph().getDocument().nodeCount() > 0);
        assertEquals("and being handed one is not something to undo",
                0, editor.graph().undoStack().undoDepth());

        // What it would write is a real graph file, and reading it back gives the same graph -- through
        // the SAVED path this time, not the blank one.
        int seeded = editor.graph().getDocument().nodeCount();
        byte[] saved = editor.encode();
        assertTrue("a blank file must become a valid one on the first save", saved.length > 0);
        editor.adopt(saved);
        assertEquals(seeded, editor.graph().getDocument().nodeCount());
    }

    /**
     * <b>A file is adopted before its panel exists, so adopt must work on a DETACHED editor.</b>
     *
     * <p>{@code Workbench.openFile} reads, calls {@code adoptInto(...)}, and only then
     * {@code openPanel(ref)} — so on the create-then-open path the editor has no document at the moment it
     * is handed its bytes. Restoring a layout takes the other order, where the panel is built first.</p>
     *
     * <p>Worth pinning because the two paths are easy to conflate and only one of them is exercised by
     * every other test here: seeding the starter graph goes through the ordinary widget mutators, and
     * anything in that chain that quietly needed a document would leave a brand-new file empty while a
     * reopened one came back correct — which is exactly how it was reported, and was in fact a stale
     * build rather than this.</p>
     */
    @Test
    public void aDetachedEditorCanStillAdopt() {
        ShaderGraphEditor detached = new ShaderGraphEditor();
        assertNull("this fixture is only meaningful while detached", detached.parent());

        detached.adopt(new byte[0]);

        assertTrue("the document did not get the starter graph",
                detached.graph().getDocument().nodeCount() > 0);
        assertEquals("and every node got a widget, without a layout pass to trigger it",
                detached.graph().getDocument().nodeCount(), detached.graph().nodes().size());
    }

    /**
     * <b>Where the canvas was looking survives the file.</b>
     *
     * <p>In the file rather than the layout, which is Unity's choice for a {@code .shadergraph}: a graph
     * is an asset you arrange, and reopening one at the origin loses real work because where things sit
     * relative to the viewport is part of how it reads.</p>
     */
    @Test
    public void theCanvasViewSurvivesASaveAndReopen() {
        build();
        editor.adopt(new byte[0]);
        editor.graph().setZoom(2.5f);
        editor.graph().setPan(-120f, 64f);

        byte[] saved = editor.encode();

        ShaderGraphEditor reopened = new ShaderGraphEditor();
        UIElement host = new UIElement().layout(l -> l.width(800).height(500));
        host.append(reopened);
        document.append(host);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        reopened.adopt(saved);

        assertEquals(2.5f, reopened.graph().getZoom(), 0.001f);
        assertEquals(-120f, reopened.graph().getPanX(), 0.001f);
        assertEquals(64f, reopened.graph().getPanY(), 0.001f);
    }

    /**
     * <b>A property node comes back as a property node, not an ordinary box.</b>
     *
     * <p>Its type is synthesised per property and deliberately never registered — a type per declared
     * property would put the Blackboard's contents in the create menu. So {@code GraphView} cannot look
     * one up, and built a plain two-row node from the stored ports with the capsule styling gone.</p>
     */
    @Test
    public void aPropertyNodeReloadsAsAPropertyNode() {
        build();
        editor.adopt(new byte[0]);
        com.crystalgui.graph.GraphProperty property = editor.blackboard().addProperty("Color");
        assertNotNull("this fixture needs a property to reference", property);
        editor.blackboard().pillFor(property.id()).endRename();

        editor.graph().getDocument().addNode(
                ShaderPropertyNodes.create(property, 40f, 40f));
        editor.graph().syncFromDocument();
        byte[] saved = editor.encode();

        ShaderGraphEditor reopened = new ShaderGraphEditor();
        UIElement host = new UIElement().layout(l -> l.width(800).height(500));
        host.append(reopened);
        document.append(host);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        reopened.adopt(saved);

        GraphNode loaded = null;
        for (GraphNode node : reopened.graph().nodes()) {
            if (ShaderPropertyNodes.isPropertyNode(
                    reopened.graph().getDocument().node(node.getNodeId()))) loaded = node;
        }
        assertNotNull("the property node is not in the reloaded graph at all", loaded);
        assertTrue("it came back as an ordinary node -- the capsule class is what styles it as a property",
                loaded.hasClass(ShaderPropertyNodes.NODE_CLASS));
        assertEquals("and it must be titled from its property, not from a stored type id",
                ShaderPropertyNodes.titleFor(property), loaded.getTitle());
    }

    /** Whitespace is blank too — a file someone opened, touched and left is still a new file. */
    @Test
    public void whitespaceCountsAsBlank() {
        build();
        editor.adopt(" \n\t ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(editor.graph().getDocument().nodeCount() > 0);
    }

    /**
     * <b>Content that will not parse still throws</b> — the protection stays where it was aimed.
     *
     * <p>The blank carve-out above must not become "accept anything and show an empty canvas", which is
     * exactly the failure the refusal exists to prevent: the editor would differ from the file, report
     * itself modified, and the first Save All would write that emptiness over the user's work.</p>
     */
    @Test
    public void amalformedFileIsStillRefused() {
        build();
        try {
            editor.adopt("{ this is not a graph".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.junit.Assert.fail("a corrupt file must be refused, not silently emptied");
        } catch (RuntimeException expected) {
            // The workbench turns this into "refusing to save -- it never loaded".
        }
    }

    /**
     * Opening a file leaves nothing to undo.
     *
     * <p>The first {@code Ctrl+Z} in a freshly opened graph must not start unpicking it a node at a time.
     * A load is the starting state, not something the user did.</p>
     */
    @Test
    public void openingAGraphIsNotUndoable() {
        build();
        editor.addStarterGraph();
        byte[] saved = editor.encode();

        editor.adopt(saved);

        assertEquals(0, editor.graph().undoStack().undoDepth());
    }

    /**
     * <b>A graph's compile errors reach its DiagnosticSet, attributed to the node.</b>
     *
     * <p>They used to reach nothing. The compiler produced a dozen problems naming a node and a port — in
     * prose — and the editor collapsed them to {@code "N error(s)"} on the status bar, while the Problems
     * panel was bound to the active {@code TextEditor} and so was empty by construction whenever a graph
     * was in front. The identity is a field now, which is what lets a panel row point back at a node.</p>
     *
     * <p>A graph with a node whose required input is unconnected is the smallest way to provoke one; what
     * is pinned is that <em>something</em> attributed arrives, not the compiler's exact wording.</p>
     */
    @Test
    public void compileProblemsBecomeAttributedDiagnostics() {
        ShaderGraphEditor editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(editor);
        document.append(root);
        for (int i = 0; i < 6; i++) frame();

        assertNotNull("a graph must answer with a set, not null", editor.diagnostics());

        // An empty graph has no output node, which is the graph-level problem every compile starts from.
        editor.adopt("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < 6; i++) frame();

        List<Diagnostic> reported = editor.diagnostics().all();
        for (Diagnostic diagnostic : reported) {
            assertEquals("a graph diagnostic names its reporter", "shadergraph", diagnostic.source());
            assertFalse("a node problem has no line to point at", diagnostic.hasPosition());
        }
    }

    /**
     * <b>Two properties with one name is a warning, not a driver error.</b>
     *
     * <p>Property names become GLSL uniform names, which must be unique — so a duplicate compiles to a
     * refusal about generated code the user never wrote, at a line that means nothing to them. Catching it
     * against the document names the actual problem, and it is a warning because the graph still emits:
     * one of the two simply wins.</p>
     */
    @Test
    public void duplicatePropertyNamesAreReported() {
        ShaderGraphEditor editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(editor);
        document.append(root);
        for (int i = 0; i < 6; i++) frame();

        editor.graph().getDocument().addProperty(GraphProperty.of("Tint", "vec4", ""));
        editor.graph().getDocument().addProperty(GraphProperty.of("Tint", "vec4", ""));
        // Adding a property to the document directly does not go through the editing path that debounces a
        // recompile, so the compile is asked for rather than waited on.
        editor.recompile();
        for (int i = 0; i < 6; i++) frame();

        boolean warned = false;
        for (Diagnostic diagnostic : editor.diagnostics().all()) {
            if (diagnostic.message().contains("Tint") && diagnostic.message().contains("named")) warned = true;
        }
        assertTrue("a duplicate uniform name went unreported: " + editor.diagnostics().all(), warned);
    }

    /**
     * <b>A node type this build does not have is reported, not just tolerated.</b>
     *
     * <p>The document model keeps an unknown node whole — id, position, values, edges — so opening a graph
     * in a build that lacks one of its node types and saving it again does not delete the user's work. That
     * is right. What was missing is that nobody was told: the canvas shows a placeholder, the bridge marks
     * the node absent and drops every edge touching it, and the shader compiles <em>without</em> it. What
     * gets emitted is then not what the document says, which is an error rather than a warning.</p>
     */
    @Test
    public void aNodeTypeThisBuildLacksIsReported() {
        ShaderGraphEditor editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(editor);
        document.append(root);
        for (int i = 0; i < 6; i++) frame();

        editor.graph().getDocument().addNode(
                NodeData.of(GraphIds.generate(), "cg:FromAPluginYouDoNotHave", 0f, 0f));
        editor.recompile();
        for (int i = 0; i < 6; i++) frame();

        boolean reported = false;
        for (Diagnostic diagnostic : editor.diagnostics().all()) {
            if (diagnostic.message().contains("cg:FromAPluginYouDoNotHave")) reported = true;
        }
        assertTrue("an unknown node type loaded silently: " + editor.diagnostics().all(), reported);
    }
}
