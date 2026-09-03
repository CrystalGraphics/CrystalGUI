package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.ServerWorkspace;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.UiType;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>Where a server would like its window to appear</b>, and what a host does with that.
 *
 * <p>A hint, never an instruction. The two properties that make it safe are here: it survives the wire
 * unchanged, and a host that cannot honour it opens the window anyway. Where a workbench actually
 * <em>puts</em> one is {@code NetworkedPanelsTest}'s subject; this is the wire and the fallback.</p>
 *
 * <p>Plus the other half of 7.1 — that a panel reads files through the fs protocol rather than
 * re-shipping a listing through the UI mirror.</p>
 */
public class PresentationTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    /**
     * A SECOND viewer, on its own wire — built <b>only by the tests that need one</b>.
     *
     * <p>Not in {@code setUp}, and that is not tidiness. {@code ClientWindows.CLIENT} is a single static:
     * a client is one process talking to one server, and {@code requestOpen} takes no connection because
     * there is only one it could mean. Opening a second client connection in the fixture re-points it, so
     * every {@code requestOpen} in this file would ask down the wrong wire — which is a property of the
     * fixture and not of anything being tested.</p>
     */
    private InMemoryTransport<Object>[] linkB;
    private ProtocolConnection<Object> serverEndB;
    private ProtocolConnection<Object> clientEndB;

    /** Every context the second client's mount was handed. */
    private final List<ClientWindowContext> mountedB = new ArrayList<>();

    /** Every context the mount was handed, in order. */
    private final List<ClientWindowContext> mounted = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        WindowProtocol.register();
        ServerWindows.resetOpenableForTesting();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        ClientWindows.of(clientEnd).setMount(new RecordingMount(mounted));
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        ServerWindows.resetOpenableForTesting();
    }

    /** Builds the second viewer's wire. @see #linkB */
    private void secondViewer() {
        linkB = InMemoryTransport.pair();
        serverEndB = Protocols.open(linkB[0], PlainOps.INSTANCE, () -> { }, "second");
        clientEndB = Protocols.open(linkB[1], PlainOps.INSTANCE, () -> { }, null);
        ClientWindows.of(clientEndB).setMount(new RecordingMount(mountedB));
    }

    private void settle() {
        for (int i = 0; i < 10; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
            if (linkB == null) continue;
            linkB[0].deliver();
            linkB[1].deliver();
            serverEndB.tick();
            clientEndB.tick();
        }
    }

    /** Records what it was given and does nothing with it — a host with only a desktop. */
    private static final class RecordingMount implements WindowMount {

        private final List<ClientWindowContext> into;

        RecordingMount(List<ClientWindowContext> into) {
            this.into = into;
        }

        @Override
        public MountedWindow mount(ClientWindowContext context) {
            into.add(context);
            return new MountedWindow() {
                @Override
                public void closedByServer(String reason) {
                }

                @Override
                public void focus() {
                }

                @Override
                public void contentReplaced(UIElement newRoot) {
                }
            };
        }
    }

    // ── The wire ────────────────────────────────────────────────────────────────────────────────

    /** A placement the server names arrives at the client saying the same thing. */
    @Test
    public void aPlacementSurvivesTheWire() {
        ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null, Presentation.EDITOR_TAB);
        settle();

        assertEquals(1, mounted.size());
        assertEquals(Presentation.EDITOR_TAB, mounted.get(0).presentation());
    }

    /** A tool window carries which rail it wants. */
    @Test
    public void aToolWindowCarriesItsRegion() {
        ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null, Presentation.toolWindow("auxiliary"));
        settle();

        Presentation where = mounted.get(0).presentation();
        assertEquals(Presentation.Kind.TOOL_WINDOW, where.kind());
        assertEquals("auxiliary", where.region());
    }

    /**
     * <b>A host with no workbench mounts the window regardless.</b>
     *
     * <p>The property the word "hint" is doing all its work for. A mount that refused a placement it
     * could not honour would leave a player who pressed a key with nothing at all — and "it opened
     * somewhere else" is a far better failure than "it did not open".</p>
     */
    @Test
    public void aHostWithNoWorkbenchMountsAWindowRegardless() {
        ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null, Presentation.EDITOR_TAB);
        settle();

        assertEquals("mounted, on a host that has nowhere to put a tab", 1, mounted.size());
        assertNotNull(mounted.get(0).root());
    }

    /**
     * A placement this client does not recognise reads as a desktop window.
     *
     * <p>The counter-control for the row above, one version skew earlier: refusing to parse a placement
     * is refusing the window, which is the failure the whole design avoids.</p>
     */
    @Test
    public void anUnknownPlacementReadsAsAWindow() {
        assertEquals(Presentation.WINDOW, Presentation.parse("hologram"));
        assertEquals(Presentation.WINDOW, Presentation.parse(null));
        assertEquals(Presentation.WINDOW, Presentation.parse(""));
        // ...and a tool window with no region named is not a tool window at all.
        assertEquals(Presentation.WINDOW, Presentation.parse("tool:"));
    }

    /** A server that names none opens a desktop window, exactly as every window did before this. */
    @Test
    public void aServerThatNamesNoPlacementOpensAWindow() {
        ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null);
        settle();

        assertEquals(Presentation.WINDOW, mounted.get(0).presentation());
    }

    /** Every form round trips through its own encoding. */
    @Test
    public void everyPlacementRoundTrips() {
        for (Presentation form : List.of(Presentation.WINDOW, Presentation.EDITOR_TAB,
                Presentation.toolWindow("sidebar"), Presentation.toolWindow("panel"))) {
            assertEquals(form, Presentation.parse(form.encode()));
        }
    }

    /**
     * A client-driven open lands where the <b>declaration</b> says, not where the client asked.
     *
     * <p>Where a panel belongs is the mod's statement about its own UI. A client that could name a
     * placement could ask for a tool window to open as an editor tab — and a restore, which is exactly
     * this path, holds no memory of how the window was presented the first time.</p>
     */
    @Test
    public void aClientDrivenOpenTakesTheDeclaredPlacement() {
        ServerWindows.openable(TinyPanel.TYPE, (viewer, args) -> "granted",
                Presentation.toolWindow("panel"));

        List<Boolean> answers = new ArrayList<>();
        ClientWindows.requestOpen(TinyPanel.TYPE.id(), new StateMap<>(PlainOps.INSTANCE), answers::add);
        settle();

        assertEquals(List.of(true), answers);
        assertEquals(1, mounted.size());
        assertEquals(Presentation.toolWindow("panel"), mounted.get(0).presentation());
    }

    /** A refusal is an answer, and no window arrives. */
    @Test
    public void aRefusedRequestOpensNothing() {
        ServerWindows.openable(TinyPanel.TYPE, (viewer, args) -> null, Presentation.EDITOR_TAB);

        List<Boolean> answers = new ArrayList<>();
        ClientWindows.requestOpen(TinyPanel.TYPE.id(), null, answers::add);
        settle();

        assertEquals(List.of(false), answers);
        assertTrue("nothing was mounted", mounted.isEmpty());
    }

    // ── Two viewers ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A placement is the WINDOW's, so two viewers see the same one.</b>
     *
     * <p>It rides {@code ui/openWindow}, which is built once and kept — so a viewer joining an hour
     * later is sent exactly what the first one saw. A placement resolved per viewer would be a window
     * that is an editor tab for one person and a floating window for another, which is not a thing a
     * server ever meant to say.</p>
     */
    @Test
    public void bothViewersSeeTheSamePlacement() {
        secondViewer();
        ServerWindow<TinyPanel> window =
                ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null, Presentation.EDITOR_TAB);
        window.session().addViewer(serverEndB);
        settle();

        assertEquals(2, window.session().viewerCount());
        assertEquals(1, mounted.size());
        assertEquals(1, mountedB.size());
        assertEquals(Presentation.EDITOR_TAB, mounted.get(0).presentation());
        assertEquals("the second viewer was told the same thing, not asked",
                Presentation.EDITOR_TAB, mountedB.get(0).presentation());
    }

    /**
     * <b>A panel's workspace is its OWNER's, whoever is watching.</b>
     *
     * <p>A window has one owner and may have many viewers, and only the owner's connection carries the
     * binding a panel acts through. Resolving per viewer would make a write's actor depend on who
     * happened to be looking — and with two viewers there is no answer to "which one" that is not a
     * guess. What a per-viewer answer is genuinely for is an EVENT, which carries the viewer that sent
     * it; a panel's own reads and writes are the window's.</p>
     */
    @Test
    public void aPanelsWorkspaceIsItsOwnersWhoeverIsWatching() {
        secondViewer();
        WorkspaceService owners = workspace();
        new WorkspaceBinding<>(owners, new WatchHub(owners), WorkspaceActor.LOCAL, "player",
                PlainOps.INSTANCE).installOn(serverEnd);
        // A DIFFERENT workspace on the second viewer's wire, so "the owner's" is provable rather than
        // merely plausible: with one service on both, either answer looks correct.
        WorkspaceService others = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject("other", "Other", Paths.get("/srv/other")))),
                new InMemoryFileSystem().seed("other:z.txt", "z"), WorkspacePermission.ALLOW_ALL);
        new WorkspaceBinding<>(others, new WatchHub(others), WorkspaceActor.LOCAL, "second",
                PlainOps.INSTANCE).installOn(serverEndB);

        List<String> projects = new ArrayList<>();
        TinyPanel.onServe = io -> {
            ServerWorkspace fs = io.workspace();
            assertNotNull(fs);
            for (ProjectInfo project : fs.projects()) projects.add(project.id());
        };
        try {
            ServerWindow<TinyPanel> window = ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null);
            window.session().addViewer(serverEndB);
            settle();
        } finally {
            TinyPanel.onServe = null;
        }

        assertEquals("the owner's workspace, not the second viewer's", List.of("demo"), projects);
    }

    // ── The workspace through the scope ─────────────────────────────────────────────────────────

    /**
     * <b>A panel lists files through its scope, not through the mirror.</b>
     *
     * <p>Shipping a listing as described elements makes a directory of ten thousand files into ten
     * thousand elements, re-sent whenever anything in it changes — and the workspace already has
     * watches, etags, chunked reads and a permission model that a hand-rolled listing does not.</p>
     */
    @Test
    public void aPanelListsFilesThroughItsScopeNotTheMirror() {
        WorkspaceService service = workspace();
        WatchHub hub = new WatchHub(service);
        new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, "player", PlainOps.INSTANCE)
                .installOn(serverEnd);

        List<String> listed = new ArrayList<>();
        TinyPanel.onServe = io -> {
            ServerWorkspace fs = io.workspace();
            assertNotNull("the panel's own connection carries a workspace", fs);
            for (CgFileEntry entry : fs.list(CgPath.ofProject("demo"))) listed.add(entry.name());
        };
        try {
            ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null, Presentation.EDITOR_TAB);
            settle();
        } finally {
            TinyPanel.onServe = null;
        }

        assertEquals("read through the fs, not described", List.of("a.txt", "b.txt"), listed);
        // THE COUNTER-CONTROL: the tree the client rebuilt holds the panel and nothing else. A listing
        // shipped through the mirror would be two more described children per file.
        assertEquals(0, mounted.get(0).root().describedChildren().size());
    }

    /** A connection carrying no workspace answers null rather than pretending. */
    @Test
    public void aConnectionWithNoWorkspaceAnswersNull() {
        List<ServerWorkspace> seen = new ArrayList<>();
        TinyPanel.onServe = io -> seen.add(io.workspace());
        try {
            ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null);
            settle();
        } finally {
            TinyPanel.onServe = null;
        }

        assertEquals(1, seen.size());
        assertNull("a server that serves UI and no files binds none", seen.get(0));
    }

    /** The bound view acts as the peer it was bound for, never as whoever asks. */
    @Test
    public void theBoundWorkspaceCarriesItsActor() {
        WorkspaceService service = workspace();
        WorkspaceBinding<Object> binding = new WorkspaceBinding<>(service, new WatchHub(service),
                WorkspaceActor.LOCAL, "player", PlainOps.INSTANCE);
        ServerWorkspace fs = binding.workspace();

        assertSame(WorkspaceActor.LOCAL, fs.actor());
        assertEquals(2, fs.list(CgPath.ofProject("demo")).size());
    }

    /** The client half is the connection's workspace, shared by every window on it. */
    @Test
    public void twoPanelsOnOneClientShareOneWorkspace() {
        ServerWindows.of(serverEnd).open(TinyPanel.TYPE, null);
        settle();
        ClientWindowContext first = mounted.get(0);

        assertSame(Workspace.of(clientEnd), Workspace.of(first.connection()));
    }

    private static WorkspaceService workspace() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("demo:a.txt", "a")
                .seed("demo:b.txt", "b");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("demo", "Demo", Paths.get("/srv/demo"))));
        return new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
    }

    /**
     * The smallest thing that is a window: no widgets, no model, and a hook a test can borrow.
     *
     * <p>The hook is static because {@code UiType.build} constructs the panel — a test cannot hand one
     * in, which is the whole point of a description that names its own class.</p>
     */
    public static class TinyPanel extends UIElement implements Networked<String> {

        public static final Name NAME = Name.of("tinypanel");

        public static final UiType<TinyPanel, String> TYPE = UiType.of("test:tiny", TinyPanel::new);

        /** Run from {@link #serve}, so a test can look at the scope it is handed. */
        @Nullable
        static Consumer<ServerScope> onServe;

        public TinyPanel() {
            super(NAME);
        }

        @Override
        public void build(String model) {
        }

        @Override
        public void serve(String model, ServerScope io) {
            if (onServe != null) onServe.accept(io);
        }

        @Override
        public String title(String model) {
            return "Tiny";
        }
    }
}
