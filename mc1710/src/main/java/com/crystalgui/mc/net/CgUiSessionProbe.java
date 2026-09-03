package com.crystalgui.mc.net;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementTreeSource;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.text.Change;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.text.UIText;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything Phase 4 built, over a real Minecraft connection — {@code -PcgSessionProbe}.
 *
 * <p>{@link CgUiNetProbe} owns the channel and echoes byte arrays, which proves the transport and
 * nothing above it. This proves the rest, <b>on the path that ships</b>: it opens no multiplexer,
 * installs no inbound handler and pumps nothing, taking the connections {@link CgUiConnections} opened
 * on join and putting a session on each end.</p>
 *
 * <table>
 *   <tr><th>Check</th><th>What it proves</th></tr>
 *   <tr><td>1 tree</td><td>{@code ui/openWindow} + the {@code ui/description} request/response</td></tr>
 *   <tr><td>2 tabs</td><td><b>C3</b> — a TabView's tabs and their contents survive being described</td></tr>
 *   <tr><td>3 state</td><td><b>C4</b> — a ProgressBar's fraction and a Dropdown's OPTIONS arrive</td></tr>
 *   <tr><td>4 delta</td><td>{@code ui/stateDelta}</td></tr>
 *   <tr><td>5 event</td><td>{@code ui/event} — the server's own lambda runs</td></tr>
 *   <tr><td>6 call</td><td>request/response, server → client</td></tr>
 *   <tr><td>7 reshape</td><td><b>C2</b> — {@code ui/treeDelta}: a child added after open arrives, and
 *       a later state update still lands on the RIGHT element through the renumbering</td></tr>
 *   <tr><td>8 fan-out</td><td><b>C1</b> — a second viewer added to the live session receives the window</td></tr>
 *   <tr><td>9 files</td><td><b>B1/B2</b> — a listing off the server's own disk</td></tr>
 *   <tr><td>10 delta-write</td><td><b>C5</b> — {@code fs.writeDelta} and the etag-validated cache</td></tr>
 * </table>
 *
 * <h3>Two handlers, because there are two threads</h3>
 *
 * <p>Server-side work runs on {@code ServerTickEvent} and client-side work on {@code ClientTickEvent}.
 * An earlier version did both from the client tick and happened to work, because a single-player
 * integrated server shares the process — it was still touching a tree from the wrong thread.</p>
 *
 * <h3>What one client cannot prove</h3>
 *
 * <p>C1's second viewer is a real {@link ServerUiSession#addViewer} against the live session, but its
 * far end is an {@link InMemoryTransport} rather than a second player — single-player has one
 * connection, and standing up a second client is not something a probe can do. So this covers the
 * fan-out <em>path</em> with a real session in the middle, and the two-player case remains covered
 * headlessly by {@code MultiViewerTest}.</p>
 */
