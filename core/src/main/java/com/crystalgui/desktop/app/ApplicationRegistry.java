package com.crystalgui.desktop.app;

import com.crystalgui.core.storage.CrystalGuiStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.provider.Providers;
import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.dialog.FailureWindow;
import com.crystalgui.desktop.manager.SessionRecord;
import com.crystalgui.desktop.window.StartingMark;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.document.DraggedResources;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.TransferData;

/**
 * What is <b>installed</b> on this desktop, and what is <b>running</b> — the shell's side of an
 * application.
 *
 * <p>One per {@link Desktop}, reached with {@code desktop.applications()}. It is the answer to every
 * question a shell asks about products rather than about windows:</p>
 *
 * <ul>
 *   <li>{@link #installed()} — what a launcher lists. Answerable with nothing running.</li>
 *   <li>{@link #handlerFor} — which product opens this file, and {@link #open} launches it and shows it.</li>
 *   <li>{@link #launch(ApplicationKind, AppContext)} — start one, or activate the instance already
 *       there when the manifest says {@code singleInstance}.</li>
 *   <li>{@link #running()} — every live instance, which is what the taskbar groups by and what a
 *       "quit everything" walks.</li>
 *   <li>{@link #onDidChange} — fired when either list moves, so a launcher redraws itself.</li>
 * </ul>
 *
 * <h3>Nothing installs these; they are discovered</h3>
 *
 * <p>Every {@link ApplicationKinds} service on the classpath is run once against this registry, lazily,
 * on the first question anybody asks — so what a desktop offers is a function of the jars present rather
 * than of which loader started it, and a mod's application arrives through exactly the door ours does.
 * {@link #install} stays public for a manifest built at run time.</p>
 *
 * <h3>Installing is not launching</h3>
 *
 * <p>A manifest is data, so everything above can be answered with nothing running. That is what the
 * shell could not do while an application was a class a host constructed: the only way to know one
 * existed was to build it, and the only way to know what it opened was to build it and ask.</p>
 *
 * <h3>Each instance gets its own corner</h3>
 *
 * <p>{@link #launch} scopes the {@link CgConfigStorage} it is given by the kind's id before handing over a
 * {@link AppContext}, so two applications on one desktop keep separate sessions and preferences with
 * neither of them doing anything about it.</p>
 */
public final class ApplicationRegistry {

    private final Desktop desktop;
    private final List<ApplicationKind> installed = new ArrayList<>();
    private final List<Application> running = new ArrayList<>();
    /** Each running application's own client on the workspace, released when it quits. */
    private final java.util.Map<Application, WorkspaceClient> clients = new java.util.IdentityHashMap<>();
    /** The Not responding box each hung document has open. */
    private final Map<AppDocument, WindowFrame> hangDialogs = new java.util.IdentityHashMap<>();
    /** The Stopped working box each faulted document has open. */
    private final Map<AppDocument, WindowFrame> faultDialogs = new java.util.IdentityHashMap<>();
    /** When each kind last faulted, newest last: three within {@link #LOOP_NANOS} is a crash loop. */
    private final Map<String, java.util.ArrayDeque<Long>> recentFaults = new java.util.HashMap<>();
    /** A third fault of one kind within this long stops offering Restart. */
    static final long LOOP_NANOS = 60_000_000_000L;
    static final int LOOP_FAULTS = 3;
    /** Documents being closed so their application can start again once they are gone. */
    private final Set<AppDocument> restarting = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean watching;
    /** The documents applications of a {@link ApplicationKind#separateDocument() separate} kind run in, until they quit. */
    private final java.util.Map<Application, AppDocument> documentsOf = new java.util.IdentityHashMap<>();
    @Nullable
    private AppDocuments documents;
    /** Kinds already told they need a server, so a host retrying every frame says so once. */
    private final Set<String> refusedForNoServer = new HashSet<>();

    /** Something was installed, launched or quit — what a launcher and a taskbar redraw from. */
    public final Signal.Action onDidChange = new Signal.Action();

    public ApplicationRegistry(Desktop desktop) {
        this.desktop = desktop;
    }

    /** Whether the {@link ApplicationKinds} services have been run against this registry. */
    private boolean bootstrapped;

