package com.crystalgui.probe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
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
 * runClient -Dcrystalgui.probe.connection=true
 *
 * // a genuinely separate process: every byte crosses a socket and the server's disk is another directory
 * runServer
 * runClient -PcgJoin=localhost:25565 -Dcrystalgui.probe.connection=true
 *
 * // two clients on one server, run on BOTH -- start the watcher first
 * runClient -PcgJoin=... -Dcrystalgui.probe.connection=true -Dcrystalgui.probe.role=watcher
 * runClient -PcgJoin=... -Dcrystalgui.probe.connection=true -Dcrystalgui.probe.role=writer
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
public final class ConnectionProbe {

    /** {@code -Dcrystalgui.probe.connection=true}. */
    public static final String PROPERTY = "crystalgui.probe.connection";

    /** {@code -Dcrystalgui.probe.role=writer|watcher}. @see Role */
    public static final String ROLE_PROPERTY = "crystalgui.probe.role";

    /** Where the verdict goes, so the build can tell "failed" from "never ran". @see ProbeReport */
    public static final String REPORT_PROPERTY = "crystalgui.probe.report";

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
            String value = System.getProperty(ROLE_PROPERTY, "");
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

    /**
     * What only the running game can answer.
     *
     * <p>Six questions and two verbs. Everything else this class does is the game's business nowhere.</p>
     */
    public interface Host {

        /** @see Topology */
        Topology topology();

        /** Whether the client is in a world yet. Nothing is driven before it is. */
        boolean inWorld();

        /**
         * Gets into a world, so an unattended run needs nobody at the keyboard.
         *
         * <p>Asked every tick until it answers true, then never again — so a host that is not ready yet
         * (a splash still up, no main menu, a join still handshaking) returns false and is asked again.
         * <b>Answer false, do not block:</b> this is called from the client tick.</p>
         *
         * <pre>{@code
         * public boolean enterWorld() {
         *     if (!(minecraft.currentScreen instanceof GuiMainMenu)) return false;  // not yet
         *     minecraft.launchIntegratedServer(name, name, settingsIfItMustBeCreated);
         *     return true;
         * }
         * }</pre>
         *
         * <p>A host joining a server with {@code -PcgJoin} has nothing to do here and answers true: the
         * game is already on its way into a world and {@link #inWorld()} is what waits for it.</p>
         *
         * @return whether the request has been made and this method should stop being called
         */
        boolean enterWorld();

        /** Whether any screen is up. */
        boolean screenIsUp();

        /** Closes whatever screen is up. */
        void closeScreen();

        /** Opens this host's desktop, for the check that the game loop survives it. */
        void openDesktop();

        /** The server-side connection to the first joined player, or null before anybody has. */
        @Nullable
        ProtocolConnection<Object> connectionToFirstPlayer();

        /** This client's connection to whatever server it is on, or null before there is one. */
        @Nullable
        ProtocolConnection<Object> clientConnection();

