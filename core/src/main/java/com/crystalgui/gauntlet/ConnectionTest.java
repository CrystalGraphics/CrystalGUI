package com.crystalgui.gauntlet;

import com.crystalgui.screen.CgUiScreen;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.platform.service.CgGameSession;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntlet;
import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.CgRequest;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.ui.dom.UIElementTreeSource;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.text.UIText;

/**
 * <b>Everything that needs a real connection, checked over one.</b>
 *
 * <p>A headless test models the wire as two multiplexers handing arrays to each other in one JVM, which
 * proves everything <em>above</em> the platform's channel and nothing about the channel itself, the
 * game's own thread, its frame ceiling, or player routing. This is the other half, and it runs in a
 * game.</p>
 *
 * <pre>{@code
 * // single player -- the integrated server is a real server with a very short wire
 * runClient -Dcrystalgraphics.gauntlet=crystalgui:connection
 *
 * // a genuinely separate process: every byte crosses a socket and the server's disk is another directory
 * runServer
 * runClient -PcgJoin=localhost:25565 -Dcrystalgraphics.gauntlet=crystalgui:connection
 *
 * // two clients on one server, run on BOTH -- start the watcher first
 * runClient -PcgJoin=... -Dcrystalgraphics.gauntlet=crystalgui:connection -Dcrystalgui.connection.role=watcher
 * runClient -PcgJoin=... -Dcrystalgraphics.gauntlet=crystalgui:connection -Dcrystalgui.connection.role=writer
 * }</pre>
 *
 * <h3>Topology is a parameter, not a class</h3>
 *
 * <p>This replaces six probes that each hard-wired one (topology, check-set) pair, and three of which
 * existed mainly to <em>refuse to run</em> in the wrong one. The checks are the same in every topology;
 * what changes is which of them mean anything, so a check declares that and is reported as SKIPPED with
 * the reason rather than being a separate class that declines.</p>
 *
 * <p>The clearest case: the old remote-workspace probe listed a directory, created a file, read it back
 * and applied a delta — which is what the session probe's own file checks did. The only difference was
 * that it ran against a dedicated server, so the bytes landed on another machine. That is a topology,
 * and here it is one: the same two file checks, and on {@link Topology#DEDICATED} they are the proof
 * that the workspace is the <em>server's</em>.</p>
 *
 * <h3>Easy to get wrong</h3>
 *
 * <ul>
 *   <li><b>A check that never ran must never read as a pass.</b> Everything starts false and the report
 *       prints one line per check; the first {@code --} is where a run stalled. A skip is printed as a
 *       skip, with its reason.</li>
 *   <li>{@link #serverTick} and {@link #clientTick} are two threads. The registry is concurrent for
 *       that reason — a {@code HashSet} written from one thread while another copied it has already
 *       cost this codebase an {@code ArrayIndexOutOfBoundsException} with nothing of ours in the trace.</li>
 *   <li>Start the <b>watcher</b> before the writer. A watch is a subscription, and a change from before
 *       anybody subscribed is not one anybody missed.</li>
 * </ul>
 */
public final class ConnectionTest extends CgGauntletController {

    /** Its id in a run. */
    public static final String ID = "crystalgui:connection";

    /** {@code -Dcrystalgui.connection.role=writer|watcher}. @see Role */
    public static final String ROLE_PROPERTY = "crystalgui.connection.role";
    private static final CgVar.Text ROLE_VAR = CgVar.text(ROLE_PROPERTY, "", CgVar.DEV | CgVar.RESTART,
            "Which half of crystalgui:connection's two-client check this client is: writer or watcher; empty for neither.");

    /** Where the run is happening, which is what decides whether a check means anything. */
    public enum Topology {
        /** One process. A real connection through the game's own channel, sharing a JVM and a disk. */
        INTEGRATED,
        /** Two processes over a socket, and the server's files are on the server's machine. */
        DEDICATED
    }

    /** Which half of the two-client check this process is. */
    public enum Role {
        NONE, WRITER, WATCHER;

        static Role fromProperty() {
            String value = ROLE_VAR.get();
            for (Role role : values()) {
                if (role.name().equalsIgnoreCase(value)) return role;
            }
            return NONE;
        }
    }

    /**
     * Read once — it was re-read per check per tick, and {@code values()} clones its array every call.
     *
     * <p>Fixed for the life of the run by construction: a role is how this process was launched.</p>
     */
    private static final Role ROLE = Role.fromProperty();

    // ── The checklist ───────────────────────────────────────────────────────────────────────────

    /** A named assertion, and where it means anything. */
    private static final class Check {
        final String name;
        @Nullable
        final Topology only;
        final Role role;

