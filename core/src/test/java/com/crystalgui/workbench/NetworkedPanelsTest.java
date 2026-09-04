package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.crystalgui.fs.server.WorkspaceActor;
import org.jetbrains.annotations.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.UiType;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;

/**
 * <b>A server's window as a workbench citizen</b> — {@code plan_ui_rewrite.md} 7.1, and K5.
 *
 * <p>A panel a server opens can be an editor tab beside the files or a tool window on a rail, and once
 * it is, everything the dock does applies to it: it splits, it drags, it tears out, and it is written
 * into the session. What it must never do is lose its session on the wire while any of that happens —
 * a tab is an element moving between frames, and the window is a thing on a connection.</p>
 *
 * <h3>Why a restore has to ASK</h3>
 *
 * <p>A described tree is the server's and cannot be rebuilt from a layout file: the layout holds an
 * identity and never content. So a restored tab shows a placeholder and asks for its window back by
 * key, and a refusal drops the tab — the machine was broken, the block was mined, the player is
 * somewhere else, and a placeholder with no explanation is the shape that gets reported as the editor
 * being broken.</p>
 */
public class NetworkedPanelsTest extends UiDocumentTestBase {

    private Workbench workbench;
    private NetworkedPanels panels;

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    /** Where a {@link Presentation#WINDOW} would have gone. Counted, never built. */
    private final List<ClientWindowContext> onDesktop = new ArrayList<>();

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        WindowProtocol.register();
        ServerWindows.resetOpenableForTesting();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject("demo", "Demo", Paths.get("/srv/demo")))),
                new InMemoryFileSystem().seed("demo:a.txt", "a"),
                WorkspacePermission.ALLOW_ALL);
        new WorkspaceBinding<>(service, new WatchHub(service),
                WorkspaceActor.LOCAL, "player", PlainOps.INSTANCE)
                .installOn(serverEnd);

        workbench = new Workbench(Workspace.of(clientEnd));
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();

        panels = workbench.windowMount(new DesktopMount());
        ClientWindows.of(clientEnd).setMount(panels);
    }

    @After
    public void closeWorkbench() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        ServerWindows.resetOpenableForTesting();
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
        // TWO FRAMES, because the workbench defers: a tool window asked for before its region exists is
        // remembered and replayed on the next tick, which is the mechanism this exercises rather than a
        // fixture convenience.
        frame();
        frame();
    }

    /** The mount a host with only a desktop would install. Here it only counts. */
    private final class DesktopMount implements WindowMount {
        @Override
        public MountedWindow mount(ClientWindowContext context) {
            onDesktop.add(context);
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

    /** Every ref in the main dock, whatever leaf it is in. */
    private List<DockPanelRef> refs() {
        List<DockPanelRef> out = new ArrayList<>();
        for (DockLeaf leaf : workbench.dock().layout().leaves()) out.addAll(leaf.panels());
        return out;
    }

    private boolean docked(String typeId) {
        return refs().stream().anyMatch(ref -> ref.typeId().equals(typeId));
    }

    // ── Routing ─────────────────────────────────────────────────────────────────────────────────

    /** <b>A server panel opens as an editor tab.</b> */
    @Test
    public void aServerPanelOpensAsAnEditorTab() {
        ServerWindows.of(serverEnd).open(NetPanel.TYPE, "one", Presentation.EDITOR_TAB);
        settle();

        assertTrue("a tab in the dock", docked(NetPanel.TYPE.id()));
        assertTrue("and it is live", panels.isLive(NetPanel.TYPE.id(), "one"));
        assertTrue("nothing went to the desktop", onDesktop.isEmpty());
    }

    /** <b>A server panel opens as a tool window.</b> */
    @Test
    public void aServerPanelOpensAsAToolWindow() {
        ServerWindows.of(serverEnd).open(NetPanel.TYPE, "one", Presentation.toolWindow("auxiliary"));
        settle();

        assertTrue("open on a rail", workbench.toolWindowManager().isPanelOpen(NetPanel.TYPE.id()));
        assertTrue("nothing went to the desktop", onDesktop.isEmpty());
    }

    /** And a plain window still goes to the desktop, through the mount this one wraps. */
    @Test
    public void aPlainWindowStillGoesToTheDesktop() {
        ServerWindows.of(serverEnd).open(NetPanel.TYPE, "one");
        settle();

        assertEquals(1, onDesktop.size());
        assertFalse("and not into the dock", docked(NetPanel.TYPE.id()));
    }

    // ── The tab is the dock's ───────────────────────────────────────────────────────────────────

    /**
     * <b>Tearing a networked tab out keeps its session.</b>
     *
     * <p>A tear-out moves an <em>element</em> between frames. The window it belongs to is a thing on a
     * connection, so nothing about it changes — which is what makes the dock's whole vocabulary free
     * here rather than something each networked panel would have to re-implement.</p>
     */
    @Test
    public void tearingOutANetworkedTabKeepsItsSession() {
        ServerWindow<NetPanel> served = ServerWindows.of(serverEnd)
                .open(NetPanel.TYPE, "one", Presentation.EDITOR_TAB);
        settle();
        UIElement tree = panelRoot();
        assertNotNull(tree);

        DockPanelRef ref = refs().stream()
                .filter(r -> r.typeId().equals(NetPanel.TYPE.id())).findFirst().orElseThrow();
        DockLeaf from = workbench.dock().layout().leaves().stream()
                .filter(leaf -> leaf.indexOf(ref) >= 0).findFirst().orElseThrow();

        // A LEAF OF ITS OWN, which is the structural half of a tear-out: DockArea.tearOutToWindow moves
        // the panel into a new leaf and hands that leaf to a DockWindow. A layout will not give up its
        // last leaf, so a tab that is alone has to be split out before it can be taken out.
        DockLeaf into = workbench.dock().layout()
                .drop(from, DockDropZone.SPLIT_RIGHT, new DockLeaf());
        assertTrue("the panel moved to its own leaf",
                workbench.dock().layout().movePanel(ref, into, 0));
        workbench.dock().layout().tearOut(into);
        workbench.dock().requestRebuild();
        settle();

        assertFalse("out of the main dock", docked(NetPanel.TYPE.id()));

        assertTrue("the window is still open on the wire", served.isOpen());
        assertSame("and the same tree, unrebuilt", tree, panelRoot());
    }

    /** The tree the client built for the one open networked window. */
    @Nullable
    private UIElement panelRoot() {
        for (ClientWindowContext context : ClientWindows.of(clientEnd).windows()) {
            if (context.type().equals(NetPanel.TYPE.id())) return context.root();
        }
        return null;
    }

    // ── Restore ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A networked tab is restored by key on the next connection.</b>
     *
     * <p>The manifest is what makes it possible at all: a ref names a type id, and on a fresh launch
     * nothing has registered a descriptor for it, so the dock would drop the ref before anything could
     * ask for the window back.</p>
     */
    @Test
    public void aNetworkedTabIsRestoredByKeyOnTheNextConnection() {
        ServerWindows.openable(NetPanel.TYPE, (viewer, args) -> args.getString("key", ""),
                Presentation.EDITOR_TAB);

        // A layout saved by a previous session, plus the manifest that went with it.
        panels.restoreManifest(List.of(new NetworkedPanels.Entry(
                NetPanel.TYPE.id(), "Machine", Presentation.EDITOR_TAB.encode())));
        workbench.open(DockInput.of(
                NetworkedPanels.refFor(NetPanel.TYPE.id(), "one", "Machine")));
        frame();

        assertTrue("the tab is there before anything is connected", docked(NetPanel.TYPE.id()));
        settle();

        assertTrue("...and the server was asked, and answered",
                panels.isLive(NetPanel.TYPE.id(), "one"));
        assertTrue("...and the tab is still there", docked(NetPanel.TYPE.id()));
    }

    /**
     * <b>A refused restore drops the tab.</b>
     *
     * <p>A tab that can never be filled is worse than one that is gone: nothing on screen says why, and
     * it reads as the editor being broken rather than as the machine having been mined.</p>
     */
    @Test
    public void aRefusedRestoreDropsTheTab() {
        ServerWindows.openable(NetPanel.TYPE, (viewer, args) -> null, Presentation.EDITOR_TAB);

        panels.restoreManifest(List.of(new NetworkedPanels.Entry(
                NetPanel.TYPE.id(), "Machine", Presentation.EDITOR_TAB.encode())));
        workbench.open(DockInput.of(
                NetworkedPanels.refFor(NetPanel.TYPE.id(), "one", "Machine")));
        frame();
        assertTrue("the tab exists to be dropped", docked(NetPanel.TYPE.id()));

        settle();

        assertFalse("refused, so gone", docked(NetPanel.TYPE.id()));
        assertFalse(panels.isLive(NetPanel.TYPE.id(), "one"));
    }

    /** The manifest survives a save and a read, which is what a restore stands on. */
    @Test
    public void theManifestRecordsWhatWasSeen() {
        ServerWindows.of(serverEnd).open(NetPanel.TYPE, "one", Presentation.EDITOR_TAB);
        settle();

        List<NetworkedPanels.Entry> manifest = panels.manifest();
        assertEquals(1, manifest.size());
        assertEquals(NetPanel.TYPE.id(), manifest.get(0).typeId());
        assertEquals(Presentation.EDITOR_TAB.encode(), manifest.get(0).presentation());
    }

    /** A server closing the window takes its tab with it. */
    @Test
    public void aServerCloseTakesTheTab() {
        ServerWindow<NetPanel> served = ServerWindows.of(serverEnd)
                .open(NetPanel.TYPE, "one", Presentation.EDITOR_TAB);
        settle();
        assertTrue(docked(NetPanel.TYPE.id()));

        served.close("done");
        settle();

        assertFalse("the tab went with the window", docked(NetPanel.TYPE.id()));
        assertFalse(panels.isLive(NetPanel.TYPE.id(), "one"));
    }

    /** A panel with a real widget in it, keyed so a restore has something to ask for. */
    public static class NetPanel extends UIElement implements Networked<String> {

        public static final Name NAME = Name.of("netpanel");

        public static final UiType<NetPanel, String> TYPE = UiType.of("test:net", NetPanel::new);

        public Button press = new Button("press");

        public NetPanel() {
            super(NAME);
        }

        @Override
        public void build(String model) {
            append(press);
        }

        @Override
        public void serve(String model, ServerScope io) {
        }

        @Override
        public String title(String model) {
            return "Machine";
        }

        @Nullable
        @Override
        public String key(String model) {
            return model;
        }
    }
}