        /**
         * Ends the run.
         *
         * <p>A client left running holds file handles on the game's jars and the next build fails
         * naming a task rather than a live process. <b>Expect one exception after the verdict</b> on an
         * integrated server: quitting from inside a tick leaves it mid-tick.</p>
         */
        void quit();
    }

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
        String skippedBecause(Host host) {
            if (only != null && host.topology() != only) {
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

    /** Written from both ticks. @see ConnectionProbe */
    private static final Map<String, Boolean> PASSED = new ConcurrentHashMap<>();

    static {
        for (Check check : CHECKS) PASSED.put(check.name, false);
    }

    private static void pass(String check) {
        if (Boolean.TRUE.equals(PASSED.get(check))) return;
        PASSED.put(check, true);
        CrystalGuiCore.LOGGER.info("[probe] OK {}", check);
    }

    private static boolean done(String check) {
        return Boolean.TRUE.equals(PASSED.get(check));
    }

    // ── State ───────────────────────────────────────────────────────────────────────────────────

    private static final int WINDOW_ID = 4242;

    /** Long enough for a world to load, a handshake to finish and a listing to cross. */
    private static final int DEADLINE_TICKS = 20 * 90;

    /** So the watcher's two reports are two events rather than one coalesced change. */
    private static final int BETWEEN_WRITES = 40;

    private static final String SHARED_FILE = "two-client-probe.txt";

    private static volatile ServerUiSession<UIElement, Object> server;
    private static volatile ClientUiSession<UIElement, Object> client;

    private static Slider serverSlider;

    private static InMemoryTransport<Object>[] extraLink;
    private static ProtocolConnection<Object> extraServer;
    private static ProtocolConnection<Object> extraClient;

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
    /** @see #isDrivingDesktop() */
    private static volatile boolean drivingDesktop;

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

    private ConnectionProbe() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Longer than the in-tick deadline and shorter than the build's, so the probe reports first. */
    private static final long WATCHDOG_MILLIS = 150_000L;

    private static volatile boolean armed;

    /**
     * <b>Guarantees the run ends.</b> Called once by a host when it registers the probe.
     *
     * <p>The in-tick deadline covers a check that stalls. It cannot cover a tick that is never
     * delivered — a hook registered behind one that threw, a client that never reaches the phase that
     * drives it — and that case is the one that actually happened: a client sat with a world loaded and
     * no verdict until somebody closed the window by hand. A thread owes nothing to the game loop.</p>
     *
     * <p>{@code halt} rather than an orderly exit: this fires only when the orderly path has already
     * failed to happen, and a shutdown hook on a half-driven client is exactly where it would hang
     * again. The report is written first, so the build reads a FAIL with the checklist on it rather
     * than "no report", which would be true but far less useful.</p>
     */
    public static synchronized void arm() {
        if (!enabled() || armed) return;
        armed = true;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (reported) return;
            String text = "=========== CrystalGUI connection probe: NO VERDICT ==========="
                    + System.lineSeparator()
                    + "The probe was armed and never reached a verdict in "
                    + (WATCHDOG_MILLIS / 1000) + "s." + System.lineSeparator()
                    + "Nothing below is a pass; the run is being halted so it cannot hold the build open."
                    + System.lineSeparator() + checklist();
            CrystalGuiCore.LOGGER.error("[probe] {}{}", System.lineSeparator(), text);
            ProbeReport.write(REPORT_PROPERTY, false, text);
            System.out.flush();
            System.err.flush();
            Runtime.getRuntime().halt(1);
        }, "cgui-probe-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        CrystalGuiCore.LOGGER.info("[probe] armed; it will quit within {}s whatever happens",
                WATCHDOG_MILLIS / 1000);
    }

    /** The checklist with no host to ask, for the watchdog. @see #render */
    private static String checklist() {
        List<String> lines = new ArrayList<>();
        for (Check check : CHECKS) lines.add((done(check.name) ? "OK   " : "--   ") + check.name);
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * Whether the probe is the one asking for the desktop right now.
     *
     * <p><b>A host refuses every other request while a run is in progress</b>, and that is not tidiness:
     * opening the desktop installs the client window host, which claims {@code ui/openWindow} from the
     * probe's session mid-check and ends the run on an {@code IllegalStateException} out of the router.
     * A probe run opens a real window somebody can see, and two different keys reached that path —
     * the desktop keybind and the worked example's own — so gating the callers one at a time was
     * treating symptoms. The host gates the door instead.</p>
     */
    public static boolean isDrivingDesktop() {
        return drivingDesktop;
    }

    // ── The server half ─────────────────────────────────────────────────────────────────────────

    /** Once per server tick. */
    public static void serverTick(Host host) {
        if (!enabled() || reported) return;

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
            ProtocolConnection<Object> connection = host.connectionToFirstPlayer();
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
            StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
            args.putString("from", "server");
            server.call("probe/ping", args,
                    result -> {
                        if ("server".equals(result.getString("pong", ""))) pass(CALL);
                    },
                    error -> CrystalGuiCore.LOGGER.error("[probe] call failed: {}", error));
            return;
        }
        if (!done(CALL)) return;

        // C2 -- a child added AFTER open, which used to need a whole re-open. Inserted at index 0, so
        // every id after it shifts and a later state update has to still land on the right element.
        if (!reshapeSent) {
            reshapeSent = true;
            server.root().insertAt(0, new UIText("added after open"));
            CrystalGuiCore.LOGGER.info("[probe] inserted a child at index 0 — every id after it shifts");
            return;
        }

        // C1 -- a second viewer on the LIVE session. Its far end is in-memory because a single-player
        // world has one connection; the fan-out path itself is the real one.
        if (done(RESHAPE) && !fanoutStarted) {
            fanoutStarted = true;
            extraLink = InMemoryTransport.pair();
            extraServer = Protocols.open(extraLink[0], PlainOps.INSTANCE, () -> { }, "probe-viewer");
            extraClient = Protocols.open(extraLink[1], PlainOps.INSTANCE, () -> { }, null);
            ClientUiSession<UIElement, Object> viewer =
                    new ClientUiSession<>(new UIElementMirror<>(extraClient.ops()), extraClient);
            viewer.onWindowOpened(root -> {
                if (root != null) pass(FANOUT);
            });
            server.addViewer(extraServer);
            CrystalGuiCore.LOGGER.info("[probe] added a second viewer; count={}", server.viewerCount());
        }
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

        ServerUiSession<UIElement, Object> session = new ServerUiSession<>(
                WINDOW_ID, new UIElementTreeSource(root),
                new UIElementMirror<>(connection.ops()), connection);
        session.on(button, Button.ACTIVATE, ctx -> pass(EVENT));
        session.open();
        server = session;
        CrystalGuiCore.LOGGER.info("[probe] server session opened on the real connection, {} children",
                root.children().size());
    }

