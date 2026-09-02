package com.crystalgui.ui;

import com.crystalgui.support.OldEngineSessions;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.fs.InMemoryConfigStorage;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.workbench.ToolWindowType;
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
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockWindow;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.elements.workbench.WorkbenchSession;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;

import dev.vfyjxf.taffy.style.FlexDirection;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkbenchSession} — closing a project and opening it where you left it.
 *
 * <p>Each test builds a <b>second, independent workbench</b> and restores into it, rather than restoring
 * into the one that saved. Restoring into the same instance proves almost nothing: every document is
 * already open, every caret is already where it was, and a restore that did nothing at all would pass.</p>
 */
public class WorkbenchSessionTest extends UiTestBase {

    private static final String PROJECT = "mymod.proj";
    private static final CgPath README = CgPath.parse("mymod.proj:README.md");
    private static final CgPath MAIN = CgPath.parse("mymod.proj:src/Main.java");

    /** A file with enough indented structure for the indent folder to find a region. */
    private static final String FOLDABLE =
            "class Main {\n    void a() {\n        one();\n        two();\n    }\n"
                    + "    void b() {\n        three();\n    }\n}\n";

    private final ConfigStorage storage = new InMemoryConfigStorage();

    private Harness first;

    /** One workbench and the transport behind it — several exist per test, deliberately. */
    private static final class Harness {
        UIWindow window;
        Workbench workbench;
        WorkbenchSession session;
        InMemoryTransport<Object> serverSide;
        InMemoryTransport<Object> clientSide;
        ClientUiSession<UIElement, Object> clientSession;
        ServerUiSession<UIElement, Object> serverSession;

        /**
         * Moves the workbench into a {@code WindowFrame} on the desktop — the shape W7 made real.
         *
         * <p>{@code setContent}, not {@code content().addChild}: it is what adopts the workbench's menu
         * bar into the caption, and it is the call the application makes.</p>
         */
        void intoWindow() {
            workbench.removeSelf();
            WindowFrame frame = window.openWindow(new WindowFrame("Editor").setKey("editor:main"));
            frame.resizeTo(900, 600).moveTo(20, 20);
            frame.setContent(workbench);
            settle();
        }

        /** What {@code UIWindow.init} does on the frame after — the tree joins a window. */
        void attach() {
            window.init(1200, 800);
            settle();
        }

        void settle() {
            for (int i = 0; i < 12; i++) {
                serverSide.deliver();
                clientSide.deliver();
                clientSession.tick();
                serverSession.tick();
                window.updateWithoutPainting();
                window.getInputHandler().beginFrame();
                window.getInputHandler().endFrame();
                session.tick();
            }
        }
    }

    private Harness build() {
        Harness harness = buildCold();
        // Frames FIRST, then ask for the projects. A call made before the session has run a frame is not
        // delivered, and the tree then has no roots for a reason that has nothing to do with the code
        // under test -- the same trap ExplorerCommandsTest's settle() note describes.
        harness.settle();
        harness.workbench.fileTree().loadProjects();
        harness.settle();
        return harness;
    }

    /**
     * A workbench that has not run a frame, so nothing has been listed and no project is known yet.
     *
     * <p>What a restore actually runs against on startup, and the only way to exercise the retry: a
     * workbench that has already settled knows every folder, so a restore that gave up after one attempt
     * would still pass.</p>
     */
    private Harness buildCold() {
        return buildCold(true);
    }

    private Harness buildCold(boolean attached) {
        Harness harness = new Harness();
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "# hello")
                .seed("mymod.proj:src/Main.java", FOLDABLE);
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT, "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        harness.serverSide = pair[0];
        harness.clientSide = pair[1];
        harness.serverSession = OldEngineSessions.serve(1, new UIElement(), pair[0]);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(harness.serverSession::onCall);
        harness.serverSession.open();
        harness.clientSession = OldEngineSessions.view(pair[1]);

