package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.editor.EditorService;

/**
 * <b>A tab whose read lands after the dock was built shows the file, not the placeholder.</b>
 *
 * <p>Opening a file is asynchronous since F5: {@code EditorService.open} returns a {@link
 * DocumentState#LOADING} tab immediately and fills it when the read lands, which is what lets a session
 * restore put twelve tabs on screen at once rather than revealing them one round trip at a time. The
 * dock builds its panels on the next frame and asks the tab for its view; while the read is in flight
 * there is none, so the registry's factory returns an empty element as the placeholder for that
 * frame.</p>
 *
 * <p><b>{@code DockGroup} memoises what it built</b>, by design — its own comment says the map
 * "survives every rebuild of the tree above", which is what stops a split or a drag rebuilding a live
 * editor underneath the user. So the placeholder is not a frame's worth of nothing: it is what that tab
 * shows for ever, and no later rebuild replaces it.</p>
 *
 * <h3>Why no existing test saw it</h3>
 *
 * <p>Every other fixture opens a file whose document is already in the store, where {@code open}
 * answers a resolved {@link com.crystalgui.core.async.Reply} on the spot and the view exists before the
 * dock asks. A read that genuinely takes a round trip is the case a wire has and a fixture does not —
 * so this one puts a real transport in the middle and pumps it, which is what the harness does.</p>
 */
public class LoadedTabReplacesItsPlaceholderTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath FILE = CgPath.parse(PROJECT + ":Main.java");

    private static final String NL = System.lineSeparator();

    private InMemoryFileSystem files;

    /** Paths the server refuses to serve. @see #refuseReadsOf */
    private final Set<String> refused = new HashSet<>();

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();

        files = new InMemoryFileSystem().seed(FILE.toString(), "class Main { }" + NL);
        // A FILE THAT IS THERE AND WILL NOT BE SERVED, which is the case the auto-close must NOT take.
        // A permission is how a real server refuses one, and it answers NO_PERMISSIONS rather than
        // NOT_FOUND -- which is the whole distinction under test.
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files,
                (actor, project, path, operation) -> path == null || !refused.contains(path.toString()));

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workbench = new Workbench(Workspace.of(clientEnd));
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
    }

    @After
    public void closeWorkbench() {
        Protocols.resetForTesting();
    }

    /**
     * A frame, and one tick of the wire — the harness's own loop.
     *
     * <p>Deliberately NOT "deliver everything, then draw". The bug is a race between a read landing and
     * the dock building, and a fixture that settles the wire first can never lose it.</p>
     */
    private void frameAndPump() {
        frame();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    @Test
    public void aTabWhoseReadLandsAfterTheDockWasBuiltShowsTheFile() {
        // A REF IN THE LAYOUT WITH NO DOCUMENT BEHIND IT, which is what a session restore is: the
        // record names four files and nothing has read one. `openResource` cannot reach this — it adds
        // the ref inside the read's own `then`, so by the time the dock hears about it the tab is
        // bound. The factory is what starts the read here, exactly as it does on a restore.
        workbench.open(DockInput.of(workbench.refFor(FILE)));

        // THE DOCK BUILDS FIRST, which is the whole case: the ref is in the layout and the read the
        // factory just started has not come back.
        frameAndPump();
        EditorService.Tab tab = workbench.editors().tabFor(
                EditorInput.of(Resource.of(FILE)));
        assertNotNull("a tab exists immediately", tab);

        // ...and now it lands.
        for (int i = 0; i < 12; i++) frameAndPump();

        assertEquals("the read landed", DocumentState.CLEAN, tab.state());
        assertNotNull("the tab has a view", tab.editor());

        UIElement onScreen = contentOf(workbench.refFor(FILE));
        assertNotNull("the dock is showing something for this file", onScreen);
        assertSame("the dock is showing the tab's EDITOR, not the placeholder it built while the "
                        + "read was in flight", tab.editor().view(), onScreen);
        assertTrue("...and that editor holds the file", onScreen instanceof TextEditor);
        assertEquals("class Main { }\n", ((TextEditor) onScreen).getText());
    }

    /**
     * <b>...and typing does not rebuild it.</b> The counter-control, and it is not a formality.
     *
     * <p>{@code onDidChangeState} fires on {@code CLEAN -> DIRTY} too, which is every keystroke. A fix
     * written as "the state changed, so rebuild" passes the test above and detaches the editor the user
     * is typing in — the widget-rebuild trap, on the one widget that can least afford it. The guard is
     * what is ON SCREEN, not that anything changed.</p>
     */
    @Test
    public void typingInARestoredTabDoesNotRebuildIt() {
        workbench.open(DockInput.of(workbench.refFor(FILE)));
        for (int i = 0; i < 12; i++) frameAndPump();

        UIElement editor = contentOf(workbench.refFor(FILE));
        assertTrue(editor instanceof TextEditor);
        ((TextEditor) editor).setText("class Main { int x; }" + System.lineSeparator());
        for (int i = 0; i < 6; i++) frameAndPump();

        EditorService.Tab tab = workbench.editors().tabFor(EditorInput.of(Resource.of(FILE)));
        assertEquals("the edit made it dirty, so the signal fired", DocumentState.DIRTY, tab.state());
        assertSame("the very same editor, still holding the caret and the undo stack",
                editor, contentOf(workbench.refFor(FILE)));
    }

    /**
     * <b>A restored tab whose file is gone says so, and offers to try again.</b>
     *
     * <p>The half the first fix did not reach. A session record names files that were there when it was
     * written, and a deleted or renamed one comes back as a tab whose read fails — {@code editor()} is
     * null for ever, so the placeholder is what that tab shows, and nothing anywhere explains it. A
     * blank pane with no explanation is indistinguishable from the editor being broken, and was
     * reported as exactly that.</p>
     *
     * <p>The restore path is also where the failure is dropped: {@code openResource} attaches an
     * {@code onError} and the panel factory does not, because it only has somewhere to put the answer
     * once the panel exists. The banner is that somewhere.</p>
     */
    @Test
    public void aRestoredTabWhoseFileIsGoneClosesItself() {
        CgPath missing = CgPath.parse(PROJECT + ":nope.java");
        DockPanelRef ref = workbench.refFor(missing);
        workbench.open(DockInput.of(ref));
        frameAndPump();
        assertTrue("the tab is there to be dropped", inTheDock(ref));

        for (int i = 0; i < 12; i++) frameAndPump();

        assertFalse("a tab with no subject is not a problem to report", inTheDock(ref));
        assertNull("...and nothing is left holding the document",
                workbench.editors().tabFor(EditorInput.of(Resource.of(missing))));
    }

    /**
     * <b>...and a file that is still THERE and could not be read keeps its tab, and its banner.</b>
     *
     * <p>The distinction both references draw, and the reason the discriminator is the {@code FsError}
     * CODE rather than the fact of a failure. A deleted file is a tab with no subject; a file that
     * cannot be read is a fact about a file that still exists — no permission, a bad encoding, over the
     * cap — and closing the tab throws away both the fact and the {@code Retry} that can act on it.</p>
     */
    @Test
    public void aFileThatCannotBeReadKeepsItsTabAndSaysWhy() {
        CgPath refused = CgPath.parse(PROJECT + ":locked.java");
        files.seed(refused.toString(), "class Locked { }");
        // NOT a delete: the file is there, and the server refuses to serve it.
        refuseReadsOf(refused);

        DockPanelRef ref = workbench.refFor(refused);
        workbench.open(DockInput.of(ref));
        for (int i = 0; i < 12; i++) frameAndPump();

        EditorService.Tab tab = workbench.editors().tabFor(EditorInput.of(Resource.of(refused)));
        assertNotNull("the tab stays", tab);
        assertEquals(DocumentState.FAILED, tab.state());
        assertTrue("...and so does its panel", inTheDock(ref));

        List<Notification> banners = workbench.panels().bannersFor(ref);
        assertEquals("exactly one thing to say about it", 1, banners.size());
        assertTrue("...and it names the file", banners.get(0).getMessage().contains("locked.java"));
        assertTrue("...and offers a way out", banners.get(0).actions().stream()
                .anyMatch(action -> "Retry".equals(action.label())));
    }

    /** Makes the server refuse this path -- present, and not served. */
    private void refuseReadsOf(CgPath path) {
        refused.add(path.toString());
    }

    /** Whether the main dock still holds a panel for {@code ref}. */
    private boolean inTheDock(DockPanelRef ref) {
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            if (leaf.indexOf(ref) >= 0) return true;
        }
        return false;
    }

    /**
     * The counter-control: a tab that opened fine has nothing to say.
     *
     * <p>A banner provider written as "answer for every panel" would put an error bar over every file
     * in the editor, which is the failure mode a provider that cannot say no has.</p>
     */
    @Test
    public void aTabThatOpenedFineHasNoBanner() {
        workbench.open(DockInput.of(workbench.refFor(FILE)));
        for (int i = 0; i < 12; i++) frameAndPump();

        assertTrue("nothing to say about a file that opened",
                workbench.panels().bannersFor(workbench.refFor(FILE)).isEmpty());
    }

    /**
     * <b>A panel that is about no file at all costs nothing and breaks nothing.</b>
     *
     * <p>A tool window has no {@code path} state, and {@code Resource.parse("")} THROWS rather than
     * answering null — so a banner provider that parses first and asks questions afterwards takes down
     * the build of every panel in the dock, not its own. That is what happened: the whole workbench
     * stopped opening, from a provider written to put a message over one tab.</p>
     */
    @Test
    public void aPanelAboutNoFileGetsNoBannerAndDoesNotThrow() {
        assertTrue("a ref with no path state says nothing",
                workbench.panels().bannersFor(new DockPanelRef("workbench.problems")).isEmpty());
    }

    /**
     * ...and a provider that throws costs its own banner, never the panel.
     *
     * <p>A provider is contributed code running inside the dock's panel build. Without the isolation an
     * exception there costs the PANEL and every other panel in the same rebuild — a workbench where
     * nothing opens because something wanted to put a message over one tab.</p>
     */
    @Test
    public void aProviderThatThrowsDoesNotTakeThePanelDown() {
        workbench.panels().registerBanner(panel -> {
            throw new IllegalStateException("this provider is broken");
        });
        assertTrue("the broken one contributed nothing and the rest still answered",
                workbench.panels().bannersFor(workbench.refFor(FILE)).isEmpty());
    }

    /** What the dock has built for {@code ref}, reaching through the tab's content host. */
    private UIElement contentOf(DockPanelRef ref) {
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            var group = workbench.dock().groupFor(leaf);
            if (group == null) return null;
            return group.builtContentFor(ref);
        }
        return null;
    }
}