    // ── The client half ─────────────────────────────────────────────────────────────────────────

    /** Once per client tick. */
    public static void clientTick(Host host) {
        if (!enabled() || reported) return;

        // COUNTED BEFORE THE WORLD, so a run that never gets into one SAYS SO. Returning early on
        // "not in a world yet" without a clock is how the probes this replaces sat at a main menu
        // indefinitely: nothing failed, nothing passed, and the Gradle task simply never came back.
        if (!host.inWorld()) {
            // AND IT GETS ITSELF THERE. Six probes ago this needed somebody to load a world by hand,
            // which is why none of them could be a build task. @see Host#enterWorld
            if (!worldRequested) worldRequested = host.enterWorld();
            // SAID OUT LOUD, because the alternative is what this cost once: a run sat in this branch
            // producing not one line, and "is the probe even being ticked" was unanswerable from a log.
            if (++ticks % 100 == 0) {
                CrystalGuiCore.LOGGER.info("[probe] waiting for a world ({} ticks; asked={})",
                        ticks, worldRequested);
            }
            if (ticks > DEADLINE_TICKS) {
                finish(host, false, worldRequested
                        ? "asked for a world and never got into one"
                        : "never got as far as asking for a world");
            }
            return;
        }

        // The two-client roles never open a session: they are about the workspace and nothing else.
        if (ROLE != Role.NONE) {
            driveRole(host, ROLE);
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
        // even be closed by hand. A diagnostic may not trap the person watching it.
        if (host.screenIsUp() && !done(WRITE)) {
            if (screenCloses < MAX_SCREEN_CLOSES) {
                screenCloses++;
                host.closeScreen();
            } else if (screenCloses == MAX_SCREEN_CLOSES) {
                screenCloses++;
                CrystalGuiCore.LOGGER.warn("[probe] a screen keeps re-opening after {} attempts; "
                        + "leaving it alone. The window is yours again -- this run will time out and "
                        + "quit on its own.", MAX_SCREEN_CLOSES);
            }
        }

        if (client == null) {
            ProtocolConnection<Object> connection = host.clientConnection();
            if (connection == null) {
                if (++ticks % 100 == 0) {
                    CrystalGuiCore.LOGGER.info("[probe] in a world, waiting for a connection ({} ticks)",
                            ticks);
                }
                if (ticks > DEADLINE_TICKS) finish(host, false, "no client connection");
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
            CrystalGuiCore.LOGGER.info("[probe] session checks done; handing ui/openWindow back so the "
                    + "desktop can open");
            return;
        }
        // THE REPORT COMES FIRST, because the early return below used to skip it: a client whose tree
        // never arrived sat silent for ever, and "where did it stop" was unanswerable from the log.
        if (ticks % 60 == 0) report(host, "waiting");

        if (detached) {
            driveLoopAlive(host);
        } else if (client.root() != null) {
            checkDescribedState();
            checkDelta();
            pressWhenReady();
            checkReshape();
            driveFiles(host);
        }
        if (everythingRunnableIsDone(host)) finish(host, true, "");
        else if (ticks > DEADLINE_TICKS) finish(host, false, "timed out");
    }

    private static void openClient(ProtocolConnection<Object> connection) {
        UIElementRegistry.bootstrap();
        ClientUiSession<UIElement, Object> session =
                new ClientUiSession<>(new UIElementMirror<>(connection.ops()), connection);
        session.onWindowOpened(root -> {
            if (root != null) pass(TREE);
            CrystalGuiCore.LOGGER.info("[probe] tree rebuilt: {} children",
                    root == null ? -1 : root.children().size());
        });
        session.onCall("probe/ping", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("pong", args.getString("from", "?"));
            respond.ok(out);
        });
        client = session;
        // LAST, and it is what releases the server half. @see #serverTick
        clientReady = true;
        CrystalGuiCore.LOGGER.info("[probe] client session listening; the server may open its window");
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
    private static Workspace workspace(Host host) {
        if (files != null) return files;
        ProtocolConnection<Object> connection = host.clientConnection();
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
    private static void driveFiles(Host host) {
        if (!done(RESHAPE)) return;
        Workspace live = workspace(host);
        if (live == null) return;

        if (!listingAsked) {
            listingAsked = true;
            live.files().list(project())
                    .then(listing -> {
                        CrystalGuiCore.LOGGER.info("[probe] listed {} entries from the {} server",
                                listing.entries().size(),
                                host.topology().name().toLowerCase(Locale.ROOT));
                        if (!listing.entries().isEmpty()) pass(LISTING);
                    })
                    .onError(failure ->
                            CrystalGuiCore.LOGGER.error("[probe] listing failed: {}", failure.code()));
            return;
        }
        if (!done(LISTING) || writeStarted) return;

        // Read, write CONDITIONALLY on the etag that read handed back, re-read, put it back. The etag
        // is the whole point: the server re-stats before it writes, so a file that moved underneath
        // this client is refused rather than clobbered.
        writeStarted = true;
        Resource readme = Resource.of(CgPath.of(WorkspaceHost.DEFAULT_PROJECT_ID, "README.md"));
        live.files().readWhole(readme)
                .onError(f -> CrystalGuiCore.LOGGER.error("[probe] README read failed: {}", f.code()))
                .then(first -> {
                    String before = new String(first.bytes(), StandardCharsets.UTF_8);
                    String edited = "#!" + before.substring(Math.min(1, before.length()));
                    live.files().write(readme, edited.getBytes(StandardCharsets.UTF_8), first.etag())
                            .onError(f -> CrystalGuiCore.LOGGER.error(
                                    "[probe] conditional write failed: {} — NO_PERMISSIONS here means "
                                            + "the op check is working and the player is not an op",
                                    f.code()))
                            .then(etag -> live.files().readWhole(readme)
                                    .onError(f -> CrystalGuiCore.LOGGER.error(
                                            "[probe] re-read failed: {}", f.code()))
                                    .then(second -> {
                                        String after = new String(second.bytes(), StandardCharsets.UTF_8);
                                        if (!after.startsWith("#!")) return;
                                        // Put it back, so a repeat run starts from the same file.
                                        live.files().write(readme,
                                                        before.getBytes(StandardCharsets.UTF_8),
                                                        second.etag())
                                                .then(e -> pass(WRITE))
                                                .onError(f -> CrystalGuiCore.LOGGER.error(
                                                        "[probe] restore failed: {}", f.code()));
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
    private static void driveLoopAlive(Host host) {
        if (done(LOOP_ALIVE)) return;
        if (host.topology() != Topology.INTEGRATED) return;

        if (!desktopOpened) {
            desktopOpened = true;
            drivingDesktop = true;
            host.openDesktop();
            return;
        }
        if (!host.screenIsUp() || loopAskStarted) return;

        loopAskStarted = true;
        Workspace live = workspace(host);
        if (live == null) return;
        live.files().list(project())
                .then(listing -> {
                    CrystalGuiCore.LOGGER.info("[probe] a round trip completed with the desktop UP "
                            + "({} entries) — the game loop is still turning", listing.entries().size());
                    pass(LOOP_ALIVE);
                })
                .onError(f -> CrystalGuiCore.LOGGER.error(
                        "[probe] the listing with the desktop up failed: {} — if it TIMED OUT, the "
                                + "screen is pausing the game and stopping the server that answers it",
                        f.code()));
    }

    // ── The second client ───────────────────────────────────────────────────────────────────────

    /**
     * The writer creates a file and later edits it; the watcher reports what reached it.
     *
     * <p>Neither talks to the other: the server is the only thing between them, which is the point.</p>
     */
    private static void driveRole(Host host, Role role) {
        ticks++;
        Workspace live = workspace(host);
        if (live == null) {
            if (ticks > DEADLINE_TICKS) finish(host, false, "joined, but no connection");
            return;
        }
        if (role == Role.WRITER) writerStep(host, live);
        else watcherStep(host, live);

        if (ticks > DEADLINE_TICKS) finish(host, false, "timed out");
    }

    private static void writerStep(Host host, Workspace live) {
        Resource shared = Resource.of(CgPath.of(WorkspaceHost.DEFAULT_PROJECT_ID, SHARED_FILE));

        if (!writerCreated) {
            if (writerCreating) return;
            writerCreating = true;
            live.files().write(shared, "first\n".getBytes(StandardCharsets.UTF_8), null)
                    .then(etag -> {
                        CrystalGuiCore.LOGGER.info("[probe] wrote {} — the watcher should hear about it",
                                SHARED_FILE);
                        writerCreated = true;
                        writerCreatedAt = ticks;
                    })
                    .onError(f -> {
                        CrystalGuiCore.LOGGER.error("[probe] create failed: {} — NO_PERMISSIONS means "
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
                        CrystalGuiCore.LOGGER.info("[probe] edited {}", SHARED_FILE);
                        writerEdited = true;
                    })
                    .onError(f -> {
                        CrystalGuiCore.LOGGER.error("[probe] edit failed: {}", f.code());
                        writerEditing = false;
                    });
            return;
        }
        // The writer asserts nothing itself -- WATCH is the watcher's check. It reports and stops.
        CrystalGuiCore.LOGGER.info("[probe] writer done; the watcher's log is the verdict");
        finish(host, true, "");
    }

    private static void watcherStep(Host host, Workspace live) {
        if (!watching) {
            watching = true;
            // RECURSIVE, because the writer's file is at the project root and a watch that did not
            // descend would be a different assertion by accident.
            live.watch(project(), true).onChanged.connect(changes -> {
                for (FsMessages.FileChange change : changes) {
                    if (!change.path().endsWith(SHARED_FILE)) continue;
                    HEARD.add(change.kind().name() + "@" + ticks);
                    CrystalGuiCore.LOGGER.info("[probe] heard {} on {} at tick {}",
                            change.kind(), change.path(), ticks);
                }
            });
            CrystalGuiCore.LOGGER.info("[probe] watching the project — start the writer now if you have "
                    + "not; a change before this line is one nobody was subscribed to");
            return;
        }
        // TWO of them: a create and a modify. One would pass against a server that reported the first
        // change and then stopped, which is what a coalescing bug produces.
        if (HEARD.size() >= 2) {
            pass(WATCH);
            finish(host, true, "");
        }
    }

    // ── Reporting ───────────────────────────────────────────────────────────────────────────────

    /** Whether every check that means anything HERE has passed. A skip is not a pass and not a block. */
    private static boolean everythingRunnableIsDone(Host host) {
        for (Check check : CHECKS) {
            if (check.skippedBecause(host) != null) continue;
            if (!done(check.name)) return false;
        }
        return true;
    }

    /** The checklist as it stands, one line per check, with a skip printed as a skip and its reason. */
    private static String render(Host host, String prefix) {
        List<String> lines = new ArrayList<>();
        lines.add("=========== CrystalGUI connection probe: " + prefix + " on the "
                + host.topology().name().toLowerCase(Locale.ROOT) + " server ===========");
        for (Check check : CHECKS) {
            String skipped = check.skippedBecause(host);
            if (skipped != null) lines.add("SKIP " + check.name + " (" + skipped + ")");
            else lines.add((done(check.name) ? "OK   " : "--   ") + check.name);
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static void report(Host host, String prefix) {
        CrystalGuiCore.LOGGER.info("[probe] {}{}", System.lineSeparator(), render(host, prefix));
    }

    private static void finish(Host host, boolean passed, String why) {
        reported = true;
        String text = render(host, passed ? "PASS" : "FAIL (" + why + ")");
        if (passed) {
            CrystalGuiCore.LOGGER.info("[probe] PASS — every check that means anything on a {} server "
                    + "crossed a real connection{}{}", host.topology(), System.lineSeparator(), text);
        } else {
            CrystalGuiCore.LOGGER.error("[probe] FAIL ({}) — the first '--' below is the check that "
                    + "stalled{}{}", why, System.lineSeparator(), text);
        }
        ProbeReport.write(REPORT_PROPERTY, passed, text);
        host.quit();
    }
}
