package com.crystalgui.app.crystaleditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.core.notify.Notifications;
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
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.widget.config.inspector.InspectorRegistry;

/**
 * <b>Four editors built and disposed leave the process where they found it.</b>
 *
 * <p>This is the test {@code HotExitIsWiredTest}'s javadoc says could not be written: <i>"four of them
 * in one worker exhausted the heap and shifted the computed colours ConfigKitTest asserts — sixteen
 * green tests in isolation, two failures in the suite"</i>. Both halves of that are answered here. The
 * heap is the subject rather than the obstacle, because a leak is what is being measured; and the
 * colours are untouched because the theme is installed from {@code connected()} and from
 * {@code loadPreferences}, neither of which a construct-and-dispose reaches — the retention chain is
 * wired in two <em>constructors</em>, which is exactly what makes it reachable without a window.</p>
 *
 * <h3>What it counts</h3>
 *
 * <p>Every process-wide holder an editor writes itself into, plus the per-connection ones on the
 * {@link Workspace}. The status bar left this census at W5 and its absence is the point: it is an
 * instance per workbench now, so it goes when the workbench does and there is no static to count. Dock banner providers were one of them and are deliberately absent: since W1 they
 * live on the workbench's own {@code DockPanelRegistry}, so their lifetime is structural and there is
 * no longer a static list to read — which is the better answer than an assertion about one.</p>
 *
 * <p>The workspace's own signals matter most, because a workspace outlives every workbench on it: a second
 * editor on the same wire inherits the first one's listeners for as long as the connection lasts.
 * {@code Disposer.liveCount()} covers the ownership tree, whose javadoc already says a leak assertion
 * reads it.</p>
 *
 * <p>The counters are read as a census rather than one assertion each, so a failure names every holder
 * that drifted and by how much — the useful output here is the shape of the leak, not the first line
 * of it.</p>
 *
 * <h3>The counter-control is load-bearing</h3>
 *
 * <p>A fixture that constructed nothing would satisfy the census perfectly, and so would a
 * {@code CrystalEditor} that failed to wire itself up. {@link #aLiveEditorIsVisibleInTheCensus} is what
 * separates "nothing leaked" from "nothing happened".</p>
 */
public class ApplicationRetentionTest {

    private static final String PROJECT = "scratch";

    private static final int EDITORS = 4;

    private UIDocument surface;
    private Desktop desktop;
    private ConfigStorage storage;
    private boolean animationsWere;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private Workspace workspace;

    /**
     * A whole workspace in this process — both halves over one transport.
     *
     * <p>A real server rather than a stub, because half the counters under test are on the client's own
     * {@code FileOperations} and {@code Presence}, and those only exist on a workspace that has a wire
     * to talk over.</p>
     */
    @Before
    public void openWorkspace() {
        Protocols.resetForTesting();

        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workspace = Workspace.of(clientEnd);

        // A REAL DESKTOP, because an application is launched onto one and its window is what a dispose
        // has to destroy -- and because the retention question is now partly the compositor's: a window
        // that survives its application is a whole editor tree still in the registry.
        // ANIMATIONS OFF, and it is what makes this test able to see a leak at all: with them on, a
        // destroyed window's taskbar entry collapses over several frames and the strip holds the frame
        // meanwhile -- and no test runs frames, so every disposed application would stay reachable
        // through the taskbar and the assertion below would fail for a reason that is not a leak.
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);

