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
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPlacement;

/**
 * Opening a file, and acting on the tab it opened in.
 *
 * <p>Two defects, reported together and unrelated except in how they present: a file opens, the status
 * line says so, and the tab is <b>empty</b>; and {@code Ctrl+W} closes nothing unless you first click the
 * tab's own header.</p>
 *
 * <h3>The read is deliberately not exercised here</h3>
 *
 * <p>These drive the editor through {@link Workbench#editorFor} and {@link Workbench#openPanel} rather
 * than through {@link Workbench#openFile}, so no RPC round-trip is involved. Not a shortcut: in the
 * harness the read plainly <em>worked</em> — the status line read "opened README.md" — and the pane was
 * blank anyway. Routing these through the network would put a second, slower, flakier thing between the
 * assertion and the defect, and the defect is not in the network. {@code WorkspaceClientTest} covers the
 * transport end to end, headlessly, where that belongs.</p>
 */
public class WorkbenchFileTabTest extends UiTestBase {

    private static final String README = "# hello\nsecond line\nthird line\n";

    private UIWindow window;
    private Workbench workbench;

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", README)
                .seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> server =
                new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(new ClientUiSession<>(pair[1], PlainOps.INSTANCE), PlainOps.INSTANCE);
    }

    @Before
    public void setUp() {
        workbench = new Workbench(client());
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        // Nothing installs the dock's commands: the DockArea inside the workbench registers them, and
        // their chords are declared on the commands rather than bound onto this root.
        settle();
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /** A file tab, with content, and no network in the way. */
    private TextEditor openWithContent(CgPath path) {
        TextEditor editor = workbench.editorFor(path);
        editor.setText(README);
        workbench.open(DockInput.of(workbench.refFor(path)));
        settle();
        return editor;
    }

    /**
     * <b>The tab is not empty.</b>
     *
     * <p>A {@link TextEditor}'s lines are absolutely positioned inside its text viewport, so the widget
     * contributes <b>nothing</b> to its own intrinsic height — the same shape as {@code CanvasView} and its
     * transformed plane. Dropped into a dock pane with no rule sizing it, it resolved to zero and painted a
     * blank pane while holding the file perfectly well: the read succeeded, the status line said so, and
     * there was nothing on screen.</p>
     *
     * <p>Asserted on the laid-out box, never on {@code getText()} — the text was never what was missing,
     * and a test that checked it would have gone green over a blank pane.</p>
     */
    @Test
    public void anOpenedFileHasAVisibleEditor() {
        TextEditor editor = openWithContent(CgPath.parse("mymod.proj:README.md"));

        assertNotNull("the editor is not in the tree", editor.getParent());
        assertTrue("the editor laid out ZERO pixels tall -- the tab renders empty",
                editor.getRuntimeCache().getHeight() > 0f);
        assertTrue("the editor laid out ZERO pixels wide",
                editor.getRuntimeCache().getWidth() > 0f);
    }

    /**
     * <b>Focusing something inside a group makes that group the active one.</b>
     *
     * <p>Every dock command acts on {@code DockArea.activeGroup()}, and that was set only by the tab
     * strip's own selection listener — so focus could sit in an editor while the "active" group was
     * whichever <em>header</em> was last clicked. {@code Ctrl+W} then closed a panel in another pane, or
     * nothing at all, and the workaround looked like "click the tab first".</p>
     *
     * <p>The active group is pointed elsewhere first, so this cannot pass on whatever {@code openPanel}
     * happened to leave behind.</p>
     */
    @Test
    public void focusingAFileEditorMakesItsGroupActive() {
        CgPath path = CgPath.parse("mymod.proj:README.md");
        TextEditor editor = openWithContent(path);

        workbench.dock().setActiveGroup(workbench.dock().groupFor(
                workbench.dock().layout().leaves().get(0)));
        settle();

        window.getInputHandler().requestPointerFocus(editor);
        settle();

        assertNotNull(workbench.dock().activeGroup());
        assertEquals("focus is inside the editor but the active group is elsewhere -- "
                        + "every dock command acts on the wrong pane",
                path.toString(),
                workbench.dock().activeGroup().leaf().activePanel().state(Workbench.PATH_STATE, ""));
    }

    // ── Which editor opens which file (E24b) ────────────────────────────────

    /**
     * <b>A bound extension opens its own editor, not the text editor.</b>
     *
     * <p>{@code refFor} returned {@code FILE_TYPE} unconditionally, so every file opened in a text editor
     * however little sense that made — a PNG arrived as mojibake and a {@code .shadergraph} as raw JSON.
     * Binding is the mechanism VS Code calls an editor association and IntelliJ a {@code FileEditorProvider};
     * the text editor becomes the fallback rather than the rule.</p>
     */
    @Test
    public void aBoundExtensionOpensItsOwnEditor() {
        workbench.registerPanel(
                DockPanelDescriptor.document("image", "Image"),
                ref -> new UIElement());
        workbench.bindEditorExtensions("image", "png", "jpg");

        assertEquals("image", workbench.refFor(CgPath.parse("mymod.proj:logo.png")).typeId());
        assertEquals("image", workbench.refFor(CgPath.parse("mymod.proj:LOGO.PNG")).typeId());
        assertEquals("an unbound file must still open in the text editor",
                Workbench.FILE_TYPE, workbench.refFor(CgPath.parse("mymod.proj:README.md")).typeId());
    }

    /** A bound panel is handed the same path state, so its factory reads the file exactly as the editor does. */
    @Test
    public void aBoundPanelStillCarriesThePath() {
        workbench.registerPanel(
                DockPanelDescriptor.document("image", "Image"),
                ref -> new UIElement());
        workbench.bindEditorExtensions("image", "png");

        CgPath path = CgPath.parse("mymod.proj:art/logo.png");
        assertEquals(path.toString(), workbench.refFor(path).state(Workbench.PATH_STATE, ""));
        assertEquals("logo.png",
                workbench.refFor(path).state(DockPanelRef.TITLE, ""));
    }

    /**
     * An exact name beats an extension, so a file whose suffix lies about it can still be claimed.
     *
     * <p>The precedence is {@link com.crystalgui.fs.FilePatternMap}'s, shared with the language registry —
     * which is the point of sharing it. Two matchers would be two chances to disagree about which of these
     * two rules wins.</p>
     */
    @Test
    public void anExactNameBeatsABoundExtension() {
        workbench.registerPanel(
                DockPanelDescriptor.document("image", "Image"),
                ref -> new UIElement());
        workbench.registerPanel(
                DockPanelDescriptor.document("licence", "Licence"),
                ref -> new UIElement());
        workbench.bindEditorExtensions("image", "png");
        workbench.bindEditorNames("licence", "NOTICE.png");

        assertEquals("licence", workbench.refFor(CgPath.parse("mymod.proj:NOTICE.png")).typeId());
        assertEquals("image", workbench.refFor(CgPath.parse("mymod.proj:other.png")).typeId());
    }

    /**
     * <b>Renaming across a binding swaps the editor.</b>
     *
     * <p>Worth pinning because {@code refFor} is also the identity used to FIND an open tab again, for
     * closing and for renaming. Its answer changing with the extension is correct — {@code a.txt} renamed
     * to {@code a.png} should stop being a text editor — but it is only correct because the rename path
     * replaces one ref with the other rather than looking the old one up afterwards.</p>
     */
    @Test
    public void renamingAcrossABindingChangesTheEditorType() {
        workbench.registerPanel(
                DockPanelDescriptor.document("image", "Image"),
                ref -> new UIElement());
        workbench.bindEditorExtensions("image", "png");

        assertEquals(Workbench.FILE_TYPE, workbench.refFor(CgPath.parse("mymod.proj:a.txt")).typeId());
        assertEquals("image", workbench.refFor(CgPath.parse("mymod.proj:a.png")).typeId());
    }

    // ── The document seam and unsaved changes (E16) ─────────────────────────

    /**
     * <b>A non-text document is what save encodes.</b>
     *
     * <p>Save used to be {@code editor.getText()} against a {@code Map<CgPath, TextEditor>}, so anything
     * that was not text could be opened once bindings existed and then could not be saved at all.</p>
     *
     * <p>The dirty and save-refusal behaviour around this lives in {@code ExplorerCommandsTest}: both need
     * a completed read to have a baseline to compare against, and this fixture deliberately pumps no
     * transport — see the class note.</p>
     */
    @Test
    public void aBoundDocumentSuppliesTheBytesToSave() {
        StringBuilder encoded = new StringBuilder();
        workbench.registerDocumentType("fake", "Fake", path -> new FileDocument() {
            private final UIElement view = new UIElement();
            @Override public UIElement view() { return view; }
            @Override public byte[] encode() {
                encoded.append("encoded:").append(path.name());
                return new byte[0];
            }
            @Override public void adopt(byte[] bytes) { }
            @Override public Connection onDidChange(Runnable listener) { return () -> { }; }
            @Override public Resource resource() { return Resource.of(path); }
        });
        workbench.bindEditorExtensions("fake", "shadergraph");

        CgPath path = CgPath.parse("mymod.proj:thing.shadergraph");
        workbench.documentFor(path).encode();

        assertEquals("save must reach the document rather than a text editor",
                "encoded:thing.shadergraph", encoded.toString());
    }

    /** Binding an extension with no document factory fails loudly rather than opening an empty editor. */
    @Test
    public void aBindingWithoutADocumentFactoryIsReported() {
        workbench.bindEditorExtensions("nosuchtype", "weird");
        try {
            workbench.documentFor(CgPath.parse("mymod.proj:a.weird"));
            org.junit.Assert.fail("expected the missing factory to be reported");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nosuchtype"));
        }
    }

    /**
     * <b>{@code openFile}'s callback runs with the document present and the editor focusable.</b>
     *
     * <p>The navigation primitive under go-to-line, go-to-definition and clicking a problem. It exists
     * because {@code openFile} has two paths and only one is synchronous: an open file activates its tab
     * and returns, an unopened one goes through a {@code client.read} round trip. Anything that positioned
     * a caret on the statement <em>after</em> the call therefore acted on the previous editor.</p>
     */
    @Test
    public void openFileRunsItsCallbackWithTheEditorReadyToFocus() {
        CgPath path = CgPath.parse("mymod.proj:src/Main.java");
        TextEditor editor = openWithContent(path);

        boolean[] ran = {false};
        TextEditor[] seen = {null};
        workbench.openFile(path, () -> {
            ran[0] = true;
            seen[0] = workbench.activeEditor();
            if (seen[0] != null) window.getInputHandler().requestFocus(seen[0]);
        });
        settle();

        assertTrue("the callback never ran, so nothing could be revealed", ran[0]);
        assertSame("the callback saw a different editor than the one it opened", editor, seen[0]);
        assertSame("focusing the editor from the callback did not stick — a problem clicked in the panel"
                        + " would land the caret nowhere",
                editor, window.getInputHandler().getFocusedElement());
    }

    /**
     * <b>Double-clicking a problem puts the caret on its line and the keyboard in the editor.</b>
     *
     * <p>End to end, through the real press route, because every part of this was individually correct
     * and the whole was not: the panel raises activation, the workbench opens the file, the callback
     * positions the caret — and the question is only whether focus is still on the editor once the press
     * has finished being dispatched.</p>
     */
    @Test
    public void doubleClickingAProblemLandsTheCaretAndTheFocusInTheEditor() {
        // ONE-TO-ONE, because the press below is built from the row's LAYOUT position while the input
        // handler takes SCREEN coordinates — the two differ by exactly uiScale, and at any other value
        // the press lands somewhere else in the panel.
        window.setUiScale(1f);
        settle();

        CgPath path = CgPath.parse("mymod.proj:src/Main.java");
        TextEditor editor = openWithContent(path);
        // ENOUGH LINES TO LAND ON. setCaret clamps, so a diagnostic pointing past the end would put the
        // caret at the last line and this would assert nothing about navigation at all.
        editor.setText("one\ntwo\nthree\nfour\nfive");
        settle();

        // THE WORKBENCH'S OWN RESOURCE, not one built here: the panel filters by identity of the resource
        // the document reports, and a hand-made one that merely looks the same matches nothing.
        assertNotNull("no active document", workbench.activeDocument());
        com.crystalgui.fs.Resource resource = workbench.activeDocument().resource();
        // ATTACHED FIRST, THEN FILLED. The panel refreshes from `markers.onDidChange`, so a set populated
        // before it is attached announces nothing and the tree stays empty.
        com.crystalgui.text.diagnostic.DiagnosticSet set =
                workbench.markers().attach(resource, new com.crystalgui.text.diagnostic.DiagnosticSet());
        set.setAll(java.util.List.of(new com.crystalgui.text.diagnostic.Diagnostic(
                new com.crystalgui.text.TextPoint(2, 0), new com.crystalgui.text.TextPoint(2, 4),
                com.crystalgui.text.diagnostic.DiagnosticSeverity.ERROR, "boom", null, null)));
        settle();

        var tree = workbench.problems().tree();
        Integer problemIndex = null;
        for (int i = 0; i < tree.visibleRows().size(); i++) {
            if (!tree.rowAt(i).item().isFile()) { problemIndex = i; break; }
        }
        assertNotNull("no problem row: rows=" + tree.visibleRows().size()
                + " files=" + workbench.problems().visibleFiles()
                + " source=" + workbench.problems().source()
                + " resource=" + resource, problemIndex);
        UIElement row = tree.realisedRows().get(problemIndex);
        assertNotNull("the problem row is not realised", row);

        java.util.List<Object> chosen = new java.util.ArrayList<>();
        workbench.problems().onProblemChosen.connect(chosen::add);

        press(row);
        assertTrue("the press did not even land on the row — selection unchanged, so the coordinates or"
                        + " the hit test are wrong, not the activation. focused="
                        + window.getInputHandler().getFocusedElement(),
                tree.isSelected(problemIndex));
        press(row);
        settle();

        assertFalse("the double click never reached the panel at all", chosen.isEmpty());
        assertEquals("the caret should be on the problem's line — chosen=" + chosen
                        + " activeEditor=" + (workbench.activeEditor() == editor),
                2, editor.caretPoint().row());
        assertSame("the keyboard is still in the panel — the caret is placed but you cannot type at it",
                editor, window.getInputHandler().getFocusedElement());
    }

    /**
     * <b>A file with errors is marked on its tab and in the tree, from one provider.</b>
     *
     * <p>Both surfaces ask {@code FileDecorations} the same question, which is what stops a tab and a tree
     * row disagreeing about one file — and means dirty state and VCS reach the tab for free rather than
     * needing a second mechanism per surface.</p>
     *
     * <p><b>Errors only.</b> A decoration on a filename is read at a glance across a whole tree and its
     * only useful question is "is this broken"; an amber name for warnings is most files most of the time,
     * and the graded answer already exists in the inspection widget and the Problems panel.</p>
     */
    @Test
    public void aFileWithErrorsIsDecoratedOnItsTabAndInTheTree() {
        CgPath path = CgPath.parse("mymod.proj:src/Main.java");
        openWithContent(path);
        settle();

        var decorations = workbench.fileTree().getDecorations();
        assertNull("a clean file must not be decorated", styleClassOf(decorations, path));

        com.crystalgui.fs.Resource resource = workbench.activeDocument().resource();
        com.crystalgui.text.diagnostic.DiagnosticSet set =
                workbench.markers().attach(resource, new com.crystalgui.text.diagnostic.DiagnosticSet());

        // A WARNING FIRST, which must change nothing at all.
        set.setAll(java.util.List.of(new com.crystalgui.text.diagnostic.Diagnostic(
                new com.crystalgui.text.TextPoint(0, 0), new com.crystalgui.text.TextPoint(0, 1),
                com.crystalgui.text.diagnostic.DiagnosticSeverity.WARNING, "meh", null, null)));
        settle();
        assertNull("warnings must not decorate — only errors do", styleClassOf(decorations, path));

        set.setAll(java.util.List.of(new com.crystalgui.text.diagnostic.Diagnostic(
                new com.crystalgui.text.TextPoint(0, 0), new com.crystalgui.text.TextPoint(0, 1),
                com.crystalgui.text.diagnostic.DiagnosticSeverity.ERROR, "boom", null, null)));
        settle();

        assertEquals("the tree row is not marked", "decoration-error", styleClassOf(decorations, path));
        assertEquals("the tab is not marked", "decoration-error", tabDecorationClass(path));

        // AND IT COMES OFF. A tab outlives every state its file passes through, so a class that is added
        // and never swapped leaves it red for the rest of the session once the file has been wrong once.
        set.setAll(java.util.List.of());
        settle();
        assertNull("the tree kept the mark after the fix", styleClassOf(decorations, path));
        assertNull("the tab kept the mark after the fix", tabDecorationClass(path));
    }

    /** Null is the ordinary answer for an undecorated file, so resolve() may hand back null itself. */
    private static String styleClassOf(
            com.crystalgui.ui.elements.workbench.decoration.FileDecorations decorations, CgPath path) {
        var decoration = decorations.resolve(path, false);
        return decoration == null ? null : decoration.styleClass();
    }

    /** The {@code decoration-*} class actually on the tab element, or null. */
    private String tabDecorationClass(CgPath path) {
        var tab = workbench.dock().groupFor(
                        workbench.dock().layout().leaves().get(0))
                .tabFor(workbench.refFor(path));
        if (tab == null) return null;
        for (String cls : tab.getClasses()) {
            if (cls.startsWith("decoration-")) return cls;
        }
        return null;
    }

    /** A press through the real route — focus resolution is exactly what is under test. */
    private void press(UIElement target) {
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        int cx = (int) (target.getRuntimeCache().getX() + target.getRuntimeCache().getWidth() / 2f);
        int cy = (int) (target.getRuntimeCache().getY() + target.getRuntimeCache().getHeight() / 2f);
        window.getInputHandler().beginFrame();
        window.getInputHandler().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(cx, cy, 0, 0, 0, true, 0f, 0L));
        window.getInputHandler().endFrame();
    }
}