    /**
     * Runs every {@link ApplicationKinds} service once — what makes this desktop's offering a function
     * of the CLASSPATH rather than of which loader happened to start it.
     *
     * <p>Called at the top of every read below, so a launcher drawing a list, an "open with" lookup and
     * a launch all find the same set with nothing having been installed by hand.</p>
     *
     * <p><b>Loaded with THIS class's loader, never the context one.</b> On 1.7.10 the context
     * classloader is whatever the host left there, and LaunchWrapper's is not the one that defined
     * these classes — a {@code ServiceLoader} pointed at it finds nothing, or finds a second copy of
     * everything. The defining loader is the only one guaranteed to see the jar this interface came
     * from.</p>
     *
     * <p><b>Per registry, not per process</b>, because installing is per desktop: two shells in one
     * installation offer the same products and keep separate running instances.</p>
     */
    public void bootstrap() {
        if (bootstrapped) return;
        // SET BEFORE THE LOOP: a service installs, and `install` bootstraps, so anything else would
        // re-enter here and run every service twice.
        bootstrapped = true;
        // A SERVICE THAT WILL NOT LOAD COSTS ITS OWN PRODUCTS AND NOT THE DESKTOP.
        Providers.forEach(ApplicationKinds.class, ApplicationRegistry.class.getClassLoader(), kinds -> {
            try {
                kinds.register(this);
            } catch (RuntimeException | LinkageError failed) {
                CrystalGuiCore.LOGGER.error("[cgui] the ApplicationKinds service '{}' failed to install "
                        + "its applications: {}", kinds.getClass().getName(), failed.getMessage(), failed);
            }
        }, broken -> CrystalGuiCore.LOGGER.error("[cgui] an ApplicationKinds service could not be loaded; "
                + "its applications are not installed", broken));
    }

    /**
     * Makes an application available on this desktop.
     *
     * <pre>{@code
     * // when the desktop appears, before anything is opened:
     * desktop.applications().install(picker.kind());
     * }</pre>
     *
     * <p><b>Install what you offer as the desktop appears, never on first open.</b> Start lists only what is
     * installed, and a pin saved by an earlier run shows only once its application is: installed lazily, a
     * pinned application has no button until something else opens it.</p>
     *
     * @return a handle that withdraws it. Running instances are left alone: uninstalling is not quitting
     */
    public Disposable install(ApplicationKind kind) {
        if (kind == null) return () -> { };
        bootstrap();
        for (ApplicationKind already : installed) {
            if (already.id().equals(kind.id())) {
                // LAST ONE LOSES, said out loud -- the same rule WorkbenchExtensions follows for the same
                // reason: two manifests under one id is a packaging mistake, and the failure it produces
                // silently is one application's launcher entry opening another's product.
                CrystalGuiCore.LOGGER.warn("[cgui] an application is already installed under '{}'; "
                        + "the second manifest is ignored", kind.id());
                return () -> { };
            }
        }
        kind.freeze();
        installed.add(kind);
        if (autostarted) runAutostart(kind);
        onDidChange.emit();
        return () -> {
            if (installed.remove(kind)) onDidChange.emit();
        };
    }

    @Nullable
    private ApplicationKind primary;

    /**
     * Installs {@code kind} as the desktop's primary application: what a host's "open the application" key launches,
     * and what fills an empty desktop.
     *
     * <pre>{@code
     * public void register(ApplicationRegistry applications) {
     *     applications.installPrimary(MyEditor.KIND);
     *     applications.install(MyViewer.KIND);
     * }
     * }</pre>
     *
     * - The first claim wins; a second is logged and installed as an ordinary application.
     * - With none, the open-application key opens the bare desktop.
     */
    public Disposable installPrimary(ApplicationKind kind) {
        Disposable handle = install(kind);
        if (primary != null) {
            CrystalGuiCore.LOGGER.warn("[cgui] '{}' asked to be the primary application, which '{}' already is",
                    kind.id(), primary.id());
            return handle;
        }
        primary = kind;
        return () -> {
            if (primary == kind) primary = null;
            handle.dispose();
        };
    }

    /** What {@link #installPrimary} declared, or null. */
    @Nullable
    public ApplicationKind primary() {
        bootstrap();
        return primary;
    }

    /** Whether {@link #autostart()} has run. */
    private boolean autostarted;

    /**
     * Runs every installed application's {@link ApplicationKind#autostart} hook, once.
     *
     * <p>Called by the desktop the moment it has storage, since an autostart hook reads its application's
     * own settings. A kind installed afterwards runs its hook on install. A hook that throws costs its own
     * application and not the desktop.</p>
     */
    public void autostart() {
        if (autostarted || desktop.config() == null) return;
        autostarted = true;
        bootstrap();
        for (ApplicationKind kind : List.copyOf(installed)) runAutostart(kind);
    }