        surface = new UIDocument();
        desktop = Desktop.of(surface);
        storage = new InMemoryConfigStorage();
        CrystalEditor.install(desktop.applications());
    }

    /**
     * Leaves the process as this test found it, whatever the test did.
     *
     * <p>Not tidiness: an editor writes into registries this whole module shares, and the failure that
     * makes is another test's, in another class, that never mentioned an editor.</p>
     */
    @After
    public void closeWorkspace() {
        // NULL WHEN THE COLLECTABILITY TEST DROPPED IT ON PURPOSE.
        if (desktop != null) {
            for (Application running : desktop.applications().running()) running.dispose();
        }
        // AND THE THEME, because installing one MUTATES SHIPPED SHEETS. `WorkbenchSettings.apply`
        // substitutes the active theme's variables into `StyleSheet.DEFAULT` itself -- a process-wide
        // object -- so an application left running leaves every later test in this worker computing
        // colours from a themed user-agent sheet. `ConfigKitTest` is where that lands: two of its
        // colour assertions fail in the suite and pass in isolation, which is the shape this file's own
        // javadoc predicted before an application ever attached to a document.
        UiThemeManager.getInstance().resetForTesting();
        Notifications.resetForTesting();
        InspectorRegistry.resetForTesting();
        ProjectSourcesRegistry.resetForTesting();
        Disposer.resetForTesting();
        Protocols.resetForTesting();
        WindowAnimator.setEnabled(animationsWere);
    }

    /** Every holder the retention chain is visible in, named so a failure reads as a list. */
    private Map<String, Integer> census() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Disposer.liveCount()", Disposer.liveCount());
        counts.put("Notifications.onDidChange", Notifications.onDidChange.connectionCount());
        counts.put("Notifications.onDidChangeUnread", Notifications.onDidChangeUnread.connectionCount());
        counts.put("InspectorRegistry.onDidChangeSubject",
                InspectorRegistry.onDidChangeSubject.connectionCount());
        counts.put("InspectorRegistry sections", InspectorRegistry.all().size());
        counts.put("ProjectSourcesRegistry", ProjectSourcesRegistry.size());
        counts.put("Workspace.onDidReconnect", workspace.onDidReconnect.connectionCount());
        counts.put("Workspace.files().onDidRun", workspace.files().onDidRun.connectionCount());
        counts.put("Workspace.files().onDidFail", workspace.files().onDidFail.connectionCount());
        counts.put("Workspace.presence().onDidChange", workspace.presence().onDidChange.connectionCount());
        counts.put("applications running", desktop.applications().running().size());
        counts.put("desktop windows", desktop.registry().size());
        return counts;
    }

    private static String drift(Map<String, Integer> before, Map<String, Integer> after) {
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, Integer> each : before.entrySet()) {
            int then = each.getValue();
            int now = after.get(each.getKey());
            if (now != then) moved.add(each.getKey() + ": " + then + " -> " + now);
        }
        return String.join(", ", moved);
    }

    @Test
    public void fourEditorsBuiltAndDisposedLeaveTheProcessWhereTheyFoundIt() {
        Map<String, Integer> before = census();

        for (int i = 0; i < EDITORS; i++) {
            launch().dispose();
        }

        Map<String, Integer> after = census();
        assertEquals(EDITORS + " editors were built and disposed and something kept them: "
                + drift(before, after), before, after);
    }

    /**
     * <b>...and the strong form: nothing that outlives the screen still holds a disposed editor.</b>
     *
     * <p>The census can only see holders it knows the name of, and the last one found was reachable
     * through none of them: a walk from every static in the jar traced
     * {@code ContentProviders.onDidChange} &rarr; a {@code Workspace} &rarr; its {@code onDidGreet}
     * &rarr; the {@code WorkspaceDocuments} a workbench built &rarr; its {@code DocumentKinds} &rarr; a
     * {@code DocumentKind} whose model factory captures the workbench. Not one counter moved for that,
     * and it kept the whole editor tree.</p>
     *
     * <h3>A PATH, not a {@code WeakReference}</h3>
     *
     * <p>Because the failure message is the entire value of this test. A weak handle that refuses to
     * clear can only ever say "still reachable", which is where the last four rounds of this went; the
     * walk says <em>through what</em>, and naming the field is what turned one red assertion into five
     * separate fixes — a detach that re-marked the subtree it had just released, a document-level
     * provider nothing withdrew, a rail's window-registry commands, a rail's focus subscription, and a
     * project listing asked before the server had greeted. It is also not GC-dependent, so it neither
     * flakes nor passes by luck.</p>
     *
     * <p>What it cannot see is stated on {@link ReferencePaths}: a weak edge is skipped deliberately, and
     * anything held only inside a JDK structure with no public iteration is invisible. So "no path" means
     * <b>nothing in this jar's statics, and nothing that outlives the screen, is holding it</b> — which is
     * the leak class that matters. Whatever is reachable only through the {@code UIDocument} dies with
     * the screen, and one of those is beyond an application's reach anyway:
     * {@code EventListenerGroup} has no way to take a listener off again — its own callers say so —
     * so every widget that has ever attached one to the root is held by that root for the document's
     * whole life.</p>
     */
    @Test
    public void nothingOutlivingTheScreenHoldsADisposedEditor() {
        Application editor = launch();
        editor.dispose();

        Map<Object, String> outlivesTheScreen = new LinkedHashMap<>();
        outlivesTheScreen.put(workspace, "the workspace");
        outlivesTheScreen.put(clientEnd, "the connection");
        String path = ReferencePaths.find(editor, outlivesTheScreen);

        assertNull("a disposed editor is still held by something that outlives its screen, so the whole "
                + "tree behind it -- workbench, dock, documents, tabs -- is still in "
                + "the heap. The chain: " + path, path);
    }

    /**
     * The counter-control for the walk itself.
     *
     * <p>A search that could not find anything would report "no path" for a live editor too, and this
     * whole file would be measuring a broken instrument. It is the same shape as the census's own
     * counter-control one method down.</p>
     */
    @Test
    public void theWalkCanSeeALiveEditor() {
        Application editor = launch();
        try {
            assertNotNull("the reference walk found no path to an editor that is plainly still running, "
                    + "so the assertion beside it is vacuous",
                    ReferencePaths.find(editor, Map.of(desktop, "the desktop")));
        } finally {
            editor.dispose();
        }
    }

    /**
     * The counter-control: the census can see an editor that is still there.
     *
     * <p>Without this, a fixture that constructed nothing — or an editor that silently failed to
     * register itself with anything — would pass the assertion above.</p>
     */
    @Test
    public void aLiveEditorIsVisibleInTheCensus() {
        Map<String, Integer> before = census();
        Application editor = launch();
        try {
            Map<String, Integer> after = census();
            assertNotEquals("a live editor registered itself with nothing the census reads, so the "
                    + "retention assertion beside this one is vacuous", before, after);
        } finally {
            editor.dispose();
        }
    }

    /** One launch of the shipped manifest, refused loudly rather than answering null into an NPE. */
    private Application launch() {
        Application launched = desktop.applications().launch(CrystalEditor.KIND, workspace, storage);
        if (launched == null) throw new AssertionError("the editor manifest refused to launch");
        return launched;
    }
}