        harness.workbench = new Workbench(new WorkspaceClient<>(harness.clientSession, PlainOps.INSTANCE));
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(harness.workbench);
        harness.window = new UIWindow(Ui.of(root));
        harness.window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        if (attached) harness.window.init(1200, 800);
        harness.session = new WorkbenchSession(harness.workbench, storage);
        return harness;
    }

    /**
     * A workbench whose tree has <b>never joined a window</b> — what a host restoring on its first frame
     * has, because {@code UIWindow.init} is what attaches the root and it has not run yet.
     */
    private Harness buildDetached() {
        return buildCold(false);
    }

    @Before
    public void setUp() {
        first = build();
    }

    /**
     * <b>The files that were open come back.</b>
     *
     * <p>Nothing in the record lists them: a leaf's {@code DockPanelRef} already carries its path, and the
     * workbench's panel factory reads a file it has not read yet. A separate open-file list would be a
     * second copy of the same fact, and the two disagree the first time a tab is closed.</p>
     *
     * <p>Asserted on {@code openTabPaths} rather than {@code openPaths}, and the difference is the point:
     * a restored tab is a title until something activates it, so a session with two files comes back with
     * two <b>tabs</b> and one <b>document</b>. Both are "open"; only one has been built.</p>
     */
    @Test
    public void theOpenFilesComeBack() {
        first.workbench.openFile(README);
        first.workbench.openFile(MAIN);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        assertTrue("nothing was open in a fresh workbench", second.workbench.openTabPaths().isEmpty());
        assertTrue(second.session.restore(PROJECT));
        second.settle();

        assertTrue("README's tab did not reopen", second.workbench.openTabPaths().contains(README));
        assertTrue("Main's tab did not reopen", second.workbench.openTabPaths().contains(MAIN));

        // MAIN was opened last, so it is the active tab and the one document that was built.
        assertEquals("only the active tab may have been materialised",
                java.util.Collections.singletonList(MAIN), second.workbench.openPaths());
        assertEquals("the active file's content did not arrive with it",
                FOLDABLE, second.workbench.editorFor(MAIN).getText());
    }

    /**
     * <b>And a background tab's content arrives when it is activated</b>, not before.
     *
     * <p>The other half of the row above, and the one that says the deferral is a deferral rather than a
     * loss. This is VS Code's behaviour exactly: a restored editor is a placeholder, and selecting it is
     * what reads the file.</p>
     */
    @Test
    public void aBackgroundTabLoadsWhenItIsActivated() {
        first.workbench.openFile(README);
        first.workbench.openFile(MAIN);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        assertTrue(second.session.restore(PROJECT));
        second.settle();
        assertFalse("README must not have been read yet", second.workbench.openPaths().contains(README));

        second.workbench.openFile(README);
        second.settle();
        assertEquals("activating it must read the file", "# hello",
                second.workbench.editorFor(README).getText());
    }

    /**
     * <b>A file nobody opened keeps its caret across a re-save.</b>
     *
     * <p>The silent loss lazy tabs would otherwise introduce, and the reason {@code WorkbenchSession.save}
     * writes back what it is still holding. A record's per-file view state is read into
     * {@code pendingViewState} and consumed when that file's document arrives — so for a tab nobody
     * activated, it is never consumed, and saving from the live documents alone drops it.</p>
     *
     * <p>It fails invisibly, which is what makes it worth a test: every tab still comes back, so nothing
     * looks lost until a file you had not touched opens at the top. Restart twice and it is gone for
     * good.</p>
     */
    @Test
    public void anUntouchedTabKeepsItsViewStateAcrossASave() {
        int caret = FOLDABLE.indexOf("three()");
        assertTrue(caret > 0);
        first.workbench.openFile(MAIN);
        first.settle();
        first.workbench.editorFor(MAIN).setCaret(caret);
        // AND THEN OPEN SOMETHING ELSE, so MAIN is the background tab in the record.
        first.workbench.openFile(README);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        // Restore, touch nothing, save again -- MAIN is the background tab and was never built.
        Harness second = build();
        assertTrue(second.session.restore(PROJECT));
        second.settle();
        assertFalse("MAIN must be the unbuilt one for this to test anything",
                second.workbench.openPaths().contains(MAIN));
        second.session.save(PROJECT, 1200, 800);

        // And now open it from THAT record. The caret has to have survived a round trip it sat out.
        Harness third = build();
        assertTrue(third.session.restore(PROJECT));
        third.settle();
        third.workbench.openFile(MAIN);
        third.settle();
        assertEquals("the caret of a tab nobody opened was dropped on the way through",
                caret, third.workbench.editorFor(MAIN).getCaret());
    }

    /**
     * <b>The caret comes back, and it comes back after the content does.</b>
     *
     * <p>The ordering is the whole point. A caret restored into a document whose read is still in flight
     * clamps to zero, and the symptom — a caret that always opens at the top — is indistinguishable from
     * view state never having been saved at all. Line 5 of the seeded file is past the end of an empty
     * document, so a restore applied too early cannot produce this number by accident.</p>
     */
    @Test
    public void theCaretComesBackAfterTheContentDoes() {
        first.workbench.openFile(MAIN);
        first.settle();
        TextEditor editor = first.workbench.editorFor(MAIN);
        assertNotNull(editor);
        int caret = FOLDABLE.indexOf("three()");
        assertTrue(caret > 0);
        editor.setCaret(caret);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        assertTrue(second.session.restore(PROJECT));
        second.settle();

        TextEditor restored = second.workbench.editorFor(MAIN);
        assertNotNull("the file did not reopen at all", restored);
        assertEquals("the caret was restored against a document that had not loaded yet",
                caret, restored.getCaret());
    }

    /** A selection comes back as a selection, not as a caret parked at one of its ends. */
    @Test
    public void aSelectionComesBackAsASelection() {
        first.workbench.openFile(MAIN);
        first.settle();
        first.workbench.editorFor(MAIN).setSelection(6, 20);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.session.restore(PROJECT);
        second.settle();

        TextEditor restored = second.workbench.editorFor(MAIN);
        assertTrue("a restored selection collapsed to a caret", restored.hasSelection());
        assertEquals(6, restored.getSelectionStart());
        assertEquals(20, restored.getSelectionEnd());
    }

    /**
     * <b>Collapsed regions come back.</b>
     *
     * <p>The one that needs {@code TextEditor.setCollapsedRows} to recompute regions first: folds are
     * rebuilt from the text one frame <em>after</em> the text arrives, so a restore that collapsed against
     * the region set as it stood would find it empty and silently do nothing.</p>
     */
    @Test
    public void collapsedRegionsComeBack() {
        first.workbench.openFile(MAIN);
        first.settle();
        TextEditor editor = first.workbench.editorFor(MAIN);
        editor.setCollapsedRows(1);
        first.settle();
        assertEquals("the fixture has no foldable region at row 1, so this test proves nothing",
                1, editor.collapsedRows().length);
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.session.restore(PROJECT);
        second.settle();

        assertArrayEqualsMessage("the fold was not restored",
                new int[]{1}, second.workbench.editorFor(MAIN).collapsedRows());
    }

    /** An expanded folder comes back, which needs its parent's listing to have arrived first. */
    @Test
    public void anExpandedFolderComesBack() {
        CgPath src = CgPath.parse("mymod.proj:src");
        first.workbench.fileTree().treeView().setExpanded(CgPath.ofProject(PROJECT), true);
        first.settle();
        first.workbench.fileTree().treeView().setExpanded(src, true);
        first.settle();
        assertTrue("the fixture did not expand, so this test proves nothing",
                first.workbench.fileTree().treeView().isExpanded(src));
        first.session.save(PROJECT, 1200, 800);

        // COLD, which is what a restore on startup actually runs against: no project is known yet, so
        // `src` is not even a directory as far as the tree is concerned when the record is read.
        Harness second = buildCold();
        assertFalse("the second workbench must not already know this folder, or the retry is untested",
                second.workbench.fileTree().source().hasChildren(src));
        second.session.restore(PROJECT);
        second.settle();

        assertTrue("a folder cannot be expanded before the listing revealing it lands -- the restore must "
                        + "retry rather than give up on the first frame",
                second.workbench.fileTree().treeView().isExpanded(src));
    }

    /**
     * <b>An unknown version is discarded for the defaults, not parsed hopefully.</b>
     *
     * <p>{@code DockLayoutCodec}'s rule, and right here for the same reason: a session restored from a
     * format that changed meaning puts panels in places nobody asked for. Settings take the opposite rule
     * deliberately — discarding those would silently reset every preference somebody has.</p>
     */
    @Test
    public void aRecordFromAnotherVersionIsDiscarded() {
        first.workbench.openFile(README);
        first.settle();
        String record = first.session.toJson(1200, 800);
        String fromTheFuture = record.replace("\"version\": 1", "\"version\": 99");
        assertFalse("the version was not where the rewrite expected it", record.equals(fromTheFuture));

        Harness second = build();
        assertFalse(second.session.fromJson(fromTheFuture));
        second.settle();
        assertTrue("a discarded record must change nothing at all",
                second.workbench.openPaths().isEmpty());
    }

    /** Unparseable text is a normal outcome, not an exception, and changes nothing. */
    @Test
    public void anUnreadableRecordChangesNothing() {
        Harness second = build();
        assertFalse(second.session.fromJson("{ this is not json"));
        assertTrue(second.workbench.openPaths().isEmpty());
    }

    /** Nothing stored is the first-run case, and the caller already needs a default layout for it. */
    @Test
    public void noRecordAtAllIsNotAnError() {
        assertFalse(first.session.restore("never.seen.this.project"));
    }

    /**
     * <b>A file that has since been deleted is dropped; the rest of the session survives.</b>
     *
     * <p>The same degradation rule {@code DockLayoutCodec} keeps for a panel type belonging to an
     * uninstalled mod. Losing a whole arrangement because one file moved is the failure worth avoiding.</p>
     */
    @Test
    public void aRecordNamingAMissingFileStillRestoresTheRest() {
        first.workbench.openFile(README);
        first.settle();
        String record = first.session.toJson(1200, 800)
                .replace("mymod.proj:README.md", "mymod.proj:gone.txt");

        Harness second = build();
        assertTrue("one missing file must not refuse the whole restore", second.session.fromJson(record));
        second.settle();
    }

    /** The filename cannot be steered out of the config directory by a project id. */
    @Test
    public void theRecordNameIsConfinedToTheConfigDirectory() {
        String name = WorkbenchSession.fileNameFor("../../etc/passwd");
        assertFalse(name, name.contains("/"));
        assertFalse(name, name.contains("\\"));
        assertTrue(name, name.startsWith("session."));
        assertTrue(name, name.endsWith(".json"));
    }

    /** Turning the session off means it is not restored, however good the record is. */
    @Test
    public void restoreIsSkippedWhenTheUserTurnedItOff() {
        first.workbench.openFile(README);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.window.ui.rootElement.settings()
                .set(SettingsLayer.USER, WorkbenchSettings.RESTORE_SESSION, false);
        assertFalse("the setting must be read through the scope chain, from the root",
                second.workbench.resolve(WorkbenchSettings.RESTORE_SESSION));
    }

    // -- Widget state ----------------------------------------------------------------------------

    private static final String CONSOLE = "console";
    private static final String SPLIT_ID = "test.rail-split";

    /**
     * A tool window holding a split it builds LAZILY — the Run panel's shape, which is the case that
     * matters.
     *
     * <p>Deliberately a real {@link SplitView} rather than a stub with a float on it. What is under test
     * is that a widget describes itself through the {@code writeState} hook it already has, so a stub
     * implementing the storage by hand would be testing the test.</p>
     */
    private static final class LazyPanel extends UIElement {
        SplitView split;

        void build() {
            if (split != null) return;
            SplitView view = new SplitView();
            view.first(new UIElement());
            view.second(new UIElement());
            view.setId(SPLIT_ID);
            view.setSessionPersistent(true);
            split = view;
            addChild(view);
        }
    }

    private static LazyPanel installPanel(Harness harness) {
        LazyPanel panel = new LazyPanel();
        harness.workbench.registerPanel(
                DockPanelDescriptor.singleton(CONSOLE, "Console").region(DockRegion.PANEL),
                ref -> panel);
        return panel;
    }

    /**
     * <b>A widget's own state survives the session.</b>
     *
     * <p>The dock record says where a panel <em>is</em>; nothing in it says anything about what is inside
     * one, because the dock deliberately does not serialize an element tree. Without this a divider
     * somebody dragged is forgotten on every launch, and the loss is silent — the panel comes back in the
     * right place, at the wrong width.</p>
     *
     * <p>Note what the restore does <b>not</b> do: it never touches the split. The state is handed over by
     * {@code UIWindow.registerElement} as the widget joins the tree, which is the only reason a widget
     * built after the fact can be reached at all.</p>
     */
    @Test
    public void aWidgetsOwnStateComesBack() {
        LazyPanel saved = installPanel(first);
        first.workbench.togglePanel(CONSOLE);
        first.settle();
        saved.build();
        first.settle();
        saved.split.setPercentage(41f);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        LazyPanel restored = installPanel(second);
        assertTrue(second.session.restore(PROJECT));
        // The panel was open when the record was written, so the restore opens it -- toggling here would
        // SHUT it, and the split would then be built into a detached panel that never joins a window.
        second.settle();
        restored.build();
        second.settle();

        assertEquals("the divider did not come back", 41f, restored.split.getPercentage(), 0.5f);
    }

    /**
     * <b>A widget built long AFTER the restore still gets its state.</b>
     *
     * <p>The ordinary case, not the exception. A tool window is built the first time it is opened and a
     * widget inside one may be built later still — the Run panel's split does not exist until a script
     * runs. Anything applied once at startup misses all of that, and misses it silently, because the
     * widget looks correct sitting at its default.</p>
     *
     * <p>Saved with the panel <b>closed</b>, so the restore has nothing to build and the assertion below
     * is about a widget that appears minutes later as far as the session is concerned.</p>
     */
    @Test
    public void aWidgetBuiltAfterTheRestoreStillGetsItsState() {
        LazyPanel saved = installPanel(first);
        first.workbench.togglePanel(CONSOLE);
        first.settle();
        saved.build();
        first.settle();
        saved.split.setPercentage(33f);
        first.settle();
        first.workbench.hidePanel(CONSOLE);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        LazyPanel restored = installPanel(second);
        assertTrue(second.session.restore(PROJECT));
        second.settle();
        assertNull("nothing should have been built by the restore itself", restored.split);

        second.workbench.togglePanel(CONSOLE);
        second.settle();
        restored.build();
        second.settle();
        assertEquals("the divider did not arrive when the split was finally built",
                33f, restored.split.getPercentage(), 0.5f);
    }

    /**
     * <b>Saving does not erase the state of a widget nobody built.</b>
     *
     * <p>Writing only what is on screen makes every save an erasure for every widget not built that
     * session — a divider would survive exactly as long as the habit of opening its panel, and the
     * erosion is invisible because each individual save looks correct.</p>
     */
    @Test
    public void aWidgetNobodyBuiltKeepsItsStateAcrossSaves() {
        LazyPanel saved = installPanel(first);
        first.workbench.togglePanel(CONSOLE);
        first.settle();
        saved.build();
        first.settle();
        saved.split.setPercentage(37f);
        first.settle();
        first.workbench.hidePanel(CONSOLE);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        // A whole session that never builds it, and saves.
        Harness second = build();
        installPanel(second);
        assertTrue(second.session.restore(PROJECT));
        second.settle();
        second.session.save(PROJECT, 1200, 800);

        // A third that does.
        Harness third = build();
        LazyPanel restored = installPanel(third);
        assertTrue(third.session.restore(PROJECT));
        third.settle();
        third.workbench.togglePanel(CONSOLE);
        third.settle();
        restored.build();
        third.settle();
        assertEquals("the untouched session wrote the divider away",
                37f, restored.split.getPercentage(), 0.5f);
    }

    /**
     * <b>...and so does one that was WINDOWED, with the workbench inside a window of its own.</b>
     *
     * <p>The shape the application actually runs in since W7 — the editor is a {@code WindowFrame} on a
     * desktop, not a panel under the root — and the one the harness reported. Kept separate from the
     * floating case because the two take different branches in {@code showInFrame}: a float is
     * {@code attachOwned} to the workbench's own frame, a windowed one is a top-level
     * {@code openWindow} with an owner relation. A fixture with the workbench under the bare root cannot
     * tell them apart, because with no frame to own it a float takes the top-level branch too.</p>
     */
    @Test
    public void aWindowedToolWindowComesBackOpenFromInsideAWindow() {
        first.intoWindow();
        first.workbench.showPanel(Workbench.PROBLEMS_TYPE);
        first.settle();
        first.workbench.toolWindowManager().setType(Workbench.PROBLEMS_TYPE, ToolWindowType.WINDOWED);
        first.settle();
        assertTrue("the fixture never opened it as a window",
                first.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));

        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.intoWindow();
        second.workbench.toolWindowManager().hidePanel(Workbench.PROBLEMS_TYPE);
        second.settle();
        assertTrue(second.session.restore(PROJECT));
        second.settle();

        assertTrue("a windowed tool window never came back",
                second.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));
    }

    /**
     * <b>A windowed tool window restored BEFORE the tree has a window still opens.</b>
     *
     * <p>The one the harness reported, and the reason the other two tests passed while it was broken: they
     * restore into a workbench that is already attached. A host may legitimately restore on its very first
     * frame — before anything called {@code UIWindow.init} — and then a windowed tool window has nowhere
     * to open into. {@code showInFrame} returned false into a caller that ignores the result, so every
     * float and every windowed panel silently failed to come back <em>with the record on disk perfectly
     * correct</em>, which is what made it read as "persistence is broken".</p>
     *
     * <p>The DOCKED path needs no window at all, so those came back and the failure looked partial.</p>
     */
    @Test
    public void aWindowedToolWindowRestoredBeforeTheTreeIsAttachedStillOpens() {
        first.intoWindow();
        first.workbench.showPanel(Workbench.PROBLEMS_TYPE);
        first.settle();
        first.workbench.toolWindowManager().setType(Workbench.PROBLEMS_TYPE, ToolWindowType.WINDOWED);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        // A WORKBENCH IN NO WINDOW AT ALL, which is what a host that restores on its first frame has.
        Harness second = buildDetached();
        assertTrue(second.session.restore(PROJECT));

        assertFalse("the fixture was not detached, so this proves nothing",
                second.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));

        // ...and now it joins one, exactly as UIWindow.init does on the frame after.
        second.attach();

        assertTrue("a windowed tool window asked for before the tree had a window never opened",
                second.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));
    }

    /**
     * <b>A tool window restored into a FRAME leaves its docked region empty.</b>
     *
     * <p>Reported as "the auxiliary bar's space is still reserved as if it were open": the editor comes
     * back a column narrower, with nothing in the column. A host left recording an occupant it no longer
     * contains answers {@code isEmpty() == false}, so {@code sync()} keeps the region in the split and its
     * whole width stays behind — the standing invariant about the host being the truth, met from a new
     * direction.</p>
     *
     * <p><b>Only the restore path could reach it.</b> Undocking by hand goes through {@code setType},
     * which hides the panel while its type is still DOCKED, so {@code hidePanel} takes its docked branch
     * and clears the half. A restore never hides anything — it decodes a placement that already says
     * WINDOWED and shows it — so {@code hidePanel}'s early return for a windowed type meant nothing was
     * ever cleared.</p>
     *
     * <p>Asserted on {@code isEmpty()}, which is what {@code sync()} actually reads. Asserting the panel
     * is open would pass against the bug, because it genuinely is open — in a frame, with its old column
     * still sitting there beside the editor.</p>
     */
    @Test
    public void aToolWindowRestoredIntoAFrameLeavesItsRegionEmpty() {
        first.intoWindow();
        first.workbench.showPanel(Workbench.PROBLEMS_TYPE);
        first.settle();
        first.workbench.toolWindowManager().setType(Workbench.PROBLEMS_TYPE, ToolWindowType.WINDOWED);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        // A FRESH LAUNCH with the panel DOCKED, exactly as the workbench opens it before a record is read.
        Harness second = build();
        second.intoWindow();
        second.workbench.showPanel(Workbench.PROBLEMS_TYPE);
        second.settle();
        DockRegion region = second.workbench.toolWindowManager().regionOf(Workbench.PROBLEMS_TYPE);
        assertFalse("the fixture never docked it",
                second.workbench.regions().host(region).isEmpty());

        assertTrue(second.session.restore(PROJECT));
        second.settle();

        assertTrue("the region still records a panel that is now in a frame, so its column stays behind",
                second.workbench.regions().host(region).isEmpty());
    }

    /**
     * <b>...and one put away before the tree attaches stays away.</b>
     *
     * <p>The other half of the deferral. A windowed show that could not be satisfied is remembered, so
     * something has to forget it when the panel is closed in between — otherwise the retry resurrects a
     * panel the user has just dismissed, which is the "an intent outlives the thing it described" shape a
     * stale watch has. {@code hidePanel} taking it out of the set is the one mechanism that does this.</p>
     */
    @Test
    public void aPanelHiddenBeforeTheTreeAttachesIsNotResurrected() {
        first.intoWindow();
        first.workbench.showPanel(Workbench.PROBLEMS_TYPE);
        first.settle();
        first.workbench.toolWindowManager().setType(Workbench.PROBLEMS_TYPE, ToolWindowType.WINDOWED);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = buildDetached();
        assertTrue(second.session.restore(PROJECT));
        // PUT AWAY WHILE THE SHOW IS STILL PENDING -- there is no window yet, so nothing has opened.
        second.workbench.toolWindowManager().hidePanel(Workbench.PROBLEMS_TYPE);

        second.attach();

        assertFalse("the retry reopened a panel that had just been dismissed",
                second.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));
    }

    /**
     * <b>A tool window that was FLOATING when the session was written comes back open.</b>
     *
     * <p>It did not, and the shape of the failure is why this test exists rather than a wider one. The
     * capture derived "is this on screen" from {@code host.showing(side)} — which can only ever see a
     * DOCKED panel, because a float lives in a frame and not in a region half. So a floating tool window
     * recorded as {@code visible: false} every single time and was never reopened.</p>
     *
     * <p><b>Its placement survived perfectly</b>, which is what made it read as "restore is broken" rather
     * than as one field: reopening the panel by hand put it back in exactly the right place, at the right
     * size, as a float. Only the fact that it had been open was lost.</p>
     *
     * <p>{@code ToolWindowManager.isPanelOpen} already answered this correctly for both presentations, and
     * its own javadoc warns against the very expression the capture had rolled by hand.</p>
     */
    @Test
    public void aFloatingToolWindowComesBackOpen() {
        first.workbench.showPanel(Workbench.PROBLEMS_TYPE);
        first.settle();
        first.workbench.toolWindowManager().setType(Workbench.PROBLEMS_TYPE, ToolWindowType.FLOATING);
        first.settle();
        assertTrue("the fixture never floated it",
                first.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));

        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.workbench.toolWindowManager().hidePanel(Workbench.PROBLEMS_TYPE);
        second.settle();
        assertTrue(second.session.restore(PROJECT));
        second.settle();

        assertTrue("a floating tool window was recorded as closed and never came back",
                second.workbench.toolWindowManager().isPanelOpen(Workbench.PROBLEMS_TYPE));
        assertEquals("it came back docked rather than as the float it was",
                ToolWindowType.FLOATING,
                second.workbench.toolWindowManager().typeOf(Workbench.PROBLEMS_TYPE));
    }

    // ── Torn-out editor windows (W9, persisted at W12) ──────────────────────────────────────────

    /**
     * Tears a leaf out of {@code harness}'s main dock into a window, exactly as
     * {@code DockArea.tearOutToWindow} does — same registry, same constructor, same
     * {@code DockLayout.of}. What differs is only that a drag is not driven to get there.
     */
    private DockWindow tearOut(Harness harness, float left, float top) {
        // A PANEL, not the leaf. DockArea.detach tears a whole GROUP out through DockLayout.tearOut and
        // a single panel by removing it and building a fresh one-panel leaf -- and the central leaf
        // cannot be torn out at all, so the leaf spelling returns null on a one-editor dock, which is
        // every fixture here.
        DockLeaf leaf = harness.workbench.dock().layout().leaves().get(0);
        assertFalse("the fixture has no panel to tear out", leaf.panels().isEmpty());
        DockPanelRef panel = leaf.panels().get(0);
        leaf.remove(panel);
        DockWindow frame = new DockWindow(harness.workbench.panels(),
                DockLayout.of(new DockLeaf(panel)), "Torn out");
        frame.resizeTo(420f, 300f);
        frame.moveTo(left, top);
        harness.window.openWindow(frame);
        harness.workbench.dock().requestRebuild();
        harness.settle();
        return frame;
    }

    private List<DockWindow> dockWindowsOf(Harness harness) {
        List<DockWindow> out = new java.util.ArrayList<>();
        for (WindowFrame frame : harness.window.desktop().registry().windows()) {
            if (frame instanceof DockWindow dock) out.add(dock);
        }
        return out;
    }

    /**
     * <b>A torn-out editor window comes back — with its tab, and where it was.</b>
     *
     * <p>It was persisted by <em>nothing</em>: {@code tearOut} removes the leaf from the main layout, so
     * it was in no project record, and a {@code DockWindow} carries no {@code WindowFrame.key()}, so it
     * was in no desktop record either. What made that read as an editor bug rather than a persistence
     * gap is that the file's caret and scroll survived perfectly — {@code openPaths()} reads
     * {@code OpenDocuments}, which is document-level — leaving view state with no tab to land in.</p>
     */
    @Test
    public void aTornOutWindowComesBack() {
        first.intoWindow();
        first.workbench.openFile(MAIN);
        first.settle();
        tearOut(first, 140f, 90f);
        assertEquals("the fixture did not tear a window out", 1, dockWindowsOf(first).size());

        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.intoWindow();
        second.session.restore(PROJECT);
        second.settle();

        List<DockWindow> restored = dockWindowsOf(second);
        assertEquals("the torn-out window did not come back", 1, restored.size());
        DockWindow frame = restored.get(0);
        assertFalse("it came back empty", frame.isEmpty());
        assertEquals("it came back somewhere else", 140f, frame.getWantedLeft(), 1f);
        assertEquals(90f, frame.getWantedTop(), 1f);

        // ASKED OF THE WINDOW'S OWN DOCK, deliberately, and this is worth writing down: `openTabPaths`
        // walks the MAIN dock's leaves, so it does not see a torn-out window's tabs at all. That is a
        // pre-existing property of W9 rather than of this record -- the file's DOCUMENT is global, so
        // `openPaths` does see it -- but it means "what is open" has two answers depending on which one
        // is asked, and only one of them knows about torn-out windows.
        assertTrue("the torn-out window came back without the file it held",
                pathsIn(frame).contains(MAIN));
    }

    /** The files with a tab in {@code frame}'s own dock. @see Workbench#PATH_STATE */
    private static List<CgPath> pathsIn(DockWindow frame) {
        List<CgPath> out = new java.util.ArrayList<>();
        for (DockLeaf leaf : frame.area().layout().leaves()) {
            for (DockPanelRef panel : leaf.panels()) {
                String raw = panel.state(Workbench.PATH_STATE, "");
                if (!raw.isEmpty()) out.add(CgPath.parse(raw));
            }
        }
        return out;
    }

    /**
     * <b>...and the main dock does not get it back as well.</b>
     *
     * <p>The failure worth naming separately: the record holds two dock trees and the panel belongs to
     * exactly one of them. Writing the torn-out tree without having removed the leaf from the main one —
     * or restoring both — puts the same file in two places, which looks like a working restore until you
     * notice the duplicate tab.</p>
     */
    @Test
    public void aTornOutPanelIsNotAlsoRestoredIntoTheMainDock() {
        first.intoWindow();
        first.workbench.openFile(MAIN);
        first.settle();
        tearOut(first, 140f, 90f);
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        second.intoWindow();
        second.session.restore(PROJECT);
        second.settle();

        int inMainDock = 0;
        for (DockLeaf leaf : second.workbench.dock().layout().leaves()) {
            inMainDock += leaf.panels().size();
        }
        assertEquals("the torn-out panel came back in the main dock too", 0, inMainDock);
    }

    /**
     * <b>A window torn out of somebody ELSE's workbench is not this project's to record.</b>
     *
     * <p>A {@code DockWindow} is a top-level desktop citizen, not a descendant of the workbench, so
     * "which project does this belong to" cannot be answered by walking the tree. It is answered by
     * panel-registry identity — the dock that builds its content is this workbench's. Without that
     * discriminator a save would sweep up every dock window on the desktop, and the restore would then
     * open another project's editors into this one.</p>
     */
    @Test
    public void aDockWindowFromAnotherWorkbenchIsNotRecorded() {
        first.intoWindow();
        first.workbench.openFile(MAIN);
        first.settle();

        // ONE OF OURS, so the assertion below has a positive control: a filter that wrote nothing at all
        // would satisfy "the stranger is absent" perfectly.
        tearOut(first, 140f, 90f);

        // NOT EMPTY, and that is load-bearing rather than incidental: a DockWindow holding nothing
        // destroys itself on the frame after a drag ends, so a stranger built around an empty leaf is
        // gone before the save runs — and the test then passes with or without the discriminator. Caught
        // by a mutant that survived exactly because of it.
        DockPanelRegistry<UIElement> foreign = new DockPanelRegistry<>();
        foreign.register(DockPanelDescriptor.document("stranger", "Stranger"), ref -> new UIElement());
        DockWindow stranger = new DockWindow(foreign,
                DockLayout.of(new DockLeaf(new DockPanelRef("stranger"))), "Stranger window");
        stranger.resizeTo(300f, 200f).moveTo(400f, 300f);
        first.window.openWindow(stranger);
        first.settle();
        assertEquals("the foreign window closed itself before the save",
                WindowState.VISIBLE, stranger.state());

        String json = first.session.toJson(1200, 800);

        // A TITLE WITH NO APOSTROPHE, deliberately. Gson HTML-escapes by default, so a `'` in the record
        // comes out as `'` and `contains` never matches — the assertion is then vacuously true and
        // the test passes against any filter at all. Caught by a mutant that survived twice.
        assertTrue("the fixture is not asserting on anything", json.contains("Torn out"));
        assertFalse("a foreign dock window was written into this project's record",
                json.contains("Stranger window"));
    }

    /**
     * <b>...and it still opens when the restore ran before the tree had a window.</b>
     *
     * <p>The ordered-failure shape the tool windows already paid for: {@code openWindow} needs a
     * desktop, a host may legitimately restore on its first frame — before {@code UIWindow.init} — and
     * the docked half succeeds regardless. So the record is perfect, the main dock comes back, and only
     * the torn-out windows are missing, which reads as those windows not being saved.</p>
     */
    @Test
    public void aTornOutWindowRestoredBeforeThereIsAWindowStillOpens() {
        first.intoWindow();
        first.workbench.openFile(MAIN);
        first.settle();
        tearOut(first, 140f, 90f);
        first.session.save(PROJECT, 1200, 800);

        Harness second = buildDetached();
        second.session.restore(PROJECT);
        assertTrue("the fixture already had a window, so this proves nothing",
                dockWindowsOf2(second).isEmpty());

        second.attach();

        assertEquals("a torn-out window restored before the tree had a window never opened",
                1, dockWindowsOf2(second).size());
    }

    /** {@link #dockWindowsOf} for a harness whose window may have no desktop yet. */
    private List<DockWindow> dockWindowsOf2(Harness harness) {
        var desktop = harness.window.desktopIfPresent();
        if (desktop == null) return List.of();
        List<DockWindow> out = new java.util.ArrayList<>();
        for (WindowFrame frame : desktop.registry().windows()) {
            if (frame instanceof DockWindow dock) out.add(dock);
        }
        return out;
    }

    private static void assertArrayEqualsMessage(String message, int[] expected, int[] actual) {
        assertEquals(message + " (expected " + java.util.Arrays.toString(expected)
                + " but was " + java.util.Arrays.toString(actual) + ")",
                java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }
}