    private void runAutostart(ApplicationKind kind) {
        Consumer<AppContext> hook = kind.autostartHook();
        CgConfigStorage storage = desktop.config();
        if (hook == null || storage == null) return;
        try {
            hook.accept(AppContext.of(kind, desktop, null, scoped(kind, storage),
                    cacheFor(kind, desktop.cacheRoot())));
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] the autostart of '{}' failed: {}", kind.id(),
                    failed.getMessage(), failed);
            // A NOTIFICATION, NOT A BOX: nobody asked for this application, so nothing is waiting on it.
            FaultReport report = FaultReport.of(FaultReport.Kind.COULDNT_START, kind.displayName(), failed);
            desktop.notifications().show(Notification.error(report.title()).from(kind.id(), kind.displayName())
                    .withDetail(report.reason()));
        }
    }

    /** Everything installed, in installation order. */
    public List<ApplicationKind> installed() {
        bootstrap();
        return List.copyOf(installed);
    }

    @Nullable
    public ApplicationKind byId(String id) {
        bootstrap();
        for (ApplicationKind kind : installed) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }

    /**
     * Starts one, or brings forward the instance a {@link ApplicationKind#singleInstance()} kind already
     * has.
     *
     * <p>Refuses, with a named reason, when the kind cannot run here: no factory, or a workspace that is
     * not connected under a manifest that says it needs one. Named rather than left to throw out of a
     * factory, because "this needs a world" and "the editor is broken" look identical from the outside
     * and the second is what a crash report says.</p>
     *
     * @return the instance, or null when it could not be started, or ran as an {@link ApplicationKind#action}
     */
    @Nullable
    public Application launch(ApplicationKind kind, AppContext context) {
        bootstrap();
        if (kind.isSingleInstance()) {
            Application already = firstRunning(kind);
            if (already != null) {
                already.activate();
                // AND THE ARGUMENT STILL LANDS. A second launch carrying a file has to open it somewhere,
                // and for a single-instance application that somewhere is the instance already running --
                // which is what a second `open` on macOS and a second command line on Windows both do.
                for (Resource resource : context.open()) already.open(resource);
                return already;
            }
        }
        if (kind.requiresConnection() && context.workspace() == null) {
            // THE FILES LIVE ON THE SERVER, so there is genuinely nothing for a workbench to show
            // without one -- and this is the refusal `ensureEditorWindow` used to make in the 1.7.10
            // screen, which is why it is here rather than in each host. A manifest that says
            // `standalone()` is offered anyway.
            if (refusedForNoServer.add(kind.id())) {
                CrystalGuiCore.LOGGER.warn("[cgui] '{}' needs a server and there is none; it was not "
                        + "launched. The desktop is open and the application is not on it.", kind.id());
            }
            return null;
        }
        if (kind.factory() == null) {
            CrystalGuiCore.LOGGER.warn("[cgui] the application '{}' declares no launch factory", kind.id());
            return null;
        }
        // EVERY APPLICATION HAS A CLIENT OF ITS OWN on the workspace the desktop owns: its replies and events come
        // back to the sequence it launches on, and the others see it by name in presence.
        // MADE ON ITS DOCUMENT'S THREAD when it has one: a client answers where it was made, and a signal belongs to the
        // sequence that first used it.
        WorkspaceClient shared = context.workspace();
        AppContext asked = context;
        AppDocument document = documents != null && kind.hasSeparateDocument() ? documents.open(kind) : null;
        String clientName = kind.displayName();
        WorkspaceClient own = shared == null ? null
                : document == null ? shared.service().client(clientName)
                : document.ask(() -> shared.service().client(clientName));
        if (own != null) {
            context = new AppContext(kind, context.desktop(), own, context.storage(), context.cache(),
                    context.open());
        }
        Application application;
        AppContext launched = context.withDocument(document);
        // On the document's thread outside any frame: without a zone, a launch is a stretch of nothing in the trace.
        long launching = CgTrace.stamp(UiTrace.FLOW);
        try {
            // ITS OWN DOCUMENT BUILDS IT: the tree an application makes is touched by its thread alone.
            application = document == null ? kind.factory().apply(launched)
                    : document.ask(() -> kind.factory().apply(launched));
            CgTrace.zoneDone(UiTrace.FLOW, "app:launch", launching);
        } catch (RuntimeException | LinkageError failed) {
            // AN APPLICATION THAT THROWS COSTS ITS OWN LAUNCH AND NOTHING ELSE. A desktop is a shell:
            // one product failing to start must leave every other window on screen, the same isolation
            // an extension's activate and a dock banner provider each get. A LinkageError is a mod whose
            // dependency is missing, which is the same fault from the user's side.
            CrystalGuiCore.LOGGER.error("[cgui] the application '{}' failed to launch: {}",
                    kind.id(), failed.getMessage(), failed);
            couldntStart(kind, asked, failed);
            if (own != null) own.dispose();
            if (document != null) document.close();
            return null;
        }
        if (application == null) {
            if (own != null) own.dispose();
            if (document != null) document.close();
            return null;
        }
        if (own != null) clients.put(application, own);
        if (document != null) {
            documentsOf.put(application, document);
            document.name(kind.displayName());
            document.hungPoke(() -> showNotResponding(document, application));
            watch();
        }
        refusedForNoServer.remove(kind.id());
        running.add(application);
        // Its main window destroyed is the application quit: closing it under DESTROY_ON_CLOSE, or anything else
        // taking it down. Every application gets this from here, so none hooks its own window.
        AppWindow main = application.mainWindow();
        if (main != null) main.frame().onDestroyed.connect(() -> quit(application));
        onDidChange.emit();
        return application;
    }

    /**
     * Launches {@code kind} as a click on Start, a pin or a key does: behind its starting window when it opts in to one
     * ({@link ApplicationKind#startingWindow}), which opens on this frame while the application builds into it,
     * however long it takes; otherwise built and activated now, as {@link #launch}.
     *
     * <pre>{@code
     * desktop.applications().launchInStartingWindow(CrystalEditor.KIND, workspace);
     * }</pre>
     *
     * <ul>
     *   <li>A single-instance kind already running is brought forward, as {@link #launch} does, with no window.</li>
     *   <li>A kind that cannot start here (no factory, no server it needs) is refused at once, with no window.</li>
     *   <li>Closing the starting window before the build cancels the launch.</li>
     *   <li>For the instance itself, call {@link #launch}: this answers nothing, since nothing exists yet.</li>
     * </ul>
     */
    public void launchInStartingWindow(ApplicationKind kind, @Nullable WorkspaceClient workspace) {
        bootstrap();
        boolean runsAlready = kind.isSingleInstance() && firstRunning(kind) != null;
        boolean refused = kind.factory() == null || (kind.requiresConnection() && workspace == null)
                || desktop.config() == null;
        UIDocument document = desktop.document();
        if (!kind.hasStartingWindow() || runsAlready || refused || document == null) {
            Application launched = launch(kind, workspace);
            if (launched != null) launched.activate();
            return;
        }
        if (desktop.isStarting(kind)) {
            desktop.windowManager().activate(desktop.openStartingWindow(kind));
            return;
        }
        WindowFrame starter = desktop.openStartingWindow(kind);
        boolean[] launched = {false};
        Connection[] waiting = {Connection.DISCONNECTED};
        // ON THE DESKTOP, AGAIN ON EVERY CONNECT: a hook goes with its node, so one on the starter died with a minimise
        // and one on the desktop dies with the desktop's close. Its mark comes in again on the reopen, so the wait
        // starts over too.
        waiting[0] = desktop.whileConnected(() -> {
            UIDocument at = desktop.document();
            if (launched[0] || at == null) return Connection.DISCONNECTED;
            boolean[] stopped = {false};
            int[] frames = {0};
            long asked = System.nanoTime();
            at.animation().every(desktop, delta -> {
                if (stopped[0] || launched[0]) return false;
                // CLOSED: the launch is over
                if (!desktop.isStarting(kind)) {
                    launched[0] = true;
                    waiting[0].disconnect();
                    return false;
                }
                // FLYING TO THE TASKBAR: the build replaces this window, and replacing it mid-flight cuts the flight
                if (starter.isMinimizing() && starter.state() != WindowState.HIDDEN) return true;
                // MINIMISED: no mark to wait for, so it builds now and its window waits minimised
                boolean away = starter.state() == WindowState.HIDDEN;
                // ITS MARK ON SCREEN WHOLE BEFORE THE BUILD, which holds this document while it runs: begun sooner,
                // the mark would stand still part way in
                boolean shown = away || ++frames[0] >= STARTING_FRAMES && StartingMark.settled(starter);
                if (!shown && System.nanoTime() - asked < STARTING_LONGEST_NANOS) return true;
                launched[0] = true;
                waiting[0].disconnect();
                if (!desktop.isStarting(kind)) return false;
                Application application = launch(kind, workspace);
                // No main window took its place: a refusal, a failure, or an application with none
                if (desktop.isStarting(kind)) {
                    desktop.endStartingWindow(kind, application == null ? null : application.mainWindow().frame());
                }
                if (application != null && !away) application.activate();
                return false;
            });
            return () -> stopped[0] = true;
        });
    }

    /** Frames a starting window shows before its application builds: one recorded and presented, then the build. */
    private static final int STARTING_FRAMES = 2;
    /** The longest a build waits for its starting window's mark to come in. */
    private static final long STARTING_LONGEST_NANOS = 3_000_000_000L;

    /**
     * Disposes {@code application} where it was built and waits: on its own document's thread when it has one. For a
     * desktop going down, which must not leave an application holding a tick or a draw.
     */
    public void dispose(Application application) {
        AppDocument document = documentsOf.get(application);
        if (document == null) application.dispose();
        else document.run(application::dispose);
    }

    // ── The session ─────────────────────────────────────────────────────────────────────────────────────────────

    /** What each application last gave {@code AppContext.saveState}, by kind id; the last session's until then. */
    private final Map<String, String> states = new ConcurrentHashMap<>();
    /** The applications the last session left running, not yet relaunched. */
    private List<SessionRecord.App> awaiting = List.of();

    /** The desktop hands over what the last session recorded: the state each application left, and which were running. */
    public void expect(List<SessionRecord.App> recorded) {
        awaiting = List.copyOf(recorded);
        for (SessionRecord.App app : recorded) {
            if (!app.state().isEmpty()) states.put(app.id(), app.state());
        }
    }

    /**
     * Relaunches every application the last session left running, once, each into its own document and with the state
     * it left. One that is not installed, is running already or refuses to launch is skipped.
     *
     * <pre>{@code
     * desktop.applications().restoreSession(workspace);    // when the desktop is first shown
     * }</pre>
     */
    public void restoreSession(@Nullable WorkspaceClient workspace) {
        List<SessionRecord.App> todo = awaiting;
        awaiting = List.of();
        for (SessionRecord.App app : todo) {
            ApplicationKind kind = byId(app.id());
            if (kind == null || !running(kind).isEmpty()) continue;
            try {
                launch(kind, workspace);
            } catch (RuntimeException | LinkageError refused) {
                CrystalGuiCore.LOGGER.error("[cgui] '{}' could not be restored", kind.id(), refused);
                couldntStart(kind, null, refused);
            }
        }
    }

    /** Records the state {@code kindId}'s application gave. Any thread. */
    void rememberState(String kindId, String state) {
        states.put(kindId, state);
    }

    /** What {@code kindId}'s application last gave {@code saveState}, or null. */
    @Nullable
    String stateOf(String kindId) {
        return states.get(kindId);
    }

    /** The running applications, one per kind, with their state: what the session records. The shell's thread. */
    public List<SessionRecord.App> sessionApps() {
        List<SessionRecord.App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Application live : running) {
            String id = live.kind().id();
            if (seen.add(id)) out.add(new SessionRecord.App(id, states.getOrDefault(id, "")));
        }
        return out;
    }

    /** The document {@code application} runs in, or null when it runs in the shell's. */
    @Nullable
    public AppDocument documentOf(Application application) {
        return documentsOf.get(application);
    }

    /** The application that runs in {@code document}, or null. */
    @Nullable
    public Application applicationOf(AppDocument document) {
        for (Map.Entry<Application, AppDocument> entry : documentsOf.entrySet()) {
            if (entry.getValue() == document) return entry.getKey();
        }
        return null;
    }

    /**
     * A value drag from {@code from}'s application was released on the bare desktop: its resources open in a window of
     * that application: its single instance, or a new one.
     */
    void openDropped(AppDocument from, Object payload) {
        DraggedResources dragged = TransferData.find(payload, DraggedResources.class);
        Application source = applicationOf(from);
        CgConfigStorage storage = desktop.config();
        if (dragged == null || source == null || storage == null) return;
        ApplicationKind kind = source.kind();
        launch(kind, new AppContext(kind, desktop, clients.get(source), scoped(kind, storage),
                cacheFor(kind, desktop.cacheRoot()), dragged.resources()));
    }

    /** A value drag was released on {@code target}'s window and no element took it: the application is offered its resources. */
    void offerDropped(AppDocument target, Object payload) {
        DraggedResources dragged = TransferData.find(payload, DraggedResources.class);
        Application receiver = applicationOf(target);
        if (dragged == null || receiver == null) return;
        target.execute(() -> {
            for (Resource resource : dragged.resources()) receiver.open(resource);
        });
    }

    /** Starts the per-frame check of every application's document, once. */
    private void watch() {
        if (watching) return;
        watching = true;
        desktop.everyFrame(delta -> {
            check(System.nanoTime());
            return true;
        });
    }

    /**
     * The shell's thread, once a frame: dims the windows of a document that does not answer and offers its dialog,
     * climbs the close ladder of one being closed, and finishes a close that has ended.
     */
    void check(long now) {
        for (Application application : new ArrayList<>(documentsOf.keySet())) {
            AppDocument document = documentsOf.get(application);
            if (document == null) continue;
            for (Throwable cause; (cause = document.pollFault()) != null; ) fault(document, application, cause, now);
            AppHealth health = document.health();
            boolean moved = health.step(now);
            if (moved) {
                if (health.state() == AppHealth.State.NOT_RESPONDING) document.hungSince(now);
                document.showHealth();
            }
            if (health.state() == AppHealth.State.NOT_RESPONDING && document.hungFor(now) >= AppHealth.HANG_NANOS) {
                showNotResponding(document, application);
            }
            if (health.state() == AppHealth.State.RESPONDING) {
                WindowFrame box = hangDialogs.remove(document);
                if (box != null && box.state() != WindowState.DESTROYED) box.requestClose();
                document.showHealth();
            }
            if (health.ended()) finishClose(document, application);
        }
    }

    /** The Not responding box for {@code document}: Wait, Restart, Close. One per document. */
    private void showNotResponding(AppDocument document, Application application) {
        if (document.health().state() != AppHealth.State.NOT_RESPONDING) return;
        WindowFrame open = hangDialogs.get(document);
        if (open != null && open.state() != WindowState.DESTROYED && !open.isClosing()) {
            desktop.windowManager().activateByUser(open);
            return;
        }
        FaultReport report = document.health().report();
        ApplicationKind kind = application.kind();
        String name = kind.displayName();
        WindowFrame box = FailureWindow.open(desktop, kind, name, name + " is not responding",
                report == null || report.origin().isEmpty() ? "" : "In " + report.origin(), List.of(
                        new FailureWindow.Action("Wait", () -> document.health().patient(System.nanoTime())),
                        new FailureWindow.Action("Restart", () -> restart(document)),
                        new FailureWindow.Action("Close", () -> document.health().close(System.nanoTime()))));
        hangDialogs.put(document, box);
        if (report != null) CrystalGuiCore.LOGGER.warn("[cgui] {}", report.text());
    }

    /**
     * An exception escaped {@code document}'s frame, a window's build or event handler, or {@code restoreState}: its
     * windows stay, dimmed, and the box offers Restart or Close. A third fault of the kind within a minute offers no
     * Restart, so a crash loop ends at the box. Further faults while the box is open are dropped.
     */
    private void fault(AppDocument document, Application application, Throwable cause, long now) {
        if (document.isStoppedWorking()) return;
        ApplicationKind kind = application.kind();
        FaultReport report = FaultReport.of(FaultReport.Kind.STOPPED, kind.displayName(), cause);
        if (recordFault(kind.id(), now) >= LOOP_FAULTS) report = report.as(FaultReport.Kind.KEEPS_STOPPING);
        document.stoppedWorking();
        CrystalGuiCore.LOGGER.error("[cgui] {}", report.text());
        List<FailureWindow.Action> actions = new ArrayList<>();
        if (report.kind() == FaultReport.Kind.STOPPED) {
            actions.add(new FailureWindow.Action("Restart", () -> restart(document)));
        }
        actions.add(new FailureWindow.Action("Close", () -> document.health().close(System.nanoTime())));
        actions.add(details(kind, report));
        WindowFrame box = FailureWindow.open(desktop, kind, report.title(), report.title(), report.reason(), actions);
        faultDialogs.put(document, box);
    }

    /**
     * The launch of {@code kind} failed: its box offers Try again (when {@code asked} is known), Close and Details.
     * The launch that failed was never on the list, so nothing else is touched.
     */
    private void couldntStart(ApplicationKind kind, @Nullable AppContext asked, Throwable cause) {
        UIDocument shell = desktop.document();
        if (shell == null) return;
        FaultReport report = FaultReport.of(FaultReport.Kind.COULDNT_START, kind.displayName(), cause);
        boolean loop = recordFault(kind.id(), System.nanoTime()) >= LOOP_FAULTS;
        FaultReport shown = loop ? report.as(FaultReport.Kind.KEEPS_STOPPING) : report;
        shell.execute(() -> {
            List<FailureWindow.Action> actions = new ArrayList<>();
            if (asked != null && !loop) actions.add(new FailureWindow.Action("Try again", () -> launch(kind, asked)));
            actions.add(new FailureWindow.Action("Close", () -> { }));
            actions.add(details(kind, shown));
            FailureWindow.open(desktop, kind, shown.title(), shown.title(), shown.reason(), actions);
        });
    }

    private static final int DETAIL_LINES = 12;

    /** The Details button: a second box with the report's top lines, and Copy for the whole of it. */
    private FailureWindow.Action details(ApplicationKind kind, FaultReport report) {
        return new FailureWindow.Action("Details", () -> {
            String text = report.text();
            String[] lines = text.split("\n");
            StringBuilder shown = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, DETAIL_LINES); i++) shown.append(lines[i]).append('\n');
            if (lines.length > DETAIL_LINES) shown.append("... ").append(lines.length - DETAIL_LINES).append(" more");
            FailureWindow.open(desktop, kind, report.title(), report.title(), shown.toString(), List.of(
                    new FailureWindow.Action("Copy", () -> CgHostThread.CLIENT.run(() -> CgClipboard.set(text))),
                    new FailureWindow.Action("Close", () -> { })));
        });
    }

    /** Notes a fault of {@code kindId} at {@code now} and answers how many fell within the last minute. */
    int recordFault(String kindId, long now) {
        java.util.ArrayDeque<Long> times = recentFaults.computeIfAbsent(kindId, id -> new java.util.ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > LOOP_NANOS) times.pollFirst();
        times.addLast(now);
        return times.size();
    }

    /** Closes the unresponsive document by the ladder, and starts the application again once it is gone. */
    private void restart(AppDocument document) {
        restarting.add(document);
        document.health().close(System.nanoTime());
    }

    /** The ladder ended: the application's windows go, it is taken off the list, and a restart launches it again. */
    private void finishClose(AppDocument document, Application application) {
        WindowFrame box = hangDialogs.remove(document);
        if (box != null && box.state() != WindowState.DESTROYED) box.destroy();
        WindowFrame fault = faultDialogs.remove(document);
        if (fault != null && fault.state() != WindowState.DESTROYED) fault.destroy();
        ApplicationKind kind = application.kind();
        WorkspaceClient workspace = clients.get(application);
        boolean again = restarting.remove(document);
        for (WindowFrame frame : new ArrayList<>(document.frames())) frame.destroy();
        forget(application);
        if (again) {
            CgConfigStorage storage = desktop.config();
            if (storage != null) {
                launch(kind, new AppContext(kind, desktop, workspace, scoped(kind, storage),
                        cacheFor(kind, desktop.cacheRoot()), List.of()));
            }
        }
    }

    /** Quits {@code application} once its main window is gone: its own {@code dispose()}, then off the list. */
    private void quit(Application application) {
        if (!running.contains(application)) return;
        AppDocument document = documentsOf.get(application);
        // ITS DISPOSE RUNS WHERE IT WAS BUILT, ahead of the document's end.
        if (document == null) application.dispose();
        else document.execute(application::dispose);
        forget(application);
    }

    /**
     * The primary application with a window: the running one, else launched now, synchronously, onto this desktop's
     * storage. What a host's "open the editor" key asks for, before it raises the window.
     *
     * <pre>{@code
     * Application app = desktop.applications().openPrimary(workspace);
     * if (app != null) app.activate();
     * }</pre>
     *
     * <ul>
     *   <li>Null when no primary is installed, or it refused to launch yet (a world it needs is not there): the refusal
     *       is logged at debug under its id, and a host retries next frame.</li>
     *   <li>Launches nothing when one runs, so a second key press raises rather than duplicates.</li>
     * </ul>
     */
    @Nullable
    public Application openPrimary(@Nullable WorkspaceClient workspace) {
        ApplicationKind kind = primary();
        if (kind == null) return null;
        for (Application live : running(kind)) {
            if (live.mainWindow() != null) return live;
        }
        Application launched;
        try {
            launched = launch(kind, workspace);
        } catch (RuntimeException refused) {
            CrystalGuiCore.LOGGER.debug("[cgui] '{}' cannot launch yet", kind.id(), refused);
            return null;
        }
        return launched == null || launched.mainWindow() == null ? null : launched;
    }

    /**
     * Brings back the application if it runs, and launches it if not: what a key bound to it, or a click on
     * its button, means. A second instance comes only from {@link #launch}, as Windows opens one from the jump
     * list and never from the button.
     *
     * <pre>{@code
     * desktop.applications().activateOrLaunch(VfxLab.KIND, workspace);
     * }</pre>
     *
     * <p>Of several running, the one whose window was active most recently. None running, it launches behind a
     * starting window ({@link #launchInStartingWindow}) and answers null, since the instance builds a frame later.</p>
     */
    @Nullable
    public Application activateOrLaunch(ApplicationKind kind, @Nullable WorkspaceClient workspace) {
        Application recent = null;
        int best = Integer.MAX_VALUE;
        List<WindowFrame> mru = desktop.windowManager().registry().mruOrder();
        for (Application live : running(kind)) {
            int at = mru.indexOf(live.mainWindow().frame());
            if (recent == null || (at >= 0 && at < best)) {
                recent = live;
                if (at >= 0) best = at;
            }
        }
        // A WINDOW AT ONCE, the application built into it: what a key or a button asks for
        if (recent == null) {
            launchInStartingWindow(kind, workspace);
            return null;
        }
        recent.activate();
        return recent;
    }

    /**
     * Launches onto <b>this desktop's own storage</b> — the usual call, and the one to reach for.
     *
     * <p>A caller supplies no directory at all: the host told the desktop once, with
     * {@code Desktop.useStorage}. The overloads below exist for a caller that genuinely needs a
     * different one, which is rare and deliberate.</p>
     *
     * <p>Refuses, with a named reason, when nobody has said where this desktop writes — rather than
     * inventing somewhere. An application whose preferences silently go to a directory nobody chose
     * looks exactly like one whose preferences do not work.</p>
     */
    @Nullable
    public Application launch(ApplicationKind kind, @Nullable WorkspaceClient workspace) {
        CgConfigStorage storage = desktop.config();
        if (storage == null) {
            CrystalGuiCore.LOGGER.warn("[cgui] '{}' was not launched: this desktop has nowhere to "
                    + "write. Call Desktop.useStorage(installation) first.", kind.id());
            return null;
        }
        return launch(kind, workspace, storage, desktop.cacheRoot());
    }

    /** Launches with nothing asked of it, and nowhere to cache derived output. */
    @Nullable
    public Application launch(ApplicationKind kind, @Nullable WorkspaceClient workspace,
                              CgConfigStorage storage) {
        return launch(kind, workspace, storage, null);
    }

    /** Launches with nothing asked of it. @param cacheRoot {@code crystalgui/cache}, or null for none */
    @Nullable
    public Application launch(ApplicationKind kind, @Nullable WorkspaceClient workspace,
                              CgConfigStorage storage, @Nullable Path cacheRoot) {
        return launch(kind, AppContext.of(kind, desktop, workspace,
                scoped(kind, storage), cacheFor(kind, cacheRoot)));
    }

    /**
     * Launches with a file to open — and hands it to the instance already running when there is one.
     *
     * @see #handlerFor
     */
    @Nullable
    public Application open(Resource resource, @Nullable WorkspaceClient workspace,
                            CgConfigStorage storage) {
        return open(resource, workspace, storage, null);
    }

    /** As {@link #open(Resource, WorkspaceClient, CgConfigStorage)}, with somewhere to cache. */
    @Nullable
    public Application open(Resource resource, @Nullable WorkspaceClient workspace,
                            CgConfigStorage storage, @Nullable Path cacheRoot) {
        ApplicationKind kind = handlerFor(resource);
        if (kind == null) return null;
        return launch(kind, new AppContext(kind, desktop, workspace, scoped(kind, storage),
                cacheFor(kind, cacheRoot), List.of(resource)));
    }

    /**
     * Which application opens {@code resource} — LaunchServices, from the manifests alone.
     *
     * <p>A running instance is preferred to an installed-but-not-running one when both declare it, so
     * "open with" lands in the window somebody already has open rather than starting a second product
     * over the same workspace.</p>
     */
    @Nullable
    public ApplicationKind handlerFor(Resource resource) {
        bootstrap();
        for (Application live : running) {
            if (live.kind().handles(resource)) return live.kind();
        }
        for (ApplicationKind kind : installed) {
            if (kind.handles(resource)) return kind;
        }
        return null;
    }

    /** Every running instance, in launch order. */
    public List<Application> running() {
        bootstrap();
        return List.copyOf(running);
    }

    /** Every running instance of one kind. */
    public List<Application> running(ApplicationKind kind) {
        List<Application> matches = new ArrayList<>();
        for (Application live : running) {
            if (live.kind().id().equals(kind.id())) matches.add(live);
        }
        return matches;
    }

    @Nullable
    private Application firstRunning(ApplicationKind kind) {
        List<Application> matches = running(kind);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Drops a running instance from the list.
     *
     * <p>Called by the instance from its own {@code dispose()} rather than by the registry wrapping it:
     * an application is quit through the {@link Application} handle a caller already holds, and a
     * registry that only learned about it when <em>it</em> did the quitting would go on listing
     * everything anybody else shut down.</p>
     */
    public void forget(Application application) {
        // FROM ITS OWN THREAD TOO: an instance calls this in its dispose, and the registry is the shell's.
        UIDocument shell = desktop.document();
        if (shell == null) forgetNow(application);
        else shell.execute(() -> forgetNow(application));
    }

    private void forgetNow(Application application) {
        // ITS STICKY NOTIFICATIONS GO WITH IT: there is no window left to act on them.
        desktop.notifications().closeFrom(application.kind().id());
        WorkspaceClient client = clients.remove(application);
        if (client != null) client.dispose();
        AppDocument document = documentsOf.remove(application);
        if (document != null) document.close();
        if (running.remove(application)) onDidChange.emit();
    }

    /** Runs each {@link ApplicationKind#separateDocument() separate} application in a document {@code documents} builds. */
    public void useDocuments(@Nullable AppDocuments documents) {
        this.documents = documents;
    }

    /** D20: each application's own corner of a shared config directory. */
    private static CgConfigStorage scoped(ApplicationKind kind, CgConfigStorage storage) {
        return storage.scoped(CrystalGuiStorage.APPS).scoped(kind.id());
    }

    /**
     * The same corner of {@code cache/}, sanitised by hand.
     *
     * <p>{@link CgConfigStorage#scoped} does this substitution for us; a raw {@link Path} has nobody to
     * do it, and an application id is namespaced ({@code crystalgui:editor}) with a colon that cannot
     * be a directory on Windows.</p>
     */
    @Nullable
    private static Path cacheFor(ApplicationKind kind, @Nullable Path cacheRoot) {
        if (cacheRoot == null) return null;
        return cacheRoot.resolve(CrystalGuiStorage.APPS)
                .resolve(kind.id().replace(':', '.').replace('/', '.'));
    }
}
