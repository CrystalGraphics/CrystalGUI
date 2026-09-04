package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();

        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed(FILE.toString(), "class Main { }\n");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

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
     * A tab whose read FAILED keeps whatever the dock built rather than rebuilding for ever.
     *
     * <p>{@code editor()} answers null for a failed tab, so the guard returns before it can ask the
     * dock — which is what stops a file that cannot be read from asking for a rebuild on every
     * announcement it makes.</p>
     */
    @Test
    public void aFailedReadDoesNotRebuildForever() {
        DockPanelRef missing = workbench.refFor(CgPath.parse(PROJECT + ":nope.java"));
        workbench.open(DockInput.of(missing));
        for (int i = 0; i < 12; i++) frameAndPump();

        UIElement shown = contentOf(missing);
        for (int i = 0; i < 6; i++) frameAndPump();
        assertSame("nothing rebuilt it", shown, contentOf(missing));
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
