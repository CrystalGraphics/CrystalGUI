package com.crystalgui.desktop.host;

import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.render.UiFrame;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.job.CgHostThread;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.ServerWindowHost;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.ui.dom.DocumentDriver;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

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
 * <p>Then a host's screen is five calls and owns nothing:</p>
 *
 * <pre>{@code
 * void onScreenShown()  { HostSession.session().shown(); }
 * void onEscape()       { if (!HostSession.session().requestHide(() -> closeLater = true)) closeNow(); }
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
     * Longest delta reported, whatever the wall clock says.
     *
     * <p>The first windows of a session open into the worst stall there is — 398 ms and 282 ms between
     * consecutive frames, measured — and an unclamped delta hands a whole animation its duration on one
     * frame, which lands it at its end state having drawn nothing in between.</p>
     */
    private static final float MAX_FRAME_SECONDS = 0.25f;

    private static final boolean TRACE = Boolean.getBoolean("crystalgui.startup.trace");

    /** One per client and the render thread's, not a document's; volatile so a document thread reads it whole. */
    @Nullable
    private static volatile HostSession current;

    private final HostServices services;
    /** Who is offered a key the desktop left, before the game is. */
    /** Who is offered a key before the desktop, while no text field has focus. */
    private final List<Predicate<CgSystemInput.Keyboard.Event>> hotKeys = new CopyOnWriteArrayList<>();
    private final List<Predicate<CgSystemInput.Keyboard.Event>> keysLeft = new CopyOnWriteArrayList<>();
    @Nullable
    private final ApplicationKind primaryKind;
    private final HostInput input = new HostInput(this::document, this::offerHotKey);

    @Nullable
    private DesktopHost host;

    /** Runs the desktop's document: inline, in lockstep or recording on its own. Null until built. */
    @Nullable
    private DocumentDriver<DesktopFacts> driver;

    /** What a host asks about a frame of a document that records on its own, read from the frame on screen. */
    private record DesktopFacts(boolean attached, boolean pinned, @Nullable float[] textInputArea) {
    }

    /** What the document is told about the host: the render thread's answers, copied at each entry. */
    @Nullable
    private HostSnapshot snapshot;

    /** Runs {@code work} on the document, without waiting when it records on its own. */
    private void post(Runnable work) {
        DocumentDriver<DesktopFacts> owner = driver;
        if (owner == null) work.run();
        else owner.post(work);
    }

    /**
     * Runs {@code work} on the document without waiting: what a host does for a feed that builds or edits the desktop's tree,
     * a server's window arriving over a connection. Inline when the document records on the host's thread.
     *
     * <pre>{@code
     * HostSession.session().postToDocument(() -> connection.tick());
     * }</pre>
     */
    public void postToDocument(Runnable work) {
        post(work);
    }

    /** Copies the host's answers for the document. Render thread, at every host entry. */
    private void refreshHost() {
        HostSnapshot copy = snapshot;
        if (copy != null) copy.refresh();
    }

    /**
     * Runs {@code work} where the desktop's tree may be touched, and returns when it has run: on the document's sequence
     * in lockstep whatever the mode, or here when it has none. For a tool driving the desktop from the host's thread, a
     * probe or a script; the host's own entries never need it.
     *
     * <pre>{@code
     * HostSession.session().inDocument(() -> desktop.minimise(window));
     * }</pre>
     */
    public void inDocument(Runnable work) {
        onDocument(work);
    }

    /** Runs {@code work} on the document and waits for it. */
    private void onDocument(Runnable work) {
        DocumentDriver<DesktopFacts> owner = driver;
        if (owner == null) work.run();
        else owner.run(work);
    }
    @Nullable
    private volatile Application primary;
    @Nullable
    private WindowFrame primaryWindow;

    /** Consumed by {@link #shown()}, never merely read. @see #requestApplication() */
    private volatile boolean raiseOnShow;

    /** A launch was wanted and could not be done. Cleared once there is a window. @see #frame(float) */
    private volatile boolean launchPending;

    /** What the last frame saw, so a screen opening or closing is noticed exactly once. */
    private boolean foreignScreenWasUp;

    private long lastFrameNanos;
    private long traceNanos;
    private boolean painted;

    private HostSession(HostServices services, @Nullable ApplicationKind primaryKind) {
        this.services = services;
        this.primaryKind = primaryKind;
        // The game's threads, as UI code reaches them. @see CgHostThread
    }

    /**
     * Declares this process's host and the application its desktop opens with. Once, at client start.
     *
     * @param primaryKind what a host's "open the application" key launches — and what fills an empty
     *                    desktop, since a desktop with nothing on it is a blank screen rather than a
     *                    desktop; null for a host that opens every application itself, by its own keys,
     *                    whose empty desktop stays empty
     */
    public static HostSession install(HostServices services, @Nullable ApplicationKind primaryKind) {
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

    /**
     * Whether a host may open the desktop now: never while CrystalGraphics' loading screen shows, which nothing opens
     * over. Every host's open asks first.
     *
     * <pre>{@code
     * public static void openDesktop() {
     *     if (!HostSession.mayOpen()) return;
     *     ...
     * }
     * }</pre>
     */
    public static boolean mayOpen() {
        if (!CgLoadingScreen.shown()) return true;
        CrystalGuiCore.LOGGER.info("[cgui] not opening the desktop: the loading screen shows");
        return false;
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
     * Builds the desktop before anything shows it, so the first open only resumes it: its document, sheets, theme,
     * compositor, taskbar and arrangement, and their first cascade and layout. The loading screen runs it, as the load
     * task {@code crystalgui:desktop}. Render thread; does nothing once built.
     *
     * <p>Nothing presents it until a screen is up; {@link #warmStep} draws a throwaway one meanwhile.</p>
     */
    public void preload() {
        if (host != null) return;
        refreshHost();
        build();
        DesktopHost built = host;
        post(() -> built.document().update(snapshot.surfaceWidth(), snapshot.surfaceHeight()));
    }

    private DesktopWarmup warmup;
    private boolean warmed;

    /**
     * Draws one frame of a throwaway desktop that nothing composites, so the first open finds its GPU work done. The
     * loading screen calls it as the load task {@code crystalgui:desktop-warm} until it answers true. Render thread.
     *
     * @return whether the warm-up is over: finished, failed (logged), or there is no desktop to warm
     */
    public boolean warmStep() {
        if (warmed || host == null) return true;
        try {
            if (warmup == null) warmup = new DesktopWarmup();
            if (!warmup.step(Math.max(1, snapshot.surfaceWidth()), Math.max(1, snapshot.surfaceHeight()))) return false;
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] the desktop warm-up failed; the first open pays for it", failed);
        }
        warmup = null;
        warmed = true;
        return true;
    }

    /**
     * The surface is up. Builds the desktop the first time and resumes it every time after.
     *
     * <p>Safe to call on a resize, which is how a game re-initialises a screen.</p>
     */
    public void shown() {
        closing = null;
        left = false;
        refreshHost();
        if (host == null) build();
        else post(host::shown);
        post(this::bringForward);
    }

    /**
     * The surface went away — and nothing is destroyed.
     *
     * <p>The desktop records its arrangement and each application its session, because each went off
     * screen. A pending launch is abandoned: closing the surface withdraws the request, and carrying it
     * across would open a window the next time the desktop appeared for some unrelated reason.</p>
     */
    public void hidden() {
        closing = null;
        left = false;
        launchPending = false;
        DesktopHost built = host;
        if (built != null) post(built::hidden);
    }

    /**
     * Asks to close the surface: the desktop plays itself off screen, then {@code close} runs, here on the host's
     * thread from a later {@link #frame}. What a screen's Escape or close key calls instead of closing; {@code close}
     * is the real close, which then reports {@link #hidden}. False, having run nothing, when the surface should close
     * now: nothing is built, or animations are off. Asked again while it plays, true and nothing more.
     *
     * <pre>{@code
     * public void onClose() {
     *     if (!HostSession.session().requestHide(() -> closeReady = true)) super.onClose();   // tick() closes it
     * }
     * }</pre>
     *
     * <p>Hosts must keep calling {@link #frame} while it plays, or {@code close} waits out its longest second.</p>
     */
    public boolean requestHide(Runnable close) {
        DesktopHost built = host;
        if (built == null || !WindowAnimator.isEnabled()) return false;
        if (closing != null) return true;
        left = false;
        closing = close;
        closingSince = System.nanoTime();
        refreshHost();
        post(() -> built.leave(() -> left = true));
        return true;
    }

    /** The close a {@link #requestHide} is waiting to run, and since when. Host thread. */
    @Nullable
    private volatile Runnable closing;
    private volatile long closingSince;
    /** The desktop has played itself off screen. Written on the document's thread. */
    private volatile boolean left;
    /** How long a close waits for the desktop: past it, the surface closes whatever is still playing. */
    private static final long LEAVE_LONGEST_NANOS = 1_000_000_000L;

    /**
     * One host tick — <b>before anything reads {@link #workspace()}</b>.
     *
     * <p>Repairs the client if the wire moved, re-asks the window mount, and retries a launch that had
     * no connection to build against. Costs a boolean read once there is a window.</p>
     */
    public void frame(float deltaSeconds) {
        Runnable close = closing;
        if (close != null && (left || System.nanoTime() - closingSince > LEAVE_LONGEST_NANOS)) {
            closing = null;
            left = false;
            close.run();
        }
        DesktopHost built = host;
        if (built == null) return;
        refreshHost();
        post(() -> {
            if (launchPending) bringForward();
            built.frame(deltaSeconds);
        });
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
        onDocument(() -> {
            // The arrangement first, with every window still up: a shutdown with the screen up never detached, which is
            // when it writes itself
            if (host != null) host.desktop().savePersistedState();
            // Every application quits, not the primary alone: one holding ticks or stage draws releases them here
            if (host != null) {
                for (Application app : host.desktop().applications().running()) app.dispose();
            } else if (primary != null) {
                primary.dispose();
            }
            if (host != null) host.dispose();
        });
        if (driver != null) driver.close();
        driver = null;
        primary = null;
        primaryWindow = null;
        host = null;
        painted = false;
        if (current == this) {
            current = null;
        }
    }

    // ── What a host reads ───────────────────────────────────────────────────────────────────────

    /** Null until the surface has been shown once. */
    @Nullable
    public UIDocument document() {
        return host == null ? null : host.document();
    }

    /** Where a host delivers every input event while its own screen is up. */
    public HostInput input() {
        return input;
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

    /**
     * The focus owner's caret area in surface pixels, {@code [x, y, width, height]}, or null when nothing takes text:
     * where an input method opens its candidate list.
     */
    @Nullable
    public float[] textInputArea() {
        UIDocument document = document();
        DocumentDriver<DesktopFacts> owner = driver;
        if (document == null || owner == null) return null;
        if (owner.isAsync()) {
            DesktopFacts shown = owner.shownFacts();
            return shown == null ? null : shown.textInputArea();
        }
        return owner.ask(() -> document.input().textInputArea());
    }

    // ── Painting over the game ──────────────────────────────────────────────────────────────────

    /**
     * What a host's paint has to answer, and the bracket it paints inside.
     *
     * <pre>{@code
     * // once, on a client: the pinned windows over the HUD and over somebody else's screen
     * HostSession.attachStages(myPaintHost);
     *
     * // our own screen, which also runs the host tick: one read, both uses
     * float delta = session.frameDelta();
     * session.frame(delta);
     * session.paint(DESKTOP, delta, myPaintHost);
     * }</pre>
     *
     * <p>A stage passes no delta: {@link #frameDelta()} advances the clock, and an arm that read it and then
     * stood down would leave the arm that does paint a delta of nearly zero. Every animation then stalls,
     * a smooth scroll included, and its timeline writes the old offset back over a thumb drag.</p>
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
        DocumentDriver<DesktopFacts> owner = driver;
        if (owner == null) return DesktopPresentation.NONE;
        if (owner.isAsync()) return presentationFromCommit(host);
        return owner.ask(() -> presentationOnDocument(host));
    }

    /** {@link #presentationOnDocument}, from what the document last committed rather than from its tree. */
    private DesktopPresentation presentationFromCommit(PaintHost host) {
        Desktop desktop = desktop();
        if (desktop == null) return DesktopPresentation.NONE;
        boolean ours = host.ownScreenUp();
        boolean any = host.anyScreenUp();
        noteForeignScreen(desktop, any && !ours);
        DesktopFacts shown = driver.shownFacts();
        // Before the first commit the desktop is attached and holds nothing pinned, as it was built.
        return Desktop.presentation(ours, any, shown == null || shown.attached(), shown != null && shown.pinned());
    }

    private void noteForeignScreen(Desktop desktop, boolean foreignUp) {
        if (foreignUp == foreignScreenWasUp) return;
        foreignScreenWasUp = foreignUp;
        // Nullable: screenOverlay() answers null while the compositor has no document, which is what
        // a closed UI leaves behind -- desktop() still hands back the Desktop. Thrown from a render
        // hook it takes the rest of the game's overlay chain with it. The missed transition is owed
        // to nobody: a fresh overlay is built when a document appears.
        ScreenOverlay overlay = desktop.screenOverlay();
        if (overlay != null) overlay.onForeignScreenChanged(foreignUp);
    }

    private DesktopPresentation presentationOnDocument(PaintHost host) {
        Desktop desktop = desktop();
        if (desktop == null) return DesktopPresentation.NONE;

        boolean ours = host.ownScreenUp();
        boolean any = host.anyScreenUp();

        noteForeignScreen(desktop, any && !ours);
        return desktop.presentation(ours, any);
    }

    // ── Input offered over a screen ─────────────────────────────────────────────────────────────

    /**
     * Offers one pointer event to the pinned windows, and <b>refuses it while the pointer is the camera</b>.
     *
     * <pre>{@code
     * // a host's screen-input hook: forward only what the desktop did not take
     * if (session.offerMouse(myPaintHost, Mouse.isGrabbed(), x, displayHeight - y, button, pressed, wheel)) {
     *     event.setCanceled(true);
     * }
     * }</pre>
     *
     * <p>With no screen up there is no cursor: the position runs with the look direction and a press is
     * an attack, so hit-testing it would press whatever pinned window the player is looking at. A grab
     * under a screen means the same, since a mod may capture the pointer there. A host reports the grab;
     * this decides.</p>
     *
     * @param host    answers whether a screen is up
     * @param grabbed whether the host has captured the pointer
     * @param yPx     surface pixels from the TOP; a host whose toolkit measures from the bottom flips it
     * @return whether the desktop consumed it, so the screen must not see it. See {@link ScreenOverlay#offerMouse}
     */
    public boolean offerMouse(PaintHost host, boolean grabbed, int xPx, int yPx, int button, boolean pressed,
                              float wheel) {
        if (grabbed || !host.anyScreenUp()) return false;
        ScreenOverlay overlay = screenOverlay();
        if (overlay == null) return false;
        // Asynchronously the overlay answers from its committed regions and posts what it delivers.
        DocumentDriver<DesktopFacts> owner = driver;
        if (owner == null || owner.isAsync()) return overlay.offerMouse(xPx, yPx, button, pressed, wheel);
        return owner.ask(() -> overlay.offerMouse(xPx, yPx, button, pressed, wheel));
    }

    /**
     * Offers one key to the pinned windows.
     *
     * @param keyCode the engine's key code ({@code CgKeyCodes}), not the toolkit's
     * @return whether the desktop consumed it. See {@link ScreenOverlay#offerKey}
     */
    public boolean offerKey(int keyCode, char typed, boolean pressed) {
        ScreenOverlay overlay = screenOverlay();
        return overlay != null && overlay.offerKey(keyCode, typed, pressed);
    }

    /**
     * Null until there is a document, and again after a close: the host keeps its {@link Desktop} while the
     * node is disconnected, and only the overlay knows that.
     */
    @Nullable
    private ScreenOverlay screenOverlay() {
        Desktop desktop = desktop();
        return desktop == null || document() == null ? null : desktop.screenOverlay();
    }

    /**
     * The order CrystalGUI's windows draw at on {@link CgRenderStage#HUD} and {@link CgRenderStage#SCREEN}: the game's
     * own interface, {@link CgRenderStage#VIEWPORT_WIDGETS}.
     */
    public static final int COMPOSITOR = CgRenderStage.VIEWPORT_WIDGETS;

    /**
     * Puts the pinned windows on CrystalGraphics' GUI stages: over the HUD, and over a screen that is not ours. Once, on
     * a client; a later call does nothing.
     *
     * <pre>{@code
     * HostSession.attachStages(CgUiHud.HOST);
     * }</pre>
     */
    public static void attachStages(PaintHost host) {
        HostStages.attach(host);
    }

    /**
     * Paints {@code arm} in {@code stage}'s frame, at {@link #COMPOSITOR}, <b>only when the compositor is actually in
     * it</b>: from {@link CgRenderStage#HUD} the HUD arm, from {@link CgRenderStage#SCREEN} the overlay arm, which our
     * own screen's desktop never is.
     *
     * <p><b>A fault here must not take the game down.</b> This runs inside the game's own render loop
     * every frame, and unlike a screen there is nothing the player can close to escape it. The mode is
     * dropped instead, which puts them back in a working game with their windows intact on the desktop.
     * Deciding what to present is inside the guard too: it reads the compositor and can throw for the
     * same reasons painting it can.</p>
     */
    void paintAt(CgStageFrame stage, DesktopPresentation arm, PaintHost host) {
        paint(arm, host, false, 0f, stage);
    }

    /**
     * Paints {@code arm} now, with a delta already read this frame, for a caller that also handed it to
     * {@link #frame}: our own screen's desktop, and the HUD arm on the frame a screen closed itself after the HUD drew.
     */
    public void paint(DesktopPresentation arm, float deltaSeconds, PaintHost host) {
        paint(arm, host, true, deltaSeconds, null);
    }

    private void paint(DesktopPresentation arm, PaintHost host, boolean deltaRead, float deltaSeconds,
                       @Nullable CgStageFrame stage) {
        // EVERY ARM, painted or not: work and answers for the frame thread must not wait for a desktop to show.
        CgHostThread.drainFrames();
        Desktop desktop = desktop();
        if (desktop == null || document() == null || driver == null) return;
        DesktopPresentation now;
        try {
            now = presentation(host);
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] could not decide a presentation; leaving HUD mode", failed);
            post(desktop::exitHudMode);
            return;
        }
        // NONE paints nothing, and the other arm's stage owns the rest. One arm per stage: a frame with a screen up
        // fires both, and painting the compositor twice lets the second pass win the hit test's localToWorld.
        if (now != arm) return;
        if (!deltaRead) deltaSeconds = frameDelta();
        refreshHost();
        returnUnhandledKeys();
        host.beforePaint();
        stagedPresentation = now;
        stagedDelta = deltaSeconds;
        stagedHost = host;
        if (stage != null) stage.callback("crystalgui", paintStaged);
        else paintStaged.run();
    }

    /** What {@link #paintStaged} draws: the presentation and delta of the paint under way. */
    private DesktopPresentation stagedPresentation = DesktopPresentation.NONE;
    private float stagedDelta;
    @Nullable
    private PaintHost stagedHost;

    /** The compositor, at its place in the arm's stage. Held, so a frame allocates no callback. */
    private final Runnable paintStaged = this::paintCompositor;

    private void paintCompositor() {
        Desktop desktop = desktop();
        UIDocument document = document();
        DocumentDriver<DesktopFacts> owner = driver;
        PaintHost host = stagedHost;
        if (desktop == null || document == null || owner == null || host == null) return;
        host.enter();
        try {
            if (owner.frame(stagedDelta, snapshot.surfaceWidth(), snapshot.surfaceHeight(),
                    new DesktopPainter(desktop, document, stagedPresentation))) {
                painted = true;
            }
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] overlay paint failed; leaving HUD mode", failed);
            post(desktop::exitHudMode);
        } finally {
            host.leave();
        }
    }

    /** The desktop in one presentation, and what the host reads back about each recorded frame. */
    private record DesktopPainter(Desktop desktop, UIDocument document, DesktopPresentation presentation)
            implements DocumentDriver.Painter<DesktopFacts> {

        @Override
        public void paint(float deltaSeconds, int width, int height) {
            desktop.paint(presentation, deltaSeconds, width, height);
        }

        @Override
        @Nullable
        public UiFrame record(float deltaSeconds, int width, int height) {
            try {
                return desktop.record(presentation, deltaSeconds, width, height);
            } catch (RuntimeException | LinkageError failed) {
                CrystalGuiCore.LOGGER.error("[cgui] recording the desktop failed; leaving HUD mode", failed);
                desktop.exitHudMode();
                return null;
            }
        }

        @Override
        public DesktopFacts facts() {
            return new DesktopFacts(desktop.parent() != null, desktop.hasPinnedWindows(),
                    document.input().textInputArea());
        }
    }

    /** Every key the document dispatched and left since the last frame, to the game, in order. Render thread. */
    private void returnUnhandledKeys() {
        for (CgSystemInput.Keyboard.Event key; (key = input.pollUnhandledKey()) != null; ) {
            if (!offerLeftKey(key)) services.reinjectKey(key);
        }
    }

    /**
     * Hears each key press and release the desktop left, before the game does: a HUD's keys that should work with the
     * desktop up. Return true to take one; a key a focused field took never gets here. Render thread.
     *
     * <pre>{@code
     * Runnable off = HostSession.session().onKeyLeft(event -> hud.key(event));
     * off.run();   // when the HUD goes
     * }</pre>
     *
     * @return what removes the listener
     */
    public Runnable onKeyLeft(Predicate<CgSystemInput.Keyboard.Event> listener) {
        keysLeft.add(listener);
        return () -> keysLeft.remove(listener);
    }

    /**
     * Hears each key before the desktop does, unless a text field has focus: for a key the desktop would take itself
     * (Tab moves focus). Return true to take it. Take only what is yours, since nothing the desktop would do with it
     * then happens; keys it leaves go to {@link #onKeyLeft}. Render thread.
     *
     * @return what removes the listener
     */
    public Runnable onHotKey(Predicate<CgSystemInput.Keyboard.Event> listener) {
        hotKeys.add(listener);
        return () -> hotKeys.remove(listener);
    }

    private boolean offerHotKey(CgSystemInput.Keyboard.Event key) {
        if (hotKeys.isEmpty() || textInputArea() != null) return false;
        for (Predicate<CgSystemInput.Keyboard.Event> listener : hotKeys) {
            if (listener.test(key)) return true;
        }
        return false;
    }

    /** Offers {@code key} to the {@link #onKeyLeft} listeners in order; true if one took it. For a host that answers a key itself. */
    public boolean offerLeftKey(CgSystemInput.Keyboard.Event key) {
        for (Predicate<CgSystemInput.Keyboard.Event> listener : keysLeft) {
            if (listener.test(key)) return true;
        }
        return false;
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    private void build() {
        trace("begin");
        snapshot = new HostSnapshot(services);
        DesktopHost built = DesktopHost.create(snapshot);
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
        driver = DocumentDriver.attach(built.document(), DocumentDriver.Mode.fromFlags(), "desktop");
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
        if (primaryKind == null) return false;
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
        // Its X quits it: the next open launches a fresh one rather than raising a window that is gone
        window.onDestroyed.connect(() -> {
            if (primaryWindow != window) return;
            primary = null;
            primaryWindow = null;
        });

        trace("window");
        return true;
    }

    /**
     * Raises the primary window if that is what was asked for, and builds it if nothing is open.
     *
     * <p>The bare-desktop key launches nothing: only {@link #requestApplication()} builds the primary
     * application, so an empty desktop stays empty.</p>
     */
    private void bringForward() {
        Desktop desktop = desktop();
        if (desktop == null) return;

        if (!raiseOnShow) return;
        // No primary: the host opens every application itself, and an empty desktop stays empty
        if (primaryKind == null) {
            raiseOnShow = false;
            launchPending = false;
            return;
        }

        if (!ensurePrimaryWindow()) {
            // Armed on the failure so the next frame retries the request.
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