        Check(String name, @Nullable Topology only, Role role) {
            this.name = name;
            this.only = only;
            this.role = role;
        }

        /** Why this check is not being run here, or null when it is. */
        @Nullable
        String skippedBecause() {
            if (only != null && topology() != only) {
                return "needs a " + only.name().toLowerCase(Locale.ROOT) + " server";
            }
            if (role != Role.NONE && ROLE != role) {
                return "needs -D" + ROLE_PROPERTY + "=" + role.name().toLowerCase(Locale.ROOT);
            }
            // A ROLE RUN DRIVES NOTHING ELSE: driveRole opens no session and touches no files of its
            // own, so every check that is not this role's goes unrun. Printing those as `--` beside a
            // PASS verdict claims a stall where there was none, and "the first `--` is where it
            // stopped" is the whole contract of the report.
            if (ROLE != Role.NONE && role != ROLE) {
                return "this is a " + ROLE.name().toLowerCase(Locale.ROOT) + " run";
            }
            return null;
        }
    }

    private static final String TREE = "tree";
    private static final String TABS = "tabs and their contents (C3)";
    private static final String WIDGET_STATE = "widget state (C4)";
    private static final String STATE_DELTA = "state delta";
    private static final String EVENT = "event, client -> server";
    private static final String CALL = "call, server -> client";
    private static final String RESHAPE = "reshape and renumbering (C2)";
    private static final String FANOUT = "a second viewer on a live session (C1)";
    private static final String LISTING = "a listing off the server's disk (B1/B2)";
    private static final String WRITE = "conditional write and the etag cache (C5)";
    private static final String LOOP_ALIVE = "the game loop turns with the desktop up";
    private static final String WATCH = "a write reaches a SECOND client";
    private static final String AUDIENCES = "a message near the player arrives, one to another dimension does not";
    private static final String REQUEST = "a typed request is answered by the server, naming who asked";

    private static final List<Check> CHECKS = Arrays.asList(
            // INTEGRATED, all eight of them: the session checks need the SERVER half of this probe,
            // and that only exists where the server is in this process. A client joined to a dedicated
            // server can drive the workspace and nothing else, which is what the file checks below are.
            new Check(TREE, Topology.INTEGRATED, Role.NONE),
            new Check(TABS, Topology.INTEGRATED, Role.NONE),
            new Check(WIDGET_STATE, Topology.INTEGRATED, Role.NONE),
            new Check(STATE_DELTA, Topology.INTEGRATED, Role.NONE),
            new Check(EVENT, Topology.INTEGRATED, Role.NONE),
            new Check(CALL, Topology.INTEGRATED, Role.NONE),
            new Check(RESHAPE, Topology.INTEGRATED, Role.NONE),
            new Check(FANOUT, Topology.INTEGRATED, Role.NONE),
            new Check(AUDIENCES, Topology.INTEGRATED, Role.NONE),
            new Check(REQUEST, Topology.INTEGRATED, Role.NONE),
            // ON A DEDICATED SERVER THESE TWO ARE THE PROOF THE FILES ARE THE SERVER'S. In single player
            // they still check the protocol; what they cannot check there is location, because there is
            // only one machine.
            new Check(LISTING, null, Role.NONE),
            new Check(WRITE, null, Role.NONE),
            // ONLY AN INTEGRATED SERVER CAN BE STOPPED BY A CLIENT'S OWN SCREEN, so this is vacuous
            // anywhere else. It exists because a screen that pauses the game stops the server ticking,
            // so the connection is never pumped and every call the desktop makes dies at its timeout --
            // with nothing in the log. It presented as "the workspace is empty".
            new Check(LOOP_ALIVE, Topology.INTEGRATED, Role.NONE),
            // TWO PROCESSES, and a single client is the fixture that passes against everything the
            // watcher exists for: a change you made yourself needs no notification to be on screen.
            new Check(WATCH, Topology.DEDICATED, Role.WATCHER));

    /** Written from both ticks. @see ConnectionTest */
    private static final Map<String, Boolean> PASSED = new ConcurrentHashMap<>();

    static {
        for (Check check : CHECKS) PASSED.put(check.name, false);
    }

    private static void pass(String check) {
        if (Boolean.TRUE.equals(PASSED.get(check))) return;
        PASSED.put(check, true);
        CrystalGuiCore.LOGGER.info("[connection] OK {}", check);
    }

    private static boolean done(String check) {
        return Boolean.TRUE.equals(PASSED.get(check));
    }

    // ── State ───────────────────────────────────────────────────────────────────────────────────

    private static final int WINDOW_ID = 4242;

    /** Five seconds of ticks: the run is in a world already, and a stalled probe ends the run rather than holding it. */
    private static final int DEADLINE_TICKS = 20 * 5;

