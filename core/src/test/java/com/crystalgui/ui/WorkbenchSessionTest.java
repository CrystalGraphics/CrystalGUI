package com.crystalgui.ui;

import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.fs.InMemoryConfigStorage;
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
        ClientUiSession<Object> clientSession;
        ServerUiSession<Object> serverSession;

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
        harness.serverSession = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(harness.serverSession::onCall);
        harness.serverSession.open();
        harness.clientSession = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);

        harness.workbench = new Workbench(new WorkspaceClient<>(harness.clientSession, PlainOps.INSTANCE));
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(harness.workbench);
        harness.window = new UIWindow(Ui.of(root));
        harness.window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        harness.window.init(1200, 800);
        harness.session = new WorkbenchSession(harness.workbench, storage);
        return harness;
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
     */
    @Test
    public void theOpenFilesComeBack() {
        first.workbench.openFile(README);
        first.workbench.openFile(MAIN);
        first.settle();
        first.session.save(PROJECT, 1200, 800);

        Harness second = build();
        assertTrue("nothing was open in a fresh workbench", second.workbench.openPaths().isEmpty());
        assertTrue(second.session.restore(PROJECT));
        second.settle();

        assertTrue("README did not reopen", second.workbench.openPaths().contains(README));
        assertTrue("Main did not reopen", second.workbench.openPaths().contains(MAIN));
        assertEquals("the file's content did not arrive with it",
                "# hello", second.workbench.editorFor(README).getText());
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

    private static void assertArrayEqualsMessage(String message, int[] expected, int[] actual) {
        assertEquals(message + " (expected " + java.util.Arrays.toString(expected)
                + " but was " + java.util.Arrays.toString(actual) + ")",
                java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }
}
