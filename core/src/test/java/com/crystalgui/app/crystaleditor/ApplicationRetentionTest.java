package com.crystalgui.app.crystaleditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.lang.ref.WeakReference;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.dispose.Disposer;
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
    }

    /**
     * Leaves the process as this test found it, whatever the test did.
     *
     * <p>Not tidiness: an editor writes into registries this whole module shares, and the failure that
     * makes is another test's, in another class, that never mentioned an editor.</p>
     */
    @After
    public void closeWorkspace() {
        Notifications.resetForTesting();
        InspectorRegistry.resetForTesting();
        ProjectSourcesRegistry.resetForTesting();
        Disposer.resetForTesting();
        Protocols.resetForTesting();
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
            Disposer.dispose(new CrystalEditor(workspace));
        }

        Map<String, Integer> after = census();
        assertEquals(EDITORS + " editors were built and disposed and something kept them: "
                + drift(before, after), before, after);
    }

    /**
     * <b>...and the strong form: a disposed editor is garbage.</b>
     *
     * <p>The census can only see holders it knows the name of, and the last one found was reachable
     * through none of them: a heap walk from every static in {@code com.crystalgui} traced
     * {@code ContentProviders.onDidChange} → a {@code Workspace} → its {@code onDidGreet} → the
     * {@code WorkspaceDocuments} a workbench built → its {@code DocumentKinds} → a {@code DocumentKind}
     * whose model factory captures the workbench. Not one counter moved for that, and it kept the whole
     * editor tree.</p>
     *
     * <p>So this is the assertion that does not need to know what to look for. It is GC-dependent, which
     * is why it retries rather than gcs once, and why the census stays beside it: when this fails, the
     * census is what says whether it is a listener, a registry, or something with no name yet.</p>
     */
    @Test
    public void aDisposedEditorIsCollectable() {
        CrystalEditor editor = new CrystalEditor(workspace);
        WeakReference<CrystalEditor> watched = new WeakReference<>(editor);
        Disposer.dispose(editor);
        editor = null;

        for (int attempt = 0; attempt < 40 && watched.get() != null; attempt++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertNull("a disposed editor is still reachable from somewhere, so the whole tree behind it "
                + "-- workbench, dock, documents, tabs -- is still in the heap", watched.get());
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
        CrystalEditor editor = new CrystalEditor(workspace);
        try {
            Map<String, Integer> after = census();
            assertNotEquals("a live editor registered itself with nothing the census reads, so the "
                    + "retention assertion beside this one is vacuous", before, after);
        } finally {
            Disposer.dispose(editor);
        }
    }
}