    /** So the watcher's two reports are two events rather than one coalesced change. */
    private static final int BETWEEN_WRITES = 40;

    private static final String SHARED_FILE = "two-client-probe.txt";

    /** Declared by {@link #declare()}, at init: a namespace declared after the hello is one the peer never heard of. */
    private static final CgMessage<String> AUDIENCE = CgMessage.toClients("crystalgui:probe/audience", CgCodecs.STRING);

    private static final CgRequest<String, String> ASK =
            CgRequest.toServer("crystalgui:probe/ask", CgCodecs.STRING, CgCodecs.STRING);

    /**
     * Declares the test's message and request, and the server's answer, on a run that plays it. At init, before any
     * connection opens: the test itself starts a world later, after the hello has named the namespaces.
     */
    public static void declare() {
        ASK.onServer((peer, text, reply) -> reply.ok(text + " from " + peer.id()));
    }

    private static boolean audienceSent;
    private static boolean asked;
    private static boolean audienceListening;
    private static volatile boolean elsewhereArrived;

    private static volatile ServerUiSession<UIElement, Object> server;
    private static volatile ClientUiSession<UIElement, Object> client;

    private static Slider serverSlider;

    private static CgInMemoryTransport<Object>[] extraLink;
    private static CgProtocolConnection<Object> extraServer;
    private static CgProtocolConnection<Object> extraClient;

    /** Set once the client's session is listening, so the server does not open into nothing. */
    private static volatile boolean clientReady;

    private static volatile boolean deltaSent;
    private static volatile boolean eventSent;
    private static volatile boolean callSent;
    private static volatile boolean reshapeSent;
    private static volatile boolean fanoutStarted;
    private static volatile boolean reported;

    private static Workspace files;
    private static boolean listingAsked;
    private static boolean writeStarted;
    private static boolean desktopOpened;
    private static boolean loopAskStarted;

    /** How many times a screen may be closed before the probe stops fighting whoever opens it. */
    private static final int MAX_SCREEN_CLOSES = 40;

    private static int screenCloses;

    private static boolean detached;
    private static boolean worldRequested;
    private static boolean watching;
    private static boolean writerCreated;
    private static boolean writerCreating;
    private static boolean writerEdited;
    private static boolean writerEditing;
    private static int writerCreatedAt;

    private static final Set<String> HEARD = new LinkedHashSet<>();

    private static int ticks;

    /** The test playing; null outside a run's turn. */
    private static volatile ConnectionTest active;

    /** Whether a run is playing it, which owns {@code ui/openWindow} meanwhile. */
    public static boolean enabled() {
        return active != null;
    }

    private static boolean ticking;

