package com.crystalgui.desktop.host;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.ServerWindowHost;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * <b>The one desktop a client has, and the application it opens with.</b>
 *
 * <p>A game builds and throws away its screen object constantly — Minecraft constructs a fresh one on
 * every display and on every window resize — so none of this can live on a screen: the desktop, its
 * windows, the unsaved documents in them and the request to bring one forward all have to outlive it.
 * This is where they live.</p>
 *
 * <p>Install once, when the client starts:</p>
 *
 * <pre>{@code
 * HostSession.install(new MyHostServices(), CrystalEditor.KIND);
 * }</pre>
 *
 * <p>Then a host's screen is four calls and owns nothing:</p>
 *
 * <pre>{@code
 * void onScreenShown()  { HostSession.session().shown(); }
 * void onScreenHidden() { HostSession.session().hidden(); }
 * void onFrame() {
 *     HostSession.session().frame(HostSession.session().frameDelta());
 *     // ...then paint through desktop()
 * }
 * void onGameShutdown() { HostSession.session().dispose(); }
 * }</pre>
 *
 * <p>And the two keys a host binds:</p>
 *
 * <pre>{@code
 * void openApp()     { HostSession.session().requestApplication(); showMyScreen(); }
 * void openDesktop() { showMyScreen(); }   // touches nothing that is already open
 * }</pre>
 *
 * <h3>Easy to get wrong</h3>
 *
 * <ul>
 *   <li>{@link #shown()} is not "build" — it is "the surface is up", and it builds only the first time.
 *       Call it from every screen-open, including a resize.</li>
 *   <li>{@link #requestApplication()} is a <em>request</em> and is consumed. Leaving it latched would
 *       re-raise a window on the next resize, which is how a window the player put away comes back.</li>
 *   <li>{@link #hidden()} destroys nothing. Only {@link #dispose()} does, and only at shutdown.</li>
 *   <li>{@link #frame(float)} must run before anything reads {@link #workspace()} — it is what binds a
 *       workspace to a live connection.</li>
 * </ul>
 *
 * <h3>Why a host may not own any of this</h3>
 *
 * <p>It was owned by one, twice — on 1.7.10 and on 1.20.x — and the two copies had already drifted
 * four ways by the time they were compared: the first-run window took 86% of the display on one and
 * 80% on the other, they wrote different CSS classes onto the document, they named the same project
 * two things, and a launch that failed for want of a connection was retried on one and permanent on
 * the other. Each copy was internally consistent, so nothing failed and no test could have seen it.
 * @see com.crystalgui.desktop.host
 */
public final class HostSession {

    /**
     * A theming hook on the document, and the only class this writes.
     *
     * <p>There were two more — a root and an editor class carrying {@code width: 100%; height: 100%}
     * from a host stylesheet — and both were dead. {@code UIDocument.frame} is handed the surface size,
     * so the root has been sized by the engine since the compositor arrived, and {@code ua/workbench.css}
     * has said {@code application { width: 100%; height: 100% }} for as long as there has been an
     * application tag. The 1.20.x host shipped without either rule, which is the proof.</p>
     */
    public static final String ROOT_CLASS = "__host-root__";

    /**
     * How much of the surface a first-run window takes.
     *
     * <p>Large enough to work in, small enough that the desktop is visibly underneath — showing that
     * there <em>is</em> a desktop is the whole job of this number, and persistence takes over from the
     * second run. The two hosts said 0.86 and 0.8 for the same stated reason; 0.8 serves it better and
     * is the one the later host chose.</p>
     */
    private static final float FIRST_RUN_FRACTION = 0.8f;

    /**
     * Longest delta reported, whatever the wall clock says.
     *
     * <p>The first windows of a session open into the worst stall there is — 398 ms and 282 ms between
     * consecutive frames, measured — and an unclamped delta hands a whole animation its duration on one
     * frame, which lands it at its end state having drawn nothing in between.</p>
     */
    private static final float MAX_FRAME_SECONDS = 0.25f;

    private static final boolean TRACE = Boolean.getBoolean("crystalgui.startup.trace");

    @Nullable
    private static HostSession current;

    private final HostServices services;
    private final ApplicationKind primaryKind;

    @Nullable
    private DesktopHost host;
    @Nullable
    private Application primary;
    @Nullable
    private WindowFrame primaryWindow;

    /** Consumed by {@link #shown()}, never merely read. @see #requestApplication() */
    private boolean raiseOnShow;

    /** A launch was wanted and could not be done. Cleared once there is a window. @see #frame(float) */
    private boolean launchPending;

    /** What the last frame saw, so a screen opening or closing is noticed exactly once. */
    private boolean foreignScreenWasUp;

    private long lastFrameNanos;
    private long traceNanos;
    private boolean painted;

    private HostSession(HostServices services, ApplicationKind primaryKind) {
        this.services = services;
        this.primaryKind = primaryKind;
    }

    /**
     * Declares this process's host and the application its desktop opens with. Once, at client start.
     *
     * @param primaryKind what a host's "open the application" key launches — and what fills an empty
     *                    desktop, since a desktop with nothing on it is a blank screen rather than a
     *                    desktop
     */
    public static HostSession install(HostServices services, ApplicationKind primaryKind) {
        if (current != null) return current;
        current = new HostSession(services, primaryKind);
        return current;
    }

    /** @throws IllegalStateException if {@link #install} has not run — a wiring error, never a state */
    public static HostSession session() {
        HostSession session = current;
        if (session == null) {
            throw new IllegalStateException(
                    "No HostSession: call HostSession.install(services, kind) when the client starts.");
        }
        return session;
    }

    public static boolean isInstalled() {
        return current != null;
    }

    // ── What a host asks for ────────────────────────────────────────────────────────────────────

    /**
     * Bring the primary application forward the next time the surface is shown — restoring it from
     * minimised, un-hiding it if it was closed, raising it and giving it the keyboard.
     *
     * <p>"Open the editor" has to mean all of that or the key is unreliable: a window that is merely
     * retained is still a window nobody can see. Showing the surface <em>without</em> calling this is
     * the other key, and it deliberately touches nothing.</p>
     */
    public void requestApplication() {
        raiseOnShow = true;
        launchPending = true;
    }

    /**
     * The surface is up. Builds the desktop the first time and resumes it every time after.
     *
     * <p>Safe to call on a resize, which is how a game re-initialises a screen.</p>
     */
    public void shown() {
        if (host == null) build();
        else host.shown();
        bringForward();
    }

    /**
     * The surface went away — and nothing is destroyed.
     *
     * <p>The desktop records its arrangement and each application its session, because each went off
     * screen. A pending launch is abandoned: closing the surface withdraws the request, and carrying it
     * across would open a window the next time the desktop appeared for some unrelated reason.</p>
     */
    public void hidden() {
        launchPending = false;
        if (host != null) host.hidden();
    }

    /**
     * One host tick — <b>before anything reads {@link #workspace()}</b>.
     *
     * <p>Repairs the client if the wire moved, re-asks the window mount, and retries a launch that had
     * no connection to build against. Costs a boolean read once there is a window.</p>
     */
    public void frame(float deltaSeconds) {
        if (host == null) return;
        if (launchPending) bringForward();
        host.frame(deltaSeconds);
    }

    /**
     * Seconds of <b>rendered</b> time since the previous frame, clamped. Zero on the first.
     *
     * <p>Rendered rather than wall: frames nobody saw must not be charged to a gesture. Zero first
     * holds every animation at its start value rather than completing it before anything was drawn.</p>
     */
    public float frameDelta() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return 0f;
        return Math.min((now - previous) / 1_000_000_000f, MAX_FRAME_SECONDS);
    }

    /** Takes the desktop down. Game shutdown only — never a surface close. @see #hidden() */
    public void dispose() {
        if (primary != null) primary.dispose();
        if (host != null) host.dispose();
        primary = null;
        primaryWindow = null;
        host = null;
        painted = false;
        if (current == this) current = null;
    }

    // ── What a host reads ───────────────────────────────────────────────────────────────────────

    /** Null until the surface has been shown once. */
    @Nullable
    public UIDocument document() {
        return host == null ? null : host.document();
    }

    /** This client's compositor, or null until the surface has been shown once. */
    @Nullable
    public Desktop desktop() {
        return host == null ? null : host.desktop();
    }

    /** The workspace the primary application is using, or null before {@link #frame} has bound one. */
    @Nullable
    public Workspace workspace() {
        return host == null ? null : host.workspace();
    }

    /** The running primary application, or null before one could be launched. */
    @Nullable
    public Application application() {
        return primary;
    }

    public HostServices services() {
        return services;
    }

    /** Whether the desktop has been built. */
    public boolean isBuilt() {
        return host != null;
    }

    /**
     * Whether the desktop has ever painted a frame.
     *
     * <p>A host that returns early from its paint — no GL context yet — draws nothing, and a game that
     * is not rendering a level does not clear the colour buffer either, so a screenshot then photographs
     * the <em>previous</em> screen. That is indistinguishable from a working desktop to anything checking
     * only that a capture exists, which is why an unattended capture reports this beside the image.</p>
     */
    public boolean hasPainted() {
        return painted;
    }

    // ── Painting over the game ──────────────────────────────────────────────────────────────────

    /**
     * What a host's paint hook has to answer, and the bracket it paints inside.
     *
     * <pre>{@code
     * // one hook per arm; a frame with a screen open fires both
     * void onHudHook()    { session.paint(HUD, session.frameDelta(), myPaintHost); }
     * void onScreenHook() { session.paint(OVERLAY, session.frameDelta(), myPaintHost); }
     * }</pre>
     *
     * <p>Read the delta <b>once</b> per frame and pass it in — {@link #frameDelta()} advances the clock,
     * so a frame that reads it for its own use and lets the paint read it again hands the compositor a
     * delta of nearly zero and every animation stops.</p>
     */
    public interface PaintHost {

        /** Whether the screen currently up is <b>ours</b>. */
        boolean ownScreenUp();

        /** Whether <b>any</b> screen is up, ours or somebody else's. */
        boolean anyScreenUp();

        /** Hand the compositor a context it can draw in. */
        void enter();

        /** Always runs, pass or throw — hand the game back whatever its own renderer assumes. */
        void leave();

        /** Once per painted frame, before the compositor draws. For a host that must offer the pointer. */
        default void beforePaint() {}
    }

    /**
     * What the desktop should be showing right now — <b>the one place a game condition becomes a
     * presentation</b>, which is what keeps every paint hook and every input hook agreeing.
     *
     * <p>The foreign-screen transition is noticed here rather than in a hook of its own, because this is
     * the one thing every caller runs. A dedicated handler on a world-render event was the first attempt
     * and is wrong for the case that matters least often and breaks worst: a screen that renders no world
     * fires no such event, so the close is never seen and ownership survives into the next screen.</p>
     */
    public DesktopPresentation presentation(PaintHost host) {
        Desktop desktop = desktop();
        if (desktop == null) return DesktopPresentation.NONE;

        boolean ours = host.ownScreenUp();
        boolean any = host.anyScreenUp();

        boolean foreignUp = any && !ours;
        if (foreignUp != foreignScreenWasUp) {
            foreignScreenWasUp = foreignUp;
            // Nullable: screenOverlay() answers null while the compositor has no document, which is what
            // a closed UI leaves behind -- desktop() still hands back the Desktop. Thrown from a render
            // hook it takes the rest of the game's overlay chain with it. The missed transition is owed
            // to nobody: a fresh overlay is built when a document appears.
            ScreenOverlay overlay = desktop.screenOverlay();
            if (overlay != null) overlay.onForeignScreenChanged(foreignUp);
        }
        return desktop.presentation(ours, any);
    }

    /**
     * Paints {@code arm}, and <b>only when the compositor is actually in it</b>.
     *
     * <p>One arm per hook. A frame with a screen open fires the HUD hook and the screen hook both, and
     * painting from each draws the whole compositor twice — style, layout and all — with the second pass
     * winning the {@code localToWorld} reconciliation the hit test walks, so clicks land somewhere other
     * than what is on screen.</p>
     *
     * <p><b>A fault here must not take the game down.</b> This runs inside the game's own render loop
     * every frame, and unlike a screen there is nothing the player can close to escape it. The mode is
     * dropped instead, which puts them back in a working game with their windows intact on the desktop.
     * Deciding what to present is inside the guard too: it reads the compositor and can throw for the
     * same reasons painting it can.</p>
     */
    public void paint(DesktopPresentation arm, float deltaSeconds, PaintHost host) {
        Desktop desktop = desktop();
        if (desktop == null) return;

        DesktopPresentation now;
        try {
            now = presentation(host);
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] could not decide a presentation; leaving HUD mode", failed);
            desktop.exitHudMode();
            return;
        }
        // NONE paints nothing, and the other arm's hook owns the rest.
        if (now != arm) return;

        host.beforePaint();
        host.enter();
        try {
            desktop.paint(now, deltaSeconds, services.surfaceWidth(), services.surfaceHeight());
            painted = true;
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] overlay paint failed; leaving HUD mode", failed);
            desktop.exitHudMode();
        } finally {
            host.leave();
        }
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    private void build() {
        trace("begin");
        DesktopHost built = DesktopHost.create(services);
        // WHERE A SERVER'S WINDOW IS OFFERED FIRST. An application with places of its own takes what it
        // recognises and hands the rest back, so a client with it closed still gets every window. A
        // supplier because the application is built later than this, and on demand.
        built.setWindowMount(() -> {
            Application app = primary;
            if (!(app instanceof ServerWindowHost)) return null;
            WindowMount fallback = built.windowMount();
            return ((ServerWindowHost) app).windowMount(fallback);
        });
        built.document().addClass(ROOT_CLASS);
        host = built;
        trace("DesktopHost");
    }

    /**
     * Builds the primary application's window on demand, or reports why it could not be built.
     *
     * <p><b>Every refusal is the registry's.</b> "It needs a world" was once a log line written beside
     * the storage wiring, the class registration, the window's title, key, policy and icon, the project
     * ask and the session restore — fourteen decisions, none about the host. What is left here is the
     * one that genuinely is: how big a first-run window is on this display.</p>
     *
     * @return whether there is a window to bring forward
     */
    private boolean ensurePrimaryWindow() {
        if (primaryWindow != null) return true;
        DesktopHost built = host;
        if (built == null) return false;

        // THE HOST'S FRAME FIRST, because it is what BINDS the workspace and the launch below reads one.
        // Painting used to be the only caller, so the first open asked for the application before any
        // frame had run and took a "needs a server and there is none" refusal with a healthy connection
        // open — and the second open worked, which made it read as a render fault. Idempotent.
        built.frame(0f);

        Application launched;
        try {
            launched = built.desktop().applications().launch(primaryKind, built.workspace());
        } catch (RuntimeException refused) {
            CrystalGuiCore.LOGGER.debug("[cgui] '{}' cannot launch yet", primaryKind.id(), refused);
            return false;
        }
        if (launched == null) return false;
        trace("launch");

        WindowFrame window = launched.mainWindow();
        if (window == null) return false;
        primary = launched;
        primaryWindow = window;

        placeFirstRun(window);
        trace("window");
        return true;
    }

    /**
     * A window on a desktop, not a full-screen application — and only as a DEFAULT: an arrangement
     * record overrides it the moment there is one.
     *
     * <p>Sized in logical pixels off the surface and the host's scale. A percentage in a stylesheet
     * would be tempting and would lose to the desktop's own cascade, which writes left and top at a
     * higher origin for any window nobody placed.</p>
     */
    private void placeFirstRun(WindowFrame window) {
        float scale = services.uiScale();
        if (scale <= 0f) return;
        float width = services.surfaceWidth() / scale;
        float height = services.surfaceHeight() / scale;
        // NO SURFACE YET, so there is nothing to be a fraction of. Placing against zero would pin the
        // window at 0x0 and then persist that, which survives every later run.
        if (width <= 0f || height <= 0f) return;

        window.resizeTo(Math.round(width * FIRST_RUN_FRACTION),
                Math.round(height * FIRST_RUN_FRACTION));
        window.moveTo(Math.round(width * (1f - FIRST_RUN_FRACTION) / 2f),
                Math.round(height * (1f - FIRST_RUN_FRACTION) / 2f));
    }

    /**
     * Raises the primary window if that is what was asked for, and builds it if nothing is open.
     *
     * <p><b>A desktop with nothing on it is a blank screen.</b> The compositor takes up no space until a
     * window exists — an unused one must not swallow clicks meant for the game behind it — so an empty
     * desktop has no taskbar either, and asking for the bare desktop before anything was ever opened
     * would show the game and nothing else. The first show therefore builds the application whichever
     * key asked for it; from then on the two keys differ as they should.</p>
     */
    private void bringForward() {
        Desktop desktop = desktop();
        if (desktop == null) return;

        boolean nothingOpen = desktop.registry().size() == 0;
        if (!raiseOnShow && !nothingOpen) return;

        if (!ensurePrimaryWindow()) {
            // Armed on the FAILURE rather than by the request alone: opening the bare desktop also wants
            // an application when nothing is open, and that path never retried.
            launchPending = true;
            return;
        }
        launchPending = false;

        // Built, and only RAISED on request. Opening a window already shows it, so an empty desktop now
        // has something on it and a taskbar to reach it by; what the bare-desktop key must not do is put
        // it in front.
        if (!raiseOnShow) return;
        raiseOnShow = false;

        if (primaryWindow.state() == WindowState.HIDDEN) primaryWindow.show(true);
        desktop.activate(primaryWindow);
    }

    /**
     * Where the first open's time actually goes — {@code -Dcrystalgui.startup.trace=true}.
     *
     * <p>It exists because the first open was reported as a three-second freeze and nobody could say
     * what was slow: the parts measurable without a client came to about a second, and the rest is
     * behind GL — fonts, glyph atlases, icon parsing, the first layout of a whole workbench — which no
     * unit test reaches. A client is an environment no test reproduces, so ask it rather than reason
     * about it.</p>
     */
    private void trace(String phase) {
        if (!TRACE) return;
        long now = System.nanoTime();
        if (traceNanos != 0L) {
            CrystalGuiCore.LOGGER.info("[startup] {} — {} ms", phase, (now - traceNanos) / 1_000_000);
        }
        traceNanos = now;
    }
}
