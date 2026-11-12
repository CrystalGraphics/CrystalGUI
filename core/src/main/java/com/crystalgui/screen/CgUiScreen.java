package com.crystalgui.screen;

import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.storage.CrystalGuiDownloads;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopWindowMount;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.AppDocument;
import com.crystalgui.desktop.app.ServerWindowHost;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.render.UiPresenter;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.core.settings.Preferences;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.viewport.CgUiViewport;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * <b>The client's one desktop, and its screen.</b> A game builds and throws away its screen object constantly, so the
 * desktop, its windows and the applications in them live here and outlive every screen: they paint at CrystalGraphics'
 * GUI stages, take input at its {@code SCREEN} input stage, and put the desktop up as the engine's screen
 * ({@code CgScreens}).
 *
 * <pre>{@code
 * CgUiScreen.install();             // once, at client start: what CgUiClientLifecycle does
 * CgUiScreen.open();                // the Show Desktop key: the desktop, nothing on it touched
 * CgUiScreen.openApplication();     // the primary application forward, launched if nothing runs it
 * CgUiScreen.close();               // it plays itself off, then its screen closes
 * CgUiScreen.dispose();             // game shutdown
 * }</pre>
 *
 * <p>What a host or a tool reads and adds:</p>
 *
 * <pre>{@code
 * if (CgUiScreen.mode() == CgUiScreen.Mode.HUD) ...           // decided once a host frame
 * CgUiScreen.run(() -> desktop.minimise(window));             // on the desktop's document, waited for
 * Runnable off = CgUiScreen.onHotKey(key -> hud.key(key));    // before the desktop, unless a text field has focus
 * Runnable off = CgUiScreen.onKeyLeft(key -> hud.key(key));   // what the desktop left, before the game
 * }</pre>
 *
 * <ul>
 *   <li>Nothing opens while CrystalGraphics' loading screen shows: the open is dropped, and logged.</li>
 *   <li>Closing the screen destroys nothing: the desktop, its windows and every running application outlive it. Only
 *       {@link #dispose} ends them, at shutdown.</li>
 *   <li>{@link #tick} runs at the end of every host frame (the client lifecycle's {@code onFrame}): it reads the game,
 *       binds the workspace to the connection, retries a launch and decides the next frame's {@link Mode}. A host
 *       opening an application before its first frame calls it once itself.</li>
 *   <li>Static and the render thread's; what the desktop's document writes back is volatile.</li>
 * </ul>
 */
public final class CgUiScreen {

    /** The order the desktop paints and takes input at, on {@code HUD} and {@code SCREEN}: the game's own interface. */
    public static final int ORDER = CgRenderStage.VIEWPORT_WIDGETS;

    /** The class the desktop's document carries: a theming hook. */
    public static final String ROOT_CLASS = "__host-root__";

    /** The theme a first run starts with: the editor's own default. */
    public static final String DEFAULT_THEME = "crystalgui:crystal-dark";

    /** The scale a host starts with; the user's own is {@code Preferences.current().uiScale()}, which defaults to it. */
    public static final float UI_SCALE = 2f;

    /** What the desktop is showing this host frame. */
    public enum Mode {
        /** Our screen is up: the whole desktop, taking input. */
        DESKTOP,
        /** Another screen is up over pinned windows: the window layer, taking the presses that land on it. */
        OVERLAY,
        /** No screen is up and windows are pinned: the window layer over the game, taking no input. */
        HUD,
        /** Nothing shows. */
        NONE;

        /**
         * What the desktop shows, from what is up and what the desktop holds.
         *
         * @param ourScreenUp the desktop's screen is the one in front
         * @param anyScreenUp some screen is up, ours or another's
         * @param attached    the desktop is in its document's tree
         * @param pinned      a window is pinned
         */
        public static Mode of(boolean ourScreenUp, boolean anyScreenUp, boolean attached, boolean pinned) {
            if (ourScreenUp) return DESKTOP;
            if (!attached || !pinned) return NONE;
            return anyScreenUp ? OVERLAY : HUD;
        }

        /** Whether it takes input. */
        public boolean isInteractive() {
            return this == DESKTOP || this == OVERLAY;
        }

        /** Whether menus, dialogs and tooltips paint with it. */
        public boolean paintsTopLayer() {
            return isInteractive();
        }

        /** Whether it is the whole desktop, taskbar and all, rather than the window layer alone. */
        public boolean coversSurface() {
            return this == DESKTOP;
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("crystalgui.startup.trace");
    /** How long a close waits for the desktop to play itself off: past it, the screen closes whatever still plays. */
    private static final long LEAVE_LONGEST_NANOS = 1_000_000_000L;
    /** The key category the desktop's keys are declared in. */
    private static final String KEY_CATEGORY = "crystalgui:keys";
    /** Under which the desktop's arrangement is recorded. */
    private static final String DESKTOP_ID = "client";

    private static volatile boolean installed;
    /** The stages, the input stage and the key outlive a dispose: registered once, they read what is installed. */
    private static boolean attached;
    /** The one input router, at the {@code SCREEN} input stage. */
    private static final CgUiScreenInput INPUT = new CgUiScreenInput();
    /** {@link CgFrameClock#frameIndex()} of the last frame an arm painted in. Render thread. */
    private static long paintedFrame = -1L;
    /** A probe's one-shot paint in place of the next. Render thread. */
    @Nullable
    private static BiConsumer<CgStageFrame, UiPresenter> replacement;

    @Nullable
    private static volatile GameState game;
    @Nullable
    private static volatile UIDocument document;
    @Nullable
    private static volatile Desktop desktop;
    @Nullable
    private static volatile DesktopConnection connection;
    @Nullable
    private static volatile CgUiViewport viewport;
    @Nullable
    private static volatile DesktopContent[] contents;
    /** What the applications that run in documents of their own are framed from, before the desktop. */
    private static final ApplicationDocuments appDocuments = new ApplicationDocuments();
    /** The primary application, once launched. Written on the document. */
    @Nullable
    private static volatile Application primary;

    /** What this host frame shows. Decided at the end of the last, and when the desktop's own screen comes or goes. */
    private static volatile Mode mode = Mode.NONE;
    private static boolean foreignScreenWasUp;

    /** The key asked for the primary application forward; consumed once it is raised. */
    private static volatile boolean raiseOnShow;
    /** A launch was wanted and could not be done; retried each host frame until a window exists. */
    private static volatile boolean launchPending;
    /** Whether the last session's applications were relaunched. On the document. */
    private static boolean sessionRestored;

    /** The close waiting for the desktop to play itself off, and since when. */
    @Nullable
    private static volatile Runnable closing;
    private static volatile long closingSince;
    /** The desktop has played itself off. Written on the document. */
    private static volatile boolean left;
    /** Whether the desktop is on screen, so a reopen plays it in only from off screen. The document's. */
    private static boolean onScreen = true;

    @Nullable
    private static DesktopRehearsal rehearsal;
    private static boolean rehearsed;
    private static volatile boolean painted;
    private static long traceNanos;

    /** The desktop as the engine's screen: what opens it, hears it come and go, and takes its Escape. */
    private static final CgScreens.Owner OWNER = new CgScreens.Owner() {
        @Override
        public void shown() {
            CgUiScreen.shown();
        }

        @Override
        public void hidden() {
            CgUiScreen.hidden();
        }

        @Override
        public void escape() {
            close();
        }

        /** It covers the window, so the HUD drawn over screens (1.21.6 to 26.0) would land on it. */
        @Override
        public boolean hidesHud() {
            return true;
        }

        @Override
        public boolean textInputArea(float[] out) {
            float[] area = CgUiScreen.textInputArea();
            if (area == null) return false;
            System.arraycopy(area, 0, out, 0, 4);
            return true;
        }
    };

    private CgUiScreen() {
    }

    /** Declares this client's desktop: its stages, its input and its key. Once, at client start; idempotent. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        if (attached) return;
        attached = true;
        CgInputStage.SCREEN.register(ORDER, INPUT);
        CgRenderStage.HUD.register(ORDER, frame -> paint(frame, false));
        CgRenderStage.SCREEN.register(ORDER, frame -> paint(frame, true));
        // Touches no window, so a desktop left with everything minimised comes back that way
        CgKeyBindings.declare("crystalgui:desktop", CgKeyCodes.KEY_LMENU, KEY_CATEGORY, () -> {
            if (installed) open();
        });
    }

    public static boolean isInstalled() {
        return installed;
    }

    // ── Opening and closing ─────────────────────────────────────────────────────────────────────

    /** Puts the desktop up, touching no window on it. Client thread. */
    public static void open() {
        requireInstalled();
        if (mayOpen()) CgScreens.open(OWNER);
    }

    /**
     * Puts the desktop up with the primary application forward: restored, shown, raised and given the keyboard,
     * launched if nothing runs it, retried each host frame until a window exists. Client thread.
     */
    public static void openApplication() {
        requireInstalled();
        if (!mayOpen()) return;
        raiseOnShow = true;
        launchPending = true;
        CgScreens.open(OWNER);
    }

    /** {@link #open} while the loading screen shows, for a host whose first frame is the desktop. Client thread. */
    public static void openBehindLoadingScreen() {
        requireInstalled();
        CgScreens.open(OWNER);
    }

    /** Closes the desktop's screen once the desktop has played itself off; at once with animations off. Client thread. */
    public static void close() {
        requireInstalled();
        if (!requestHide(() -> CgScreens.close(OWNER))) CgScreens.close(OWNER);
    }

    /** Whether the desktop's screen is the one in front. */
    public static boolean isOpen() {
        return installed && CgScreens.isOpen(OWNER);
    }

    /** Never while the loading screen shows, which nothing opens over. */
    private static boolean mayOpen() {
        if (!CgLoadingScreen.shown()) return true;
        CrystalGuiCore.LOGGER.info("[cgui] not opening the desktop: the loading screen shows");
        return false;
    }

    /** Its screen is up: builds the desktop the first time and resumes it every time after, a resize included. */
    static void shown() {
        // The render thread's share of an open, which a first open in the game spends most in.
        long opening = CgTrace.stamp(UiTrace.FLOW);
        closing = null;
        left = false;
        if (game == null) build();
        else execute(CgUiScreen::resumeDesktop);
        execute(CgUiScreen::bringForward);
        decideMode(true);
        CgTrace.zoneDone(UiTrace.FLOW, "screen:shown", opening);
    }

    /**
     * Its screen went away, and nothing is destroyed: the desktop records its arrangement and each application its
     * session. A pending launch is abandoned, or it would open a window the next time the desktop appeared.
     */
    static void hidden() {
        closing = null;
        left = false;
        launchPending = false;
        if (desktop != null) execute(CgUiScreen::putDesktopAway);
        decideMode(false);
    }

    /** On the document: the windows the HUD hid back, then the desktop, played in only from off screen. */
    private static void resumeDesktop() {
        Desktop shown = desktop;
        // exitHudMode FIRST: it puts back the windows the HUD hid, onto the desktop the resume brings back.
        shown.windowManager().exitHudMode();
        shown.resume();
        if (!onScreen) shown.arrive();
        onScreen = true;
    }

    /** On the document: pinned windows stay over the game; otherwise the desktop is suspended. */
    private static void putDesktopAway() {
        Desktop away = desktop;
        onScreen = false;
        if (away.windowManager().hasPinnedWindows()) away.windowManager().enterHudMode();
        else away.suspend();
    }

    /**
     * The desktop plays itself off screen, then {@code close} runs from a later {@link #tick}. False, having run nothing,
     * when it should close now: nothing is built, or animations are off. Asked again while it plays, true.
     */
    private static boolean requestHide(Runnable close) {
        Desktop leaving = desktop;
        if (leaving == null || !WindowAnimator.isEnabled()) return false;
        if (closing != null) return true;
        left = false;
        closing = close;
        closingSince = System.nanoTime();
        execute(() -> leaving.leave(() -> left = true));
        return true;
    }

    // ── The host frame ──────────────────────────────────────────────────────────────────────────

    /**
     * One host frame's tick, at its end: the game read, a finished close run, the connection and language posted, a
     * pending launch retried, the next {@link Mode} decided. The client lifecycle's {@code onFrame}. Render thread.
     */
    public static void tick() {
        GameState state = game;
        if (state == null) return;
        long ticking = CgTrace.stamp(UiTrace.FLOW);
        state.read();
        Runnable close = closing;
        if (close != null && (left || System.nanoTime() - closingSince > LEAVE_LONGEST_NANOS)) {
            closing = null;
            left = false;
            close.run();
        }
        connection.frame(state);
        if (launchPending) execute(CgUiScreen::bringForward);
        decideMode(CgScreens.isOpen(OWNER));
        CgTrace.zoneDone(UiTrace.FLOW, "screen:tick", ticking);
    }

    /** The next frame's mode, from what is up and what the newest frame says the desktop holds. */
    private static void decideMode(boolean ours) {
        CgUiViewport shown = viewport;
        if (shown == null) {
            mode = Mode.NONE;
            return;
        }
        boolean any = CgScreens.anyOpen();
        boolean foreignUp = any && !ours;
        if (foreignUp != foreignScreenWasUp) {
            foreignScreenWasUp = foreignUp;
            INPUT.foreignScreenChanged(foreignUp);
        }
        Boolean attachedNow = shown.readout(DesktopContent.ATTACHED);
        // Before the first commit the desktop is attached and holds nothing pinned, as it was built.
        mode = Mode.of(ours, any, attachedNow == null || attachedNow, Boolean.TRUE.equals(shown.readout(DesktopContent.PINNED)));
    }

    /** What the desktop shows this host frame. */
    public static Mode mode() {
        return mode;
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    /** Builds the desktop behind the loading screen, so the first open only resumes it. Render thread. */
    public static void preload() {
        requireInstalled();
        if (game != null) return;
        build();
        GameState state = game;
        UIDocument built = document;
        execute(() -> built.update(state.surfaceWidth(), state.surfaceHeight()));
    }

    private static void build() {
        long building = CgTrace.stamp(UiTrace.FLOW);
        trace("begin");
        GameState state = new GameState();
        // THE LANGUAGE BEFORE ANY TEXT, so no font family is built in the JVM's and rebuilt a frame later.
        FontFamilyCache.useLocale(state.locale());
        UIDocument built = newDocument();
        Preferences.PUBLISHED.changes().subscribe(
                () -> built.execute(() -> built.boxes().setUiScale(Preferences.current().uiScale())));
        // THE HOST SAYS WHERE crystalgui/ IS AND NOTHING ELSE: the desktop derives its config, its cache and its
        // arrangement from that one directory.
        Desktop made = Desktop.of(built).useStorage(state.installationDirectory());
        CrystalGuiDownloads.LOCATIONS.useCacheRoot(made.cacheRoot());
        made.useLocalWorld(state::localWorldDirectory);
        DesktopWindowMount mount = new DesktopWindowMount(built, () -> appDocuments.openServer("connection"));
        // WHERE A SERVER'S WINDOW IS OFFERED FIRST: the primary application takes what it recognises and hands the
        // rest back, so a client with it closed still gets every window.
        DesktopConnection wire = new DesktopConnection(built, mount, () -> {
            Application app = primary;
            if (!(app instanceof ServerWindowHost host)) return null;
            // Built on the application's own thread: the workbench's panels are its.
            AppDocument home = made.applications().documentOf(app);
            WindowMount offered = home == null ? host.windowMount(mount) : home.ask(() -> host.windowMount(mount));
            return offered == null ? null : new DesktopWindowMount.Preferred(offered, home);
        });
        made.useWorkspace(wire::workspace);
        made.useConnection(wire::bound);
        made.persistAs(DESKTOP_ID);

        game = state;
        document = built;
        desktop = made;
        connection = wire;
        made.applications().useDocuments(appDocuments);
        viewport = CgUiViewport.open(built, CgUiViewport.Threading.fromFlags(), "desktop");
        Mode[] modes = Mode.values();
        DesktopContent[] byMode = new DesktopContent[modes.length];
        for (Mode each : modes) byMode[each.ordinal()] = new DesktopContent(made, built, each);
        contents = byMode;
        trace("built");
        CgTrace.zoneDone(UiTrace.FLOW, "screen:build", building);
    }

    /**
     * A document as the desktop and every application have it: at the user's scale, with the themes' sheets and the
     * theme in force. The caller follows the scale preference and gives the document a viewport.
     */
    static UIDocument newDocument() {
        UIDocument built = new UIDocument();
        // Logical pixels to the surface's: the user's preference, on the box tree's root transform and nowhere else.
        built.boxes().setUiScale(Preferences.current().uiScale());
        // NOT INSTALLED FOR YOU: without the sheets nothing matches, and without a theme every token falls back.
        UiThemeManager themes = UiThemeManager.getInstance();
        themes.installInto(built.styles());
        ThemeRegistry.registerBuiltins();
        if (themes.activeThemeId() == null) themes.setTheme(DEFAULT_THEME);
        built.addClass(ROOT_CLASS);
        return built;
    }

    /**
     * On the document: the primary application forward, if that was asked for; launched if nothing runs it. The bare
     * desktop key launches nothing, so an empty desktop stays empty.
     */
    private static void bringForward() {
        Desktop shown = desktop;
        if (shown != null && !sessionRestored) {
            // THE LAST SESSION'S APPLICATIONS, once the desktop is first shown: each in its own document, with its state.
            sessionRestored = true;
            connection.bind(game.connection());
            shown.applications().restoreSession(connection.workspace());
        }
        if (shown == null || !raiseOnShow) return;
        if (shown.applications().primary() == null) {
            raiseOnShow = false;
            launchPending = false;
            return;
        }
        // THE WORKSPACE FIRST: the launch reads one, and the first open comes before any tick has bound it.
        connection.bind(game.connection());
        Application app = shown.applications().openPrimary(connection.workspace());
        if (app == null) {
            launchPending = true;
            return;
        }
        launchPending = false;
        raiseOnShow = false;
        WindowFrame window = app.mainWindow().frame();
        if (app != primary) {
            primary = app;
            // Its X quits it: the next open launches a fresh one rather than reporting one that is gone
            window.onDestroyed.connect(() -> {
                if (primary == app) primary = null;
            });
            trace("launch");
        }
        if (window.state() == WindowState.HIDDEN) window.show(true);
        shown.windowManager().activate(window);
    }

    // ── Rehearsing ──────────────────────────────────────────────────────────────────────────────

    /** Rehearses the desktop behind the loading screen, a pass or a few a call; true once over. Render thread. */
    public static boolean rehearseStep() {
        if (!installed || rehearsed || game == null) return true;
        if (rehearsal == null) rehearsal = new DesktopRehearsal();
        if (!rehearsal.step()) return false;
        rehearsal = null;
        rehearsed = true;
        return true;
    }

    // ── Shutdown ────────────────────────────────────────────────────────────────────────────────

    /** Takes the desktop down. Game shutdown only, never a screen close. */
    public static synchronized void dispose() {
        if (!installed) return;
        Runnable down = () -> {
            Desktop gone = desktop;
            if (gone == null) return;
            // The arrangement first, with every window still up
            gone.savePersistedState();
            // Every application quits, not the primary alone: one holding ticks or stage draws releases them here
            for (Application app : new ArrayList<>(gone.applications().running())) gone.applications().dispose(app);
            // A copy: destroying a window takes it out of the list being walked
            for (WindowFrame frame : new ArrayList<>(gone.windowManager().windows())) frame.destroy();
            gone.suspend();
        };
        CgUiViewport shown = viewport;
        if (shown == null) down.run();
        else {
            shown.run(down);
            shown.close();
        }
        appDocuments.dispose();
        installed = false;
        INPUT.reset();
        viewport = null;
        contents = null;
        primary = null;
        desktop = null;
        document = null;
        connection = null;
        game = null;
        painted = false;
        mode = Mode.NONE;
        foreignScreenWasUp = false;
        raiseOnShow = false;
        launchPending = false;
        sessionRestored = false;
        closing = null;
        left = false;
        onScreen = true;
        rehearsal = null;
        rehearsed = false;
        paintedFrame = -1L;
        replacement = null;
    }

    // ── What a host reads ───────────────────────────────────────────────────────────────────────

    /** The desktop's document; null until built. */
    @Nullable
    public static UIDocument document() {
        return document;
    }

    /** What puts the desktop's document on screen; null until built. */
    @Nullable
    public static CgUiViewport viewport() {
        return viewport;
    }

    /** The client's desktop; null until built. */
    @Nullable
    public static Desktop desktop() {
        return desktop;
    }

    /** The workspace on the client's connection; null before a tick has bound one. */
    @Nullable
    public static WorkspaceClient workspace() {
        DesktopConnection wire = connection;
        return wire == null ? null : wire.workspace();
    }

    /** The running primary application; null before one was launched. */
    @Nullable
    public static Application application() {
        return primary;
    }

    /** Whether the desktop has ever painted: a capture reports it beside its picture. */
    public static boolean hasPainted() {
        return painted;
    }

    /** The focus owner's caret area in surface pixels, {@code [x, y, width, height]}, of the frame on screen; or null. */
    @Nullable
    public static float[] textInputArea() {
        CgUiViewport shown = viewport;
        return shown == null ? null : shown.readout(CgUiViewport.TEXT_INPUT_AREA);
    }

    /** Where a host delivers an input event to the desktop by hand, as the {@code SCREEN} input stage does. */
    public static CgSystemInput.Mouse mouse() {
        return input();
    }

    /** {@link #mouse}'s keyboard half: hot keys first, then the desktop. */
    public static CgSystemInput.Keyboard keyboard() {
        return input();
    }

    // ── The document ────────────────────────────────────────────────────────────────────────────

    /** Runs {@code work} on the desktop's document and waits: for a probe or a script, never per frame. */
    public static void run(Runnable work) {
        CgUiViewport shown = viewport;
        if (shown == null) work.run();
        else shown.run(work);
    }

    /** Runs {@code work} on the desktop's document without waiting: a feed that edits the desktop's tree. */
    public static void execute(Runnable work) {
        CgUiViewport shown = viewport;
        if (shown == null) work.run();
        else shown.execute(work);
    }

    // ── Keys around the desktop ─────────────────────────────────────────────────────────────────

    /**
     * Hears each key before the desktop does, unless a text field has focus: for a key the desktop would take itself.
     * True takes it. Render thread.
     *
     * @return what removes the listener
     */
    public static Runnable onHotKey(Predicate<CgSystemInput.Keyboard.Event> listener) {
        return input().onHotKey(listener);
    }

    /**
     * Hears each key the desktop left, before the game does: a HUD's keys that work with the desktop up. True takes
     * it. Render thread.
     *
     * @return what removes the listener
     */
    public static Runnable onKeyLeft(Predicate<CgSystemInput.Keyboard.Event> listener) {
        return input().onKeyLeft(listener);
    }

    /** Offers {@code key} to the {@link #onKeyLeft} listeners in order; true if one took it. */
    public static boolean offerLeftKey(CgSystemInput.Keyboard.Event key) {
        return input().offerLeftKey(key);
    }

    /**
     * Runs {@code paint} once, at the next frame the desktop paints and in its place, with the frame's stage and the
     * document's presenter: a probe presenting a frame itself. Render thread.
     *
     * <pre>{@code
     * CgUiScreen.replaceNextPaint((stage, presenter) -> presenter.presentAgain(stage));
     * }</pre>
     */
    public static void replaceNextPaint(BiConsumer<CgStageFrame, UiPresenter> paint) {
        requireInstalled();
        replacement = paint;
    }

    // ── Painting ────────────────────────────────────────────────────────────────────────────────

    /**
     * A turn at {@code HUD} or {@code SCREEN} ({@code screenStage}), at {@link #ORDER}: one arm a frame, the one the
     * {@link Mode} decided. {@code HUD} paints only the HUD arm; {@code SCREEN} paints whichever is current, so a screen
     * that closed itself while it drew still gets its HUD arm after the {@code HUD} stage's turn has passed. Render
     * thread.
     *
     * <ul>
     *   <li>Once a frame: a frame with a screen up fires both stages, and painting twice lets the second win the hit
     *       test's geometry.</li>
     *   <li>While CrystalGraphics rehearses, {@code SCREEN} draws the throwaway desktop instead.</li>
     *   <li>A fault must not take the game down: it runs inside the game's render loop, where nothing can be closed to
     *       escape it, so the HUD mode is dropped and the windows kept.</li>
     * </ul>
     */
    private static void paint(CgStageFrame stage, boolean screenStage) {
        if (!installed) return;
        if (CgRehearsal.active()) {
            DesktopRehearsal playing = rehearsal;
            if (screenStage && playing != null) playing.paint(stage);
            return;
        }
        CgUiViewport shown = viewport;
        DesktopContent[] byMode = contents;
        if (shown == null || byMode == null) return;
        Mode now = mode;
        long frameIndex = CgFrameClock.frameIndex();
        if (now == Mode.NONE || paintedFrame == frameIndex || !screenStage && now != Mode.HUD) return;
        paintedFrame = frameIndex;
        INPUT.returnUnhandledKeys();
        BiConsumer<CgStageFrame, UiPresenter> replaced = replacement;
        if (replaced != null) {
            replacement = null;
            replaced.accept(stage, shown.presenter());
            return;
        }
        long painting = CgTrace.stamp(UiTrace.FLOW);
        try {
            // THE CLIENTS FIRST: their surfaces have executed when the desktop's frame draws them.
            appDocuments.frame(stage);
            if (shown.frame(stage, byMode[now.ordinal()])) painted = true;
            CgTrace.zoneDone(UiTrace.FLOW, "screen:paint", painting);
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] the desktop's paint failed; leaving HUD mode", failed);
            Desktop gone = desktop;
            if (gone != null) execute(gone.windowManager()::exitHudMode);
        }
    }

    // ── For the package ─────────────────────────────────────────────────────────────────────────

    static CgUiScreenInput input() {
        requireInstalled();
        return INPUT;
    }

    private static void requireInstalled() {
        if (!installed) throw new IllegalStateException("No desktop: call CgUiScreen.install() when the client starts.");
    }

    /** {@code -Dcrystalgui.startup.trace=true}: where the first open's time goes, a line a phase. */
    private static void trace(String phase) {
        if (!TRACE) return;
        long now = System.nanoTime();
        if (traceNanos != 0L) CrystalGuiCore.LOGGER.info("[startup] {} — {} ms", phase, (now - traceNanos) / 1_000_000);
        traceNanos = now;
    }
}