    @Override
    protected void onInit() {
        active = this;
        if (ticking) return;
        ticking = true;
        CgEngineLifecycle.onServerTick(() -> serverTick());
        CgEngineLifecycle.onClientTick(() -> clientTick());
    }

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.SERVER)) {
            skip("connection", "a client's test: its server half runs in the clients' integrated servers");
            endTest();
        }
    }

    @Override
    protected void onEnd() {
        active = null;
    }

    /** Where the run is: one process, or a client of a dedicated server. */
    private static Topology topology() {
        return CgServers.running() && !CgServers.dedicated() ? Topology.INTEGRATED : Topology.DEDICATED;
    }

    /**
     * The integrated server's connection to its one player, or null before they have joined. Found among the server's
     * own: the client's player has no id there, being no server player.
     */
    @Nullable
    private static CgProtocolConnection<Object> connectionToFirstPlayer() {
        if (!CgServers.running() || CgServers.dedicated()) return null;
        UUID[] first = new UUID[1];
        CgNetwork.forEachPlayer(id -> {
            if (first[0] == null) first[0] = id;
        });
        return first[0] == null ? null : CgNetwork.forPlayer(first[0]);
    }

    // ── The server half ─────────────────────────────────────────────────────────────────────────

    /** Once per server tick. */
    private static void serverTick() {
        if (!enabled() || reported) return;
        if (!audienceSent && clientReady) sendAudiences();

        if (server == null) {
            // THE CLIENT HAS TO BE LISTENING FIRST. `ui/openWindow` is a notification, so a client with
            // no handler yet does not queue it or ask again -- it drops it and waits for ever, saying
            // only "No notification handler for 'ui/openWindow'" in its own log. Both halves are driven
            // by ticks in one process and nothing orders them; here the server's connection opened five
            // seconds before the client's.
            //
            // Waiting, not re-announcing: ServerUiSession.open() throws on a second call, by design --
            // a session is opened once and a reshape is a delta.
            if (!clientReady) return;
            CgProtocolConnection<Object> connection = connectionToFirstPlayer();
            if (connection == null) return;
            openServer(connection);
            return;
        }

        // Riding a connection, so this only flushes what the tree changed.
        server.tick();
        pumpExtraViewer();

        if (!done(TREE)) return;

        if (!deltaSent) {
            deltaSent = true;
            serverSlider.setValue(0.75f);
            return;
        }
        if (!done(STATE_DELTA) || !done(EVENT)) return;

        if (!callSent) {
            callSent = true;
            CgStateMap<Object> args = new CgStateMap<>(CgPlainOps.INSTANCE);
            args.putString("from", "server");
            server.call("probe/ping", args,
                    result -> {
                        if ("server".equals(result.getString("pong", ""))) pass(CALL);
                    },
                    error -> CrystalGuiCore.LOGGER.error("[connection] call failed: {}", error));
            return;
        }
        if (!done(CALL)) return;

        // C2 -- a child added AFTER open, which used to need a whole re-open. Inserted at index 0, so
        // every id after it shifts and a later state update has to still land on the right element.
        if (!reshapeSent) {
            reshapeSent = true;
            server.root().insertAt(0, new UIText("added after open"));
            CrystalGuiCore.LOGGER.info("[connection] inserted a child at index 0 — every id after it shifts");
            return;
        }

        // C1 -- a second viewer on the LIVE session. Its far end is in-memory because a single-player
        // world has one connection; the fan-out path itself is the real one.
        if (done(RESHAPE) && !fanoutStarted) {
            fanoutStarted = true;
            extraLink = CgInMemoryTransport.pair();
            extraServer = CgProtocols.open(extraLink[0], CgPlainOps.INSTANCE, () -> { }, "probe-viewer");
            extraClient = CgProtocols.open(extraLink[1], CgPlainOps.INSTANCE, () -> { }, null);
            ClientUiSession<UIElement, Object> viewer =
                    new ClientUiSession<>(new UIElementMirror<>(extraClient.ops()), extraClient);
            viewer.onWindowOpened(root -> {
                if (root != null) pass(FANOUT);
            });
            server.addViewer(extraServer);
            CrystalGuiCore.LOGGER.info("[connection] added a second viewer; count={}", server.viewerCount());
        }
    }

    /**
     * One message to the dimension the player is not in, then one near them. A connection is in order, so the near one
     * arriving with nothing before it is the proof. Waits until the host can place the player: a host that never fills
     * its levels ({@link CgWorldQueries#dimension}) never sends, and the check stays unrun.
     */
    private static void sendAudiences() {
        CgProtocolConnection<Object> connection = connectionToFirstPlayer();
        Object player = connection != null && connection.peer() instanceof CgPeer
                ? ((CgPeer) connection.peer()).player() : null;
        double[] at = new double[3];
        String here = player == null ? null : CgWorldQueries.dimension(player);
        if (here == null || !CgEntityQueries.position(player, at)) return;
        audienceSent = true;
        String elsewhere = "minecraft:the_nether".equals(here) ? "minecraft:overworld" : "minecraft:the_nether";
        AUDIENCE.send(CgAudience.dimension(elsewhere), "elsewhere");
        AUDIENCE.send(CgAudience.near(player, at[0], at[1], at[2], 16), "near");
        CrystalGuiCore.LOGGER.info("[connection] sent near ({}) and to {}", here, elsewhere);
    }

    /** Both ends of the synthetic viewer's link, on the thread that owns the tree. */
    private static void pumpExtraViewer() {
        if (extraLink == null) return;
        extraLink[0].deliver();
        extraLink[1].deliver();
        extraServer.tick();
        extraClient.tick();
    }

    /** A tree that exercises C3 and C4 as well as the basics. */
    private static void openServer(CgProtocolConnection<Object> connection) {
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

        ServerUiSession<UIElement, Object> session = new ServerUiSession<>(
                WINDOW_ID, new UIElementTreeSource(root),
                new UIElementMirror<>(connection.ops()), connection);
        session.on(button, Button.ACTIVATE, ctx -> pass(EVENT));
        session.open();
        server = session;
        CrystalGuiCore.LOGGER.info("[connection] server session opened on the real connection, {} children",
                root.children().size());
    }

    // ── The client half ─────────────────────────────────────────────────────────────────────────

    /** Once per client tick. */
    private static void clientTick() {
        if (!enabled() || reported) return;
        if (!asked && CgNetwork.client() != null) {
            asked = true;
            ASK.ask("ping").then(answer -> {
                if (answer.startsWith("ping from ")) pass(REQUEST);
                else CrystalGuiCore.LOGGER.error("[connection] a request came back as {}", answer);
            }).onError(error -> CrystalGuiCore.LOGGER.error("[connection] a request failed: {}", error));
        }
        if (!audienceListening) {
            audienceListening = true;
            AUDIENCE.onReceive(which -> {
                if ("elsewhere".equals(which)) {
                    elsewhereArrived = true;
                    CrystalGuiCore.LOGGER.error("[connection] a message to another dimension arrived");
                } else if (!elsewhereArrived) {
                    pass(AUDIENCES);
                }
            });
        }

        // COUNTED BEFORE THE WORLD, so a run that never gets into one SAYS SO. Returning early on
        // "not in a world yet" without a clock is how the probes this replaces sat at a main menu
        // indefinitely: nothing failed, nothing passed, and the Gradle task simply never came back.
        if (!CgGameSession.inWorld()) {
            // AND IT GETS ITSELF THERE. Six probes ago this needed somebody to load a world by hand,
            // which is why none of them could be a build task.
            if (!worldRequested) worldRequested = CgGauntlet.enterWorld();
            // SAID OUT LOUD, because the alternative is what this cost once: a run sat in this branch
            // producing not one line, and "is the probe even being ticked" was unanswerable from a log.
            if (++ticks % 100 == 0) {
                CrystalGuiCore.LOGGER.info("[connection] waiting for a world ({} ticks; asked={})",
                        ticks, worldRequested);
            }
            if (ticks > DEADLINE_TICKS) {
                finish(false, worldRequested
                        ? "asked for a world and never got into one"
                        : "never got as far as asking for a world");
            }
            return;
        }

        // The two-client roles never open a session: they are about the workspace and nothing else.
        if (ROLE != Role.NONE) {
            driveRole(ROLE);
            return;
        }

        // A screen is closed until the session checks are done -- a screen that pauses the game stops
        // the integrated server draining its inbound queue, and LOOP_ALIVE is what asserts our own
        // screen no longer does that.
        //
        // BOUNDED, AND THE TICK CARRIES ON. Both halves were paid for. Returning here starved the probe
        // of every tick it had, because an unattended client has no window focus and Minecraft re-opens
        // its pause menu constantly. And closing it EVERY tick for ever makes the window unusable by a
        // person: Escape opens the menu and the next tick shuts it, so a run that has stalled cannot
        // even be closed by hand. A diagnostic may not trap the person watching it. The desktop pauses nothing, which
        // LOOP_ALIVE asserts, so it stays up.
        if (CgScreens.anyOpen() && !CgUiScreen.isOpen() && !done(WRITE)) {
            if (screenCloses < MAX_SCREEN_CLOSES) {
                screenCloses++;
                CgScreens.closeFront();
            } else if (screenCloses == MAX_SCREEN_CLOSES) {
                screenCloses++;
                CrystalGuiCore.LOGGER.warn("[connection] a screen keeps re-opening after {} attempts; "
                        + "leaving it alone. The window is yours again -- this run will time out and "
                        + "quit on its own.", MAX_SCREEN_CLOSES);
            }
        }

        if (client == null) {
            CgProtocolConnection<Object> connection = CgNetwork.client();
            if (connection == null) {
                if (++ticks % 100 == 0) {
                    CrystalGuiCore.LOGGER.info("[connection] in a world, waiting for a connection ({} ticks)",
                            ticks);
                }
                if (ticks > DEADLINE_TICKS) finish(false, "no client connection");
                return;
            }
            openClient(connection);
            return;
        }
        ticks++;

        // THE SESSION IS HANDED BACK BEFORE THE DESKTOP OPENS, and it has to be.
        //
        // `ui/openWindow` has one owner per connection: this session while the session checks run, and
        // ClientWindows once a real desktop is up. Without the handover the desktop's own mount threw
        // from the router mid-tick -- which is how the editor-open probe ended up a separate class with
        // its own flag, never opening a session at all. @see ClientUiSession#detach()
        if (done(WRITE) && !detached) {
            detached = true;
            client.detach();
            CrystalGuiCore.LOGGER.info("[connection] session checks done; handing ui/openWindow back so the "
                    + "desktop can open");
            return;
        }
        // THE REPORT COMES FIRST, because the early return below used to skip it: a client whose tree
        // never arrived sat silent for ever, and "where did it stop" was unanswerable from the log.
        if (ticks % 60 == 0) report("waiting");

        if (detached) {
            driveLoopAlive();
        } else if (client.root() != null) {
            checkDescribedState();
            checkDelta();
            pressWhenReady();
            checkReshape();
            driveFiles();
        }
        if (everythingRunnableIsDone()) finish(true, "");
        else if (ticks > DEADLINE_TICKS) finish(false, "timed out");
    }

    private static void openClient(CgProtocolConnection<Object> connection) {
        UIElementRegistry.bootstrap();
        ClientUiSession<UIElement, Object> session =
                new ClientUiSession<>(new UIElementMirror<>(connection.ops()), connection);
        session.onWindowOpened(root -> {
            if (root != null) pass(TREE);
            CrystalGuiCore.LOGGER.info("[connection] tree rebuilt: {} children",
                    root == null ? -1 : root.children().size());
        });
        session.onCall("probe/ping", (args, respond) -> {
            CgStateMap<Object> out = new CgStateMap<>(CgPlainOps.INSTANCE);
            out.putString("pong", args.getString("from", "?"));
            respond.ok(out);
        });
        client = session;
        // LAST, and it is what releases the server half. @see #serverTick
        clientReady = true;
        CrystalGuiCore.LOGGER.info("[connection] client session listening; the server may open its window");
    }

    /** C3 and C4, read off the rebuilt tree. */
    private static void checkDescribedState() {
        if (done(TABS) && done(WIDGET_STATE)) return;
        List<UIElement> children = client.root().children();
        // The reshape inserts at 0, so index off the END -- which is also a small check that the tree
        // really is the one that was described.
        int n = children.size();
        if (n < 3) return;
        UIElement tabsElement = children.get(n - 3);
        UIElement progressElement = children.get(n - 2);
        UIElement dropdownElement = children.get(n - 1);

        if (tabsElement instanceof TabView) {
            TabView tabs = (TabView) tabsElement;
            if (tabs.getTabs().size() == 2) {
                boolean labels = "Editor".equals(tabs.getTabs().get(0).getText())
                        && "Settings".equals(tabs.getTabs().get(1).getText());
                boolean content = !tabs.getTabs().get(0).content().children().isEmpty();
                if (labels && content && tabs.getSelectedIndex() == 1) pass(TABS);
            }
        }

        boolean progressOk = progressElement instanceof ProgressBar
                && Math.abs(((ProgressBar) progressElement).fraction() - 0.42f) < 1e-4f;
        boolean dropdownOk = dropdownElement instanceof Dropdown
                && ((Dropdown) dropdownElement).getOptionCount() == 3
                && "gamma".equals(((Dropdown) dropdownElement).getSelectedOption());
        if (progressOk && dropdownOk) pass(WIDGET_STATE);
    }

    private static void checkDelta() {
        if (done(STATE_DELTA) || !deltaSent) return;
        List<UIElement> children = client.root().children();
        if (children.size() < 4) return;
        UIElement mirrored = children.get(children.size() - 4);
        if (mirrored instanceof Slider && Math.abs(((Slider) mirrored).getValue() - 0.75f) < 1e-4f) {
            pass(STATE_DELTA);
        }
    }

    private static void pressWhenReady() {
        if (eventSent || !done(STATE_DELTA)) return;
        List<UIElement> children = client.root().children();
        if (children.size() < 5) return;
        UIElement mirrored = children.get(children.size() - 5);
        if (!(mirrored instanceof Button)) return;
        eventSent = true;
        // The REAL widget: what reports is a listener the client attached from the description.
        ((Button) mirrored).onPressed.emit();
    }

    /** C2 — the child arrived, and the slider's update still landed after renumbering. */
    private static void checkReshape() {
        if (done(RESHAPE) || !reshapeSent) return;
        List<UIElement> children = client.root().children();
        if (children.size() != 7) return;
        if (!(children.get(0) instanceof UIText)) return;
        if (!"added after open".equals(((UIText) children.get(0)).getText())) return;
        // And the slider is still the slider, at its shifted index.
        UIElement slider = children.get(children.size() - 4);
        if (slider instanceof Slider && Math.abs(((Slider) slider).getValue() - 0.75f) < 1e-4f) {
            pass(RESHAPE);
        }
    }

    // ── The workspace ───────────────────────────────────────────────────────────────────────────

    /**
     * The workspace on this client's connection — <b>the same object the desktop is using</b>.
     *
     * <p>{@link Workspace#of} is an attachment on the connection rather than a constructor, so every
     * caller shares one. That matters for {@link #LOOP_ALIVE}: the assertion is that the request the
     * desktop itself would make completes, and a second workspace would be a second subscriber to
     * {@code fs.changed} as well as a weaker claim.</p>
     */
    @Nullable
    private static Workspace workspace() {
        if (files != null) return files;
        CgProtocolConnection<Object> connection = CgNetwork.client();
        if (connection == null) return null;
        files = Workspace.of(connection);
        return files;
    }

    private static Resource project() {
        return Resource.of(CgPath.ofProject(WorkspaceHost.DEFAULT_PROJECT_ID));
    }

    /**
     * B1/B2 then C5 — and on a dedicated server, the proof that the files are the SERVER's.
     *
     * <p>A write is refused with {@code NO_PERMISSIONS} unless the player is an operator, and that is
     * the permission check <em>working</em> rather than a fault. Op the dev player to exercise it.</p>
     */
    private static void driveFiles() {
        if (!done(RESHAPE)) return;
        Workspace live = workspace();
        if (live == null) return;
        if (!live.onHome(ConnectionTest::driveFiles)) return;

        if (!listingAsked) {
            listingAsked = true;
            live.files().list(project())
                    .then(listing -> {
                        CrystalGuiCore.LOGGER.info("[connection] listed {} entries from the {} server",
                                listing.entries().size(),
                                topology().name().toLowerCase(Locale.ROOT));
                        if (!listing.entries().isEmpty()) pass(LISTING);
                    })
                    .onError(failure ->
                            CrystalGuiCore.LOGGER.error("[connection] listing failed: {}", failure.code()));
            return;
        }
        if (!done(LISTING) || writeStarted) return;

        // Read, write CONDITIONALLY on the etag that read handed back, re-read, put it back. The etag
        // is the whole point: the server re-stats before it writes, so a file that moved underneath
        // this client is refused rather than clobbered.
        writeStarted = true;
        Resource readme = Resource.of(CgPath.of(WorkspaceHost.DEFAULT_PROJECT_ID, "README.md"));
        live.files().readWhole(readme)
                .onError(f -> CrystalGuiCore.LOGGER.error("[connection] README read failed: {}", f.code()))
                .then(first -> {
                    String before = new String(first.bytes(), StandardCharsets.UTF_8);
                    String edited = "#!" + before.substring(Math.min(1, before.length()));
                    live.files().write(readme, edited.getBytes(StandardCharsets.UTF_8), first.etag())
                            .onError(f -> CrystalGuiCore.LOGGER.error(
                                    "[connection] conditional write failed: {} — NO_PERMISSIONS here means "
                                            + "the op check is working and the player is not an op",
                                    f.code()))
                            .then(etag -> live.files().readWhole(readme)
                                    .onError(f -> CrystalGuiCore.LOGGER.error(
                                            "[connection] re-read failed: {}", f.code()))
                                    .then(second -> {
                                        String after = new String(second.bytes(), StandardCharsets.UTF_8);
                                        if (!after.startsWith("#!")) return;
                                        // Put it back, so a repeat run starts from the same file.
                                        live.files().write(readme,
                                                        before.getBytes(StandardCharsets.UTF_8),
                                                        second.etag())
                                                .then(e -> pass(WRITE))
                                                .onError(f -> CrystalGuiCore.LOGGER.error(
                                                        "[connection] restore failed: {}", f.code()));
                                    }));
                });
    }

    /**
     * The one configuration a player actually uses, and the one no other check covered.
     *
     * <p>Opens the desktop and asks the server for a listing <b>with the screen up</b>. On an integrated
     * server that is a statement about the game loop rather than the filesystem: a screen that pauses
     * the game stops {@code MinecraftServer.tick}, so the connection is never pumped and the desktop's
     * own request dies at its timeout — the editor asking the integrated server for a project list, and
     * the integrated server not listening <em>because the editor being open is what stopped it</em>.</p>
     */
    private static void driveLoopAlive() {
        if (done(LOOP_ALIVE)) return;
        if (topology() != Topology.INTEGRATED) return;

        if (!desktopOpened) {
            desktopOpened = true;
            CgUiScreen.openApplication();
            return;
        }
        if (!CgScreens.anyOpen() || loopAskStarted) return;

        Workspace live = workspace();
        if (live == null || !live.onHome(ConnectionTest::driveLoopAlive)) return;
        loopAskStarted = true;
        live.files().list(project())
                .then(listing -> {
                    CrystalGuiCore.LOGGER.info("[connection] a round trip completed with the desktop UP "
                            + "({} entries) — the game loop is still turning", listing.entries().size());
                    pass(LOOP_ALIVE);
                })
                .onError(f -> CrystalGuiCore.LOGGER.error(
                        "[connection] the listing with the desktop up failed: {} — if it TIMED OUT, the "
                                + "screen is pausing the game and stopping the server that answers it",
                        f.code()));
    }

    // ── The second client ───────────────────────────────────────────────────────────────────────

    /**
     * The writer creates a file and later edits it; the watcher reports what reached it.
     *
     * <p>Neither talks to the other: the server is the only thing between them, which is the point.</p>
     */
    private static void driveRole(Role role) {
        ticks++;
        Workspace live = workspace();
        if (live == null) {
            if (ticks > DEADLINE_TICKS) finish(false, "joined, but no connection");
            return;
        }
        if (role == Role.WRITER) writerStep(live);
        else watcherStep(live);

        if (ticks > DEADLINE_TICKS) finish(false, "timed out");
    }

    private static void writerStep(Workspace live) {
        Resource shared = Resource.of(CgPath.of(WorkspaceHost.DEFAULT_PROJECT_ID, SHARED_FILE));

        if (!writerCreated) {
            if (writerCreating) return;
            writerCreating = true;
            live.files().write(shared, "first\n".getBytes(StandardCharsets.UTF_8), null)
                    .then(etag -> {
                        CrystalGuiCore.LOGGER.info("[connection] wrote {} — the watcher should hear about it",
                                SHARED_FILE);
                        writerCreated = true;
                        writerCreatedAt = ticks;
                    })
                    .onError(f -> {
                        CrystalGuiCore.LOGGER.error("[connection] create failed: {} — NO_PERMISSIONS means "
                                + "the player is not an op", f.code());
                        writerCreating = false;
                    });
            return;
        }

        // A GAP BETWEEN THEM, so the watcher's two reports are two events rather than one coalesced
        // change. Coalescing per path is correct and is exactly what would hide the second write.
        if (ticks - writerCreatedAt < BETWEEN_WRITES) return;

        if (!writerEdited) {
            if (writerEditing) return;
            writerEditing = true;
            live.files().write(shared, "second\n".getBytes(StandardCharsets.UTF_8), null)
                    .then(etag -> {
                        CrystalGuiCore.LOGGER.info("[connection] edited {}", SHARED_FILE);
                        writerEdited = true;
                    })
                    .onError(f -> {
                        CrystalGuiCore.LOGGER.error("[connection] edit failed: {}", f.code());
                        writerEditing = false;
                    });
            return;
        }
        // The writer asserts nothing itself -- WATCH is the watcher's check. It reports and stops.
        CrystalGuiCore.LOGGER.info("[connection] writer done; the watcher's log is the verdict");
        finish(true, "");
    }

    private static void watcherStep(Workspace live) {
        if (!watching) {
            watching = true;
            // RECURSIVE, because the writer's file is at the project root and a watch that did not
            // descend would be a different assertion by accident.
            live.watch(project(), true).onChanged.connect(changes -> {
                for (FsMessages.FileChange change : changes) {
                    if (!change.path().endsWith(SHARED_FILE)) continue;
                    HEARD.add(change.kind().name() + "@" + ticks);
                    CrystalGuiCore.LOGGER.info("[connection] heard {} on {} at tick {}",
                            change.kind(), change.path(), ticks);
                }
            });
            CrystalGuiCore.LOGGER.info("[connection] watching the project — start the writer now if you have "
                    + "not; a change before this line is one nobody was subscribed to");
            return;
        }
        // TWO of them: a create and a modify. One would pass against a server that reported the first
        // change and then stopped, which is what a coalescing bug produces.
        if (HEARD.size() >= 2) {
            pass(WATCH);
            finish(true, "");
        }
    }

    // ── Reporting ───────────────────────────────────────────────────────────────────────────────

    /** Whether every check that means anything HERE has passed. A skip is not a pass and not a block. */
    private static boolean everythingRunnableIsDone() {
        for (Check check : CHECKS) {
            if (check.skippedBecause() != null) continue;
            if (!done(check.name)) return false;
        }
        return true;
    }

    /** The checklist as it stands, one line per check, with a skip printed as a skip and its reason. */
    private static String render(String prefix) {
        List<String> lines = new ArrayList<>();
        lines.add("=========== CrystalGUI connection probe: " + prefix + " on the "
                + topology().name().toLowerCase(Locale.ROOT) + " server ===========");
        for (Check check : CHECKS) {
            String skipped = check.skippedBecause();
            if (skipped != null) lines.add("SKIP " + check.name + " (" + skipped + ")");
            else lines.add((done(check.name) ? "OK   " : "--   ") + check.name);
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static void report(String prefix) {
        CrystalGuiCore.LOGGER.info("[connection] {}{}", System.lineSeparator(), render(prefix));
    }

    /** The checklist into the run's report, a line a check, then the test ends. */
    private static void finish(boolean passed, String why) {
        reported = true;
        ConnectionTest test = active;
        if (test == null) return;
        if (!passed) test.info("connection.stalled", why);
        for (Check check : CHECKS) {
            String skipped = check.skippedBecause();
            if (skipped != null) test.skip(check.name, skipped);
            else test.check(check.name, done(check.name), done(check.name) ? "crossed a real connection" : "never got there");
        }
        test.endTest();
    }
}
