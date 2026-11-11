package com.crystalgui.desktop.host;

import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.render.UiPresenter;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.ApplicationRegistry;
import com.crystalgui.desktop.app.ServerWindowHost;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.gauntlet.ConnectionTest;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * <b>The one desktop a client has, and the application it opens with.</b>
 *
 * <p>A game builds and throws away its screen object constantly — Minecraft constructs a fresh one on
 * every display and on every window resize — so none of this can live on a screen: the desktop, its
 * windows, the unsaved documents in them and the request to bring one forward all have to outlive it.
 * This is where they live.</p>
 *
 * <p>Install once, when a client starts: the rest is the session's own. It paints at CrystalGraphics' GUI stages,
 * takes input at its {@code SCREEN} input stage, and puts its desktop up as the engine's screen ({@code CgScreens}):</p>
 *
 * <pre>{@code
 * HostSession.install();                                   // what CgUiClientLifecycle does on every Minecraft client
 *
 * HostSession.session().openDesktop();                     // a key: the desktop, nothing on it touched
 * HostSession.session().openApplication();                 // a key: the desktop, the primary application forward
 * HostSession.session().closeDesktop();                    // it plays itself off, then its screen closes
 * HostSession.session().dispose();                         // game shutdown
 * }</pre>
 *
 * <h3>Easy to get wrong</h3>
 *
 * <ul>
 *   <li>Nothing opens while CrystalGraphics' loading screen shows: an open then is dropped, and logged.</li>
 *   <li>Closing the screen destroys nothing: the desktop, its windows and every running application outlive it, and
 *       the next open resumes them. Only {@link #dispose()} ends them, at shutdown.</li>
 *   <li>{@link #frame(float)} must run before anything reads {@link #workspace()} -- it is what binds a
 *       workspace to a live connection. The desktop's arm runs it each frame; a host opening an application before
 *       the first paint calls it once itself.</li>
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


    private static final boolean TRACE = Boolean.getBoolean("crystalgui.startup.trace");

    /** One per client and the render thread's, not a document's; volatile so a document thread reads it whole. */
    @Nullable
    private static volatile HostSession current;

    /** Who is offered a key before the desktop, while no text field has focus. */
    private final List<Predicate<CgSystemInput.Keyboard.Event>> hotKeys = new CopyOnWriteArrayList<>();
    /** Who is offered a key the desktop left, before the game is. */
    private final List<Predicate<CgSystemInput.Keyboard.Event>> keysLeft = new CopyOnWriteArrayList<>();
    private final HostInput input = new HostInput(this::viewport, this::offerHotKey);

    @Nullable
    private DesktopHost host;

    /** Puts the desktop's document on screen: inline, in lockstep or recording on its own. Null until built. */
    @Nullable
    private volatile CgUiViewport viewport;

    /** What the document is told about the host: the render thread's answers, copied at each entry. */
    @Nullable
    private HostSnapshot snapshot;

    /** Runs {@code work} on the document, without waiting when it records on its own. */
    private void post(Runnable work) {
        CgUiViewport shown = viewport;
        if (shown == null) work.run();
        else shown.execute(work);
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
        CgUiViewport shown = viewport;
        if (shown == null) work.run();
        else shown.run(work);
    }
    @Nullable
    private volatile Application primary;
    @Nullable
    private WindowFrame primaryWindow;

    /** Consumed by {@link #shown()}, never merely read. @see #openApplication() */
    private volatile boolean raiseOnShow;

    /** A launch was wanted and could not be done. Cleared once there is a window. @see #frame(float) */
    private volatile boolean launchPending;

    /** What the last frame saw, so a screen opening or closing is noticed exactly once. */
    private boolean foreignScreenWasUp;

    private long traceNanos;
    private boolean painted;

    private HostSession() {
    }

    /**
     * Declares this process's desktop. Once, at client start; everything the desktop asks of its host is read from
     * CrystalGraphics' services, and the application it opens with is the registry's
     * ({@link ApplicationRegistry#installPrimary}).
     */
    public static synchronized HostSession install() {
        if (current != null) return current;
        current = new HostSession();
        HostStages.attach();
        return current;
    }

    /** @throws IllegalStateException if {@link #install} has not run — a wiring error, never a state */
    public static HostSession session() {
        HostSession session = current;
        if (session == null) {
            throw new IllegalStateException(
                    "No HostSession: call HostSession.install() when the client starts.");
        }
        return session;
    }

    public static boolean isInstalled() {
        return current != null;
    }

    /** Whether the desktop may open now: never while CrystalGraphics' loading screen shows, which nothing opens over. */
    private static boolean mayOpen() {
        if (!CgLoadingScreen.shown()) return true;
        CrystalGuiCore.LOGGER.info("[cgui] not opening the desktop: the loading screen shows");
        return false;
    }

    // ── Its screen ──────────────────────────────────────────────────────────────────────────────

    /** The desktop as the engine's screen: what opens it, hears it come and go, and takes its Escape. */
    private final CgScreens.Owner screen = new CgScreens.Owner() {
        @Override
        public void shown() {
            HostSession.this.shown();
        }

        @Override
        public void hidden() {
            HostSession.this.hidden();
        }

        @Override
        public void escape() {
            closeDesktop();
        }

        /** It covers the window, so the HUD drawn over screens (1.21.6 to 26.0) would land on it. */
        @Override
        public boolean hidesHud() {
            return true;
        }

        @Override
        public boolean textInputArea(float[] out) {
            float[] area = HostSession.this.textInputArea();
            if (area == null) return false;
            System.arraycopy(area, 0, out, 0, 4);
            return true;
        }
    };

    /**
     * Puts the desktop up, touching no window on it: what comes back is what was left, minimised windows included. The
     * Show Desktop key's. Client thread.
     */
    public void openDesktop() {
        if (!mayOpen()) return;
        // THE PROBE OWNS THE DESKTOP WHILE IT RUNS: opening claims ui/openWindow, which the probe's own session holds.
        if (ConnectionTest.enabled() && !ConnectionTest.isDrivingDesktop()) {
            CrystalGuiCore.LOGGER.info("[cgui] not opening the desktop: the connection probe is driving");
            return;
        }
        CgScreens.open(screen);
    }

    /**
     * Puts the desktop up with the primary application brought forward: restored from minimised, un-hidden if it was
     * closed, raised and given the keyboard, launched if nothing runs it. "Open the editor" has to mean all of that or
     * the key is unreliable. Client thread.
     */
    public void openApplication() {
        if (!mayOpen()) return;
        raiseOnShow = true;
        launchPending = true;
        openDesktop();
    }

    /**
     * {@link #openDesktop} while the loading screen shows, for a host whose first frame is the desktop: it is built
     * behind that screen and up when it hides. Client thread.
     */
    public void openBehindLoadingScreen() {
        CgScreens.open(screen);
    }

    /** Closes the desktop's screen once the desktop has played itself off; at once with animations off. Client thread. */
    public void closeDesktop() {
        if (!requestHide(() -> CgScreens.close(screen))) CgScreens.close(screen);
    }

    /** Whether the desktop's screen is the one in front. */
    public boolean isOpen() {
        return CgScreens.isOpen(screen);
    }

    /**
     * Builds the desktop before anything shows it, so the first open only resumes it: its document, sheets, theme,
     * compositor, taskbar and arrangement, and their first cascade and layout. The loading screen runs it, as the load
     * task {@code crystalgui:desktop}. Render thread; does nothing once built.
     *
     * <p>Nothing presents it until a screen is up; {@link #rehearseStep} draws a throwaway one meanwhile.</p>
     */
    public void preload() {
        if (host != null) return;
        refreshHost();
        build();
        DesktopHost built = host;
        post(() -> built.document().update(snapshot.surfaceWidth(), snapshot.surfaceHeight()));
    }

    @Nullable
    private DesktopRehearsal rehearsal;
    private boolean rehearsed;

    /**
     * Rehearses the desktop behind the loading screen: {@link CgRenderStage#SCREEN} fired into a hidden target with a
     * throwaway desktop on it, until the JIT, the pipelines, the blur targets and the rasters a first open needs have
     * settled. The load task {@code crystalgui:desktop-warm} calls it once a frame until it answers true. Render thread.
     *
     * @return whether the rehearsal is over: settled, failed (logged), or there is no desktop to rehearse
     */
    public boolean rehearseStep() {
        if (rehearsed || host == null) return true;
        if (rehearsal == null) rehearsal = new DesktopRehearsal();
        if (!rehearsal.step()) return false;
        rehearsal = null;
        rehearsed = true;
        return true;
    }

    /** Its screen is up: builds the desktop the first time and resumes it every time after, a resize included. */
    void shown() {
        closing = null;
        left = false;
        refreshHost();
        if (host == null) build();
        else post(host::shown);
        post(this::bringForward);
    }

    /**
     * Its screen went away, and nothing is destroyed: the desktop records its arrangement and each application its
     * session. A pending launch is abandoned, or it would open a window the next time the desktop appeared for some
     * unrelated reason.
     */
    void hidden() {
        closing = null;
        left = false;
        launchPending = false;
        DesktopHost built = host;
        if (built != null) post(built::hidden);
    }

    /**
     * The desktop plays itself off screen, then {@code close} runs from a later {@link #frame}. False, having run
     * nothing, when it should close now: nothing is built, or animations are off. Asked again while it plays, true.
     */
    private boolean requestHide(Runnable close) {
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
        if (viewport != null) viewport.close();
        viewport = null;
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

    /** What puts the desktop's document on screen; null until the surface has been shown once. */
    @Nullable
    public CgUiViewport viewport() {
        return viewport;
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
        CgUiViewport shown = viewport;
        return shown == null ? null : shown.readout(CgUiViewport.TEXT_INPUT_AREA);
    }

    // ── Painting over the game ──────────────────────────────────────────────────────────────────

    /**
     * What the desktop should be showing right now — <b>the one place a game condition becomes a
     * presentation</b>, which is what keeps every paint hook and every input hook agreeing.
     *
     * <p>The foreign-screen transition is noticed here rather than in a hook of its own, because this is
     * the one thing every caller runs. A dedicated handler on a world-render event was the first attempt
     * and is wrong for the case that matters least often and breaks worst: a screen that renders no world
     * fires no such event, so the close is never seen and ownership survives into the next screen.</p>
     */
    public DesktopPresentation presentation() {
        CgUiViewport shown = viewport;
        if (shown == null) return DesktopPresentation.NONE;
        if (shown.isSequenced()) return presentationFromCommit(shown);
        return shown.ask(this::presentationOnDocument);
    }

    /** {@link #presentationOnDocument}, from what the document last committed rather than from its tree. */
    private DesktopPresentation presentationFromCommit(CgUiViewport shown) {
        Desktop desktop = desktop();
        if (desktop == null) return DesktopPresentation.NONE;
        boolean ours = CgScreens.isOpen(screen);
        boolean any = CgScreens.anyOpen();
        noteForeignScreen(desktop, any && !ours);
        Boolean attached = shown.readout(DesktopContent.ATTACHED);
        // Before the first commit the desktop is attached and holds nothing pinned, as it was built.
        return Desktop.presentation(ours, any, attached == null || attached,
                Boolean.TRUE.equals(shown.readout(DesktopContent.PINNED)));
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

    private DesktopPresentation presentationOnDocument() {
        Desktop desktop = desktop();
        if (desktop == null) return DesktopPresentation.NONE;

        boolean ours = CgScreens.isOpen(screen);
        boolean any = CgScreens.anyOpen();

        noteForeignScreen(desktop, any && !ours);
        return desktop.presentation(ours, any);
    }

    // ── Input offered over a screen ─────────────────────────────────────────────────────────────

    /**
     * Offers one pointer event to the pinned windows, and <b>refuses it while the pointer is the camera</b>.
     *
     * <pre>{@code
     * // the SCREEN input stage's listener: forward only what the desktop did not take
     * if (session.offerMouse(CgInput.pointerGrabbed(), x, y, button, pressed, wheel)) {
     *     event.setCanceled(true);
     * }
     * }</pre>
     *
     * <p>With no screen up there is no cursor: the position runs with the look direction and a press is
     * an attack, so hit-testing it would press whatever pinned window the player is looking at. A grab
     * under a screen means the same, since a mod may capture the pointer there. A host reports the grab;
     * this decides.</p>
     *
     * @param grabbed whether the host has captured the pointer
     * @param yPx     surface pixels from the TOP; a host whose toolkit measures from the bottom flips it
     * @return whether the desktop consumed it, so the screen must not see it. See {@link ScreenOverlay#offerMouse}
     */
    public boolean offerMouse(boolean grabbed, int xPx, int yPx, int button, boolean pressed, float wheel) {
        if (grabbed || !CgScreens.anyOpen()) return false;
        ScreenOverlay overlay = screenOverlay();
        if (overlay == null) return false;
        // Sequenced, the overlay answers from its committed regions and posts what it delivers.
        CgUiViewport shown = viewport;
        if (shown == null || shown.isSequenced()) return overlay.offerMouse(xPx, yPx, button, pressed, wheel);
        return shown.ask(() -> overlay.offerMouse(xPx, yPx, button, pressed, wheel));
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
        ScreenOverlay overlay = desktop == null || document() == null ? null : desktop.screenOverlay();
        if (overlay != null) overlay.shownThrough(viewport);
        return overlay;
    }

    /**
     * The order CrystalGUI's windows draw at on {@link CgRenderStage#HUD} and {@link CgRenderStage#SCREEN}: the game's
     * own interface, {@link CgRenderStage#VIEWPORT_WIDGETS}.
     */
    public static final int COMPOSITOR = CgRenderStage.VIEWPORT_WIDGETS;

    /**
     * Paints the desktop in {@code stage}'s firing, at {@link #COMPOSITOR}, <b>only when the compositor is actually in
     * it</b>: from {@link CgRenderStage#HUD} the HUD arm, from {@link CgRenderStage#SCREEN} whichever arm is current --
     * our own screen's desktop, the overlay over somebody else's, or the HUD arm on the frame a screen closed itself
     * while it drew. One arm a frame: a frame with a screen up fires both stages, and painting the compositor twice
     * lets the second pass win the hit test's localToWorld. The desktop's arm runs the host tick ({@link #frame})
     * first, with the same delta.
     *
     * <p><b>A fault here must not take the game down.</b> This runs inside the game's own render loop every frame, and
     * unlike a screen there is nothing the player can close to escape it. The mode is dropped instead, which puts them
     * back in a working game with their windows intact on the desktop. Deciding what to present is inside the guard
     * too: it reads the compositor and can throw for the same reasons painting it can.</p>
     */
    void paintAt(CgStageFrame stage, boolean screenStage) {
        if (CgRehearsal.active()) {
            DesktopRehearsal playing = rehearsal;
            if (screenStage && playing != null) playing.paint(stage);
            return;
        }
        Desktop desktop = desktop();
        CgUiViewport owner = viewport;
        if (desktop == null || document() == null || owner == null) return;
        DesktopPresentation now;
        try {
            now = presentation();
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] could not decide a presentation; leaving HUD mode", failed);
            post(desktop::exitHudMode);
            return;
        }
        // SCREEN paints whatever arm is current, HUD only its own; once a frame. A screen that closed itself while it
        // drew left the HUD stage's turn already past: SCREEN, firing after that draw, paints the HUD arm in its place.
        long frameIndex = CgFrameClock.frameIndex();
        if (now == DesktopPresentation.NONE || paintedFrame == frameIndex
                || !screenStage && now != DesktopPresentation.HUD) {
            return;
        }
        paintedFrame = frameIndex;
        refreshHost();
        returnUnhandledKeys();
        BiConsumer<CgStageFrame, UiPresenter> replaced = replacement;
        if (replaced != null) {
            replacement = null;
            replaced.accept(stage, owner.presenter());
            return;
        }
        try {
            // Advanced by the arm that paints alone: one that read it and stood down would leave this one a delta of
            // nearly zero, stalling every animation, a smooth scroll's included.
            float delta = owner.advance();
            if (now == DesktopPresentation.DESKTOP) frame(delta);
            if (owner.frame(stage, delta, contents[now.ordinal()])) painted = true;
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] overlay paint failed; leaving HUD mode", failed);
            post(desktop::exitHudMode);
        }
    }

    @Nullable
    private BiConsumer<CgStageFrame, UiPresenter> replacement;

    /**
     * Runs {@code paint} once, at the next frame the desktop paints and in place of that paint, with the frame's stage
     * and the document's presenter: a probe presenting a frame itself. The host tick does not run that frame. Render
     * thread.
     *
     * <pre>{@code
     * session.replaceNextPaint((stage, presenter) -> presenter.presentAgain(stage));   // the last frame, as it was
     * }</pre>
     */
    public void replaceNextPaint(BiConsumer<CgStageFrame, UiPresenter> paint) {
        replacement = paint;
    }

    /** {@link CgFrameClock#frameIndex()} of the last frame an arm painted in: one arm a frame. Render thread. */
    private long paintedFrame = -1L;

    /** The desktop in each presentation, by ordinal: made once it is built, so a frame allocates none. */
    private DesktopContent[] contents = new DesktopContent[0];

    /** Every key the document dispatched and left since the last frame, to the game, in order. Render thread. */
    private void returnUnhandledKeys() {
        for (CgSystemInput.Keyboard.Event key; (key = input.pollUnhandledKey()) != null; ) {
            if (!offerLeftKey(key)) CgScreens.giveBack(key);
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
        snapshot = new HostSnapshot();
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
        viewport = CgUiViewport.open(built.document(), CgUiViewport.Threading.fromFlags(), "desktop");
        DesktopPresentation[] presentations = DesktopPresentation.values();
        contents = new DesktopContent[presentations.length];
        for (DesktopPresentation presentation : presentations) {
            contents[presentation.ordinal()] = new DesktopContent(built.desktop(), presentation);
        }
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
        ApplicationKind primaryKind = built.desktop().applications().primary();
        if (primaryKind == null) return false;

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
     * <p>The bare-desktop key launches nothing: only {@link #openApplication()} builds the primary
     * application, so an empty desktop stays empty.</p>
     */
    private void bringForward() {
        Desktop desktop = desktop();
        if (desktop == null) return;

        if (!raiseOnShow) return;
        // No primary: an empty desktop stays empty
        if (desktop.applications().primary() == null) {
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