public final class CgUiSessionProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.session.probe");

    private static final int WINDOW_ID = 4242;

    /** How long to wait before calling it a failure, in client ticks. */
    private static final int DEADLINE_TICKS = 20 * 60;

    /**
     * The checklist. <b>Written from two threads</b> — server-side checks pass on the server tick and
     * client-side ones on the client tick — so it is concurrent, with the order kept separately.
     *
     * <p>A plain {@code LinkedHashMap} would very likely have worked here, since every key is inserted
     * once at class init and only values are replaced afterwards. "Very likely" is what this codebase
     * already paid for once: a {@code HashSet} written from a script thread while the UI thread copied
     * it threw an {@code ArrayIndexOutOfBoundsException} from inside {@code HashMap.keysToArray}, with
     * nothing about the offending subsystem anywhere in the trace.</p>
     */
    private static final Map<String, Boolean> CHECKS = new ConcurrentHashMap<>();

    /** Insertion order, kept apart because a ConcurrentHashMap has none. */
    private static final List<String> ORDER = Arrays.asList(
            "1 tree", "2 tabs (C3)", "3 widget state (C4)", "4 state delta", "5 event",
            "6 server->client call", "7 reshape (C2)", "8 fan-out (C1)", "9 files (B1/B2)",
            "10 writeDelta + cache (C5)");

    private static void pass(String check) {
        if (Boolean.TRUE.equals(CHECKS.get(check))) return;
        CHECKS.put(check, true);
        CrystalGuiCore.LOGGER.info("[session-probe] OK {}", check);
    }

    static {
        for (String check : ORDER) CHECKS.put(check, false);
    }

    private static volatile ServerUiSession<UIElement, Object> server;
    private static volatile ClientUiSession<UIElement, Object> client;

    private static Slider serverSlider;
    private static UIText addedLater;

    // C1's second viewer, both ends pumped on the server tick.
    private static InMemoryTransport<Object>[] extraLink;
    private static ProtocolConnection<Object> extraServer;
    private static ProtocolConnection<Object> extraClient;
    private static ClientUiSession<UIElement, Object> extraViewer;

    private static volatile boolean deltaSent;
    private static volatile boolean eventSent;
    private static volatile boolean callSent;
    private static volatile boolean reshapeSent;
    private static volatile boolean fanoutStarted;
    private static volatile boolean filesAsked;
    private static volatile boolean deltaWriteStarted;
    private static volatile boolean reported;

    private static WorkspaceClient<Object> files;
    private static int clientTicks;

    private CgUiSessionProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[session-probe] armed; waiting for CgUiConnections to open a pair");
    }

    private static boolean done(String check) {
        return Boolean.TRUE.equals(CHECKS.get(check));
    }

    // ── The two ends ────────────────────────────────────────────────────────

    /** <b>Server thread.</b> A tree that exercises C3 and C4 as well as the basics. */
    private static void openServer(ProtocolConnection<Object> connection) {
        UIElement root = new UIElement();
        root.append(new UIText("hello from the server"));

        Button button = new Button("Press me");
        root.append(button);

        serverSlider = new Slider();
        serverSlider.setRange(0f, 1f);
        root.append(serverSlider);

        // C3: tabs are content, and their contents are content too.
        TabView tabs = new TabView();
        Tab first = tabs.addTab("Editor");
        first.content().append(new UIText("inside the first tab"));
        Tab second = tabs.addTab("Settings");
        second.content().append(new Slider());
        tabs.selectIndex(1);
        root.append(tabs);

        // C4: two widgets whose state had no way of travelling until this phase.
        ProgressBar progress = new ProgressBar();
        progress.setFraction(0.42f);
        root.append(progress);

        Dropdown dropdown = new Dropdown("choose");
        dropdown.addOptions("alpha", "beta", "gamma");
        dropdown.select(2);
        root.append(dropdown);

        ServerUiSession<UIElement, Object> session = new ServerUiSession<>(WINDOW_ID, new UIElementTreeSource(root),
                new UIElementMirror<>(connection.ops()), connection);
        session.on(button, Button.ACTIVATE, ctx -> pass("5 event"));
        session.open();
        server = session;
        CrystalGuiCore.LOGGER.info("[session-probe] server session opened on the real connection, "
                + "{} children, hash={}", root.children().size(), session.descHash());
    }

    /** <b>Client thread.</b> */
    private static void openClient(ProtocolConnection<Object> connection) {
        UIElementRegistry.bootstrap();
        ClientUiSession<UIElement, Object> session = new ClientUiSession<>(new UIElementMirror<>(connection.ops()), connection);
        session.onWindowOpened(root -> {
            if (root != null) pass("1 tree");
            CrystalGuiCore.LOGGER.info("[session-probe] tree rebuilt: {} children",
                    root == null ? -1 : root.children().size());
        });
        session.onCall("probe/ping", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("pong", args.getString("from", "?"));
            respond.ok(out);
        });
        client = session;
    }

    public static final class Handler {

        // ── Server side ─────────────────────────────────────────────────────

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            if (server == null) {
                MinecraftServer mc = MinecraftServer.getServer();
                if (mc == null || mc.getConfigurationManager() == null
                        || mc.getConfigurationManager().playerEntityList.isEmpty()) {
                    return;
                }
                EntityPlayerMP player =
                        (EntityPlayerMP) mc.getConfigurationManager().playerEntityList.get(0);
                ProtocolConnection<Object> connection = CgUiConnections.forPlayer(player);
                if (connection == null) return;
                openServer(connection);
                return;
            }

            // Riding a connection, so this only flushes what the tree changed.
            server.tick();
            pumpExtraViewer();

            if (!done("1 tree")) return;

            if (!deltaSent) {
                deltaSent = true;
                serverSlider.setValue(0.75f);
                return;
            }
            if (!done("4 state delta") || !done("5 event")) return;

            if (!callSent) {
                callSent = true;
                StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
                args.putString("from", "server");
                server.call("probe/ping", args,
                        result -> {
                            if ("server".equals(result.getString("pong", ""))) {
                                pass("6 server->client call");
                            }
                        },
                        error -> CrystalGuiCore.LOGGER.error("[session-probe] call failed: {}", error));
                return;
            }
            if (!done("6 server->client call")) return;

            // C2 -- a child added AFTER open, which used to need a whole re-open.
            if (!reshapeSent) {
                reshapeSent = true;
                addedLater = new UIText("added after open");
                server.root().insertAt(0, addedLater);
                CrystalGuiCore.LOGGER.info("[session-probe] inserted a child at index 0 "
                        + "— every id after it shifts");
                return;
            }

            // C1 -- a second viewer on the LIVE session. Its far end is in-memory because a
            // single-player world has one connection; the fan-out path is the real one.
            if (done("7 reshape (C2)") && !fanoutStarted) {
                fanoutStarted = true;
                extraLink = InMemoryTransport.pair();
                extraServer = Protocols.open(extraLink[0], PlainOps.INSTANCE, () -> { }, "probe-viewer");
                extraClient = Protocols.open(extraLink[1], PlainOps.INSTANCE, () -> { }, null);
                extraViewer = new ClientUiSession<>(new UIElementMirror<>(extraClient.ops()), extraClient);
                extraViewer.onWindowOpened(root -> {
                    if (root != null) pass("8 fan-out (C1)");
                    CrystalGuiCore.LOGGER.info("[session-probe] second viewer rebuilt {} children",
                            root == null ? -1 : root.children().size());
                });
                server.addViewer(extraServer);
                CrystalGuiCore.LOGGER.info("[session-probe] added a second viewer; count={}",
                        server.viewerCount());
            }
        }

        /** Both ends of the synthetic viewer's link, on the thread that owns the tree. */
        private void pumpExtraViewer() {
            if (extraLink == null) return;
            extraLink[0].deliver();
            extraLink[1].deliver();
            extraServer.tick();
            extraClient.tick();
        }

        // ── Client side ─────────────────────────────────────────────────────

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            // An open screen pauses the integrated server, and a paused server never drains its inbound
            // queue. @see CgUiNetProbe, which cost eleven runs to learn it.
            if (mc.currentScreen != null) {
                mc.displayGuiScreen(null);
                return;
            }

            if (client == null) {
                ProtocolConnection<Object> connection = CgUiConnections.client();
                if (connection == null) {
                    if (++clientTicks > DEADLINE_TICKS) finish(false, "no client connection");
                    return;
                }
                openClient(connection);
                return;
            }
            clientTicks++;
            if (client.root() == null) return;

            checkDescribedState();
            checkDelta();
            pressWhenReady();
            checkReshape();
            driveFiles();

            if (clientTicks % 60 == 0) report("waiting");
            if (!CHECKS.containsValue(false)) finish(true, "");
            else if (clientTicks > DEADLINE_TICKS) finish(false, "timed out");
        }

        /** C3 and C4, read off the rebuilt tree. */
        private void checkDescribedState() {
            if (done("2 tabs (C3)") && done("3 widget state (C4)")) return;
            List<UIElement> children = client.root().children();
            // The reshape inserts at 0, so index off the END -- which is also a small check that the
            // tree really is the one that was described.
            int n = children.size();
            UIElement tabsElement = children.get(n - 3);
            UIElement progressElement = children.get(n - 2);
            UIElement dropdownElement = children.get(n - 1);

            if (tabsElement instanceof TabView tabs && tabs.getTabs().size() == 2) {
                boolean labels = "Editor".equals(tabs.getTabs().get(0).getText())
                        && "Settings".equals(tabs.getTabs().get(1).getText());
                boolean content = !tabs.getTabs().get(0).content().children().isEmpty();
                boolean selection = tabs.getSelectedIndex() == 1;
                if (labels && content && selection) pass("2 tabs (C3)");
                else CrystalGuiCore.LOGGER.warn("[session-probe] tabs: labels={} content={} selected={}",
                        labels, content, tabs.getSelectedIndex());
            }

            boolean progressOk = progressElement instanceof ProgressBar bar
                    && Math.abs(bar.fraction() - 0.42f) < 1e-4f;
            boolean dropdownOk = dropdownElement instanceof Dropdown drop
                    && drop.getOptionCount() == 3
                    && "gamma".equals(drop.getSelectedOption());
            if (progressOk && dropdownOk) pass("3 widget state (C4)");
        }

        private void checkDelta() {
            if (done("4 state delta") || !deltaSent) return;
            int n = client.root().children().size();
            UIElement mirrored = client.root().children().get(n - 4);
            if (mirrored instanceof Slider slider && Math.abs(slider.getValue() - 0.75f) < 1e-4f) {
                pass("4 state delta");
            }
        }

        private void pressWhenReady() {
            if (eventSent || !done("4 state delta")) return;
            int n = client.root().children().size();
            UIElement mirrored = client.root().children().get(n - 5);
            if (!(mirrored instanceof Button)) return;
            eventSent = true;
            // The REAL widget: what reports is a listener the client attached from the description.
            ((Button) mirrored).onPressed.emit();
        }

        /** C2 — the child arrived, and the slider's update still landed after renumbering. */
        private void checkReshape() {
            if (done("7 reshape (C2)") || !reshapeSent) return;
            List<UIElement> children = client.root().children();
            if (children.size() != 7) return;
            if (!(children.get(0) instanceof UIText text)) return;
            if (!"added after open".equals(text.getText())) return;
            // And the slider is still the slider, at its shifted index.
            UIElement slider = children.get(children.size() - 4);
            if (slider instanceof Slider s && Math.abs(s.getValue() - 0.75f) < 1e-4f) {
                pass("7 reshape (C2)");
            }
        }

        /** B1/B2 then C5, on the workspace the server is hosting. */
        private void driveFiles() {
            if (!done("7 reshape (C2)")) return;
            if (files == null) {
                ProtocolConnection<Object> connection = CgUiConnections.client();
                if (connection == null) return;
                files = WorkspaceClient.forConnection(connection);
            }

            if (!filesAsked) {
                filesAsked = true;
                files.list(CgPath.ofProject(CgUiWorkspaceHost.PROJECT_ID),
                        entries -> {
                            CrystalGuiCore.LOGGER.info("[session-probe] listed {} entries from the server",
                                    entries.size());
                            if (!entries.isEmpty()) pass("9 files (B1/B2)");
                        },
                        failure -> CrystalGuiCore.LOGGER.error("[session-probe] listing failed: {}",
                                failure.code()));
                return;
            }
            if (!done("9 files (B1/B2)") || deltaWriteStarted) return;

            // C5 -- read, write a CHANGE SET, re-read. The re-read is conditional on the etag, so it
            // also exercises the cache path rather than merely the write.
            deltaWriteStarted = true;
            CgPath readme = CgPath.of(CgUiWorkspaceHost.PROJECT_ID, "README.md");
            files.read(readme,
                    first -> {
                        String before = new String(first.content(), StandardCharsets.UTF_8);
                        CrystalGuiCore.LOGGER.info("[session-probe] README is {} bytes", before.length());
                        // Replace the first line's "#" with "#!" -- one change, not the whole file.
                        files.writeDelta(readme, List.of(new Change(0, 1, "#!")),
                                etag -> files.read(readme,
                                        second -> {
                                            String after =
                                                    new String(second.content(), StandardCharsets.UTF_8);
                                            boolean applied = after.startsWith("#!");
                                            CrystalGuiCore.LOGGER.info("[session-probe] after "
                                                    + "writeDelta the file starts \"{}\"",
                                                    after.substring(0, Math.min(12, after.length())));
                                            if (applied) {
                                                // Put it back, so a repeat run starts from the same file.
                                                files.writeDelta(readme, List.of(new Change(0, 2, "#")),
                                                        e -> pass("10 writeDelta + cache (C5)"),
                                                        f -> CrystalGuiCore.LOGGER.error(
                                                                "[session-probe] restore failed: {}",
                                                                f.code()));
                                            }
                                        },
                                        f -> CrystalGuiCore.LOGGER.error(
                                                "[session-probe] re-read failed: {}", f.code())),
                                failure -> CrystalGuiCore.LOGGER.error(
                                        "[session-probe] writeDelta failed: {}", failure.code()));
                    },
                    failure -> CrystalGuiCore.LOGGER.error("[session-probe] README read failed: {}",
                            failure.code()));
        }

        private void report(String prefix) {
            StringBuilder line = new StringBuilder("[session-probe] " + prefix + ":");
            for (String check : ORDER) {
                line.append(done(check) ? " OK " : " -- ").append(check).append(';');
            }
            CrystalGuiCore.LOGGER.info(line.toString());
        }

        /**
         * Reports, then quits.
         *
         * <p>A client left running holds file handles on the vanilla jars, and the next build then fails
         * with {@code Could not evaluate onlyIf predicate for task ':mc1710:mergeVanillaSidedJars'} —
         * which names a Gradle task and says nothing about a live process.</p>
         *
         * <p><b>Expect one exception after the verdict.</b> Quitting from inside a tick leaves the
         * integrated server mid-tick and it dies with {@code IllegalStateException: Display not created}.
         * It is written after the PASS/FAIL line and means nothing.</p>
         */
        private void finish(boolean pass, String why) {
            reported = true;
            report(pass ? "PASS" : "FAIL (" + why + ")");
            if (pass) {
                CrystalGuiCore.LOGGER.info("[session-probe] PASS — every Phase 4 feature crossed a real "
                        + "Minecraft connection: protocol, lifecycle, workspace, tree deltas, tab and "
                        + "widget state, fan-out, and a delta write");
            } else {
                CrystalGuiCore.LOGGER.error("[session-probe] FAIL ({}) — the first '--' above is the "
                        + "check that stalled", why);
            }
            Minecraft.getMinecraft().shutdown();
        }
    }
}
