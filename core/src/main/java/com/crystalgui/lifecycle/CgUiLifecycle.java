package com.crystalgui.lifecycle;

import com.crystalgui.core.dispose.Disposer;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadListener;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.asset.CgUiSpriteRegistry;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.StyleEngine;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.desktop.host.DesktopKeys;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.UiPaintWarmup;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * CrystalGUI's single hook into the GL context lifecycle.
 *
 * <p>CrystalGUI holds GL resources and caches of GL-derived objects, but it is not part of
 * CrystalGraphics and cannot be enumerated inside {@code CgGraphicsLifecycle}'s own teardown. This
 * class is the seam: one {@link CgLifecycleListener} registered with the engine, which then drives
 * every CrystalGUI-side subsystem that has a lifecycle.</p>
 *
 * <h3>Why a coordinator rather than each subsystem registering itself</h3>
 * <p>Teardown order matters and is only knowable from here. {@link CgUiPaintContext} must release
 * its framebuffers and renderer before the caches that feed it are dropped, and all of it must
 * happen before CrystalGraphics sweeps the registries those resources came from. Letting four
 * classes each register independently would make that order an accident of class-initialisation
 * timing. One listener, one explicit sequence.</p>
 *
 * <h3>Registration is CrystalGraphics' discovery</h3>
 * <p>Declared {@code client} in {@code META-INF/crystalgraphics/listeners}, so the engine registers it on every client
 * before its graphics start, and no host names it. A dedicated server never loads it. {@link CgUiPaintContext}'s
 * static initializer still calls {@link #register()} for a process the engine's discovery did not reach (a test
 * painting on its own); it is idempotent.</p>
 */
public final class CgUiLifecycle implements CgLifecycleListener, CgReloadListener {

    /** The one listener; what the engine's discovery registers. */
    public static final CgUiLifecycle INSTANCE = new CgUiLifecycle();

    private CgUiLifecycle() {
    }

    /**
     * Registers CrystalGUI with the engine lifecycle by hand, where the engine's discovery did not. Idempotent: a
     * double {@code onContextDestroy} would be a double free. Before or after a context exists; a late one hears
     * {@link #onContextInit} at once.
     */
    public static void register() {
        CgEngineLifecycle.addListener(INSTANCE);
        CgAssetReloader.addListener(INSTANCE);
    }

    /**
     * Does what a resource reload does, without one -- a dev host's reload key.
     *
     * <pre>{@code
     * if (ctrl && key == R) CgUiLifecycle.reload();   // edited .css and .svg files reappear
     * }</pre>
     *
     * <p>One definition of "reload" for every host: a key that re-read only the stylesheets left every
     * edited icon on screen, because the icon caches are this method's to revalidate.</p>
     */
    public static void reload() {
        INSTANCE.onReload();
    }

    /** Unregisters CrystalGUI from the engine lifecycle. Rarely needed outside tests. */
    public static void unregister() {
        CgEngineLifecycle.removeListener(INSTANCE);
        CgAssetReloader.removeListener(INSTANCE);
    }

    /**
     * F3+T, and a resource pack change: re-read what CrystalGUI keeps from disk.
     *
     * <p>The caches go before the stylesheets, and the order is load-bearing: the restyle below
     * re-resolves every {@code asset(...)} and {@code icon(...)}, and must find the JSON and the
     * {@code .svg} files as they are now rather than what the previous reload left behind. Only an SVG
     * whose file changed is dropped (see {@code SvgDocument.revalidate}); the rest keep their parse and,
     * since {@code SvgRasterCache} keys on the document, their raster.</p>
     *
     * <p>{@code StyleEngine.reloadStylesheets()} is the whole of the CSS half — it refills the sheets
     * in place, so existing registrations stay valid, and re-matches every live window. Its own
     * javadoc named this path before anything called it.</p>
     *
     * <p>Font families are deliberately left alone. They are CrystalGraphics' objects, reloaded on
     * its side, and dropping ours would orphan the shaped paragraphs every {@code UIText} retains.</p>
     */
    @Override
    public void onReload() {
        CgUiSpriteRegistry.clearCache();
        // REVALIDATED, not cleared: a full clear re-parses every icon ever drawn, seconds at a few
        // hundred of them, to pick up the one that changed.
        int icons = SvgDocument.revalidate();
        FileIconTheme.invalidateCache();
        int sheets = StyleEngine.reloadStylesheets();
        CrystalGuiCore.LOGGER.info("CrystalGUI resource reload: {} stylesheet(s) re-read, {} icon(s) changed",
                sheets, icons);
    }

    /**
     * The client's desktop ({@link HostSession}, opening on {@link CrystalEditor}) and its keys, before any loader
     * registers keys; then CrystalGUI's start on a worker ({@link CgUiStart}), behind the rest of mod loading, and every
     * UI shader named to the precache so the loading screen builds them. A client only.
     */
    @Override
    public void onPreInit() {
        // Before the keys: a key pressed before the session exists would open nothing.
        HostSession.install(CrystalEditor.KIND);
        DesktopKeys.declare();
        startOnce();
    }

    private boolean started;

    /** Once, at whichever comes first: a host that never runs pre-init (the harness) reaches only the context's start. */
    private synchronized void startOnce() {
        if (started) return;
        started = true;
        CgUiStart.ahead();
        CgUiPaintContext.precache();
    }

    /** Installs the GL gate for disposals. */
    @Override
    public void onContextInit(int width, int height) {
        startOnce();
        CgUiPaintContext.parseAhead();

        // The GL gate, installed the moment a context exists.
        //
        // Until now Disposer has been running every disposal immediately, which is correct while there
        // is no context to be off the thread of -- and is what keeps the whole class usable from
        // headlessTest. From here a Disposable.Gl disposed from anywhere else is queued and drained by
        // onFrame below, because freeing a GL object off the GL thread is silent corruption rather than
        // an exception.
        //
        // The thread that receives onContextInit IS the GL thread: CgEngineLifecycle.ensureGraphicsContext() is called
        // from it by definition, and a listener registered later is delivered onContextInit on the caller's
        // thread for the same reason.
        Thread glThread = Thread.currentThread();
        Disposer.setGlGate(() -> Thread.currentThread() == glThread, pending::add);
    }

    /**
     * What painting loads before the first frame, then the desktop built and rehearsed behind the screen: load tasks,
     * tracked and named on the loading screen, each stepped in its budget.
     */
    @Override
    public void onLoad(CgLoadPlan plan) {
        // The default font, the icons and the sheets' glyphs, each on a worker. The pipelines are the precache's
        // (CgUiPaintContext.precache), the GPU's first-frame work the rehearsal's below.
        UiPaintWarmup preload = UiPaintWarmup.preload();
        plan.task("crystalgui:paint").render(task -> {
            // Behind the start: a paint class initialising beside its style classes could wait on one it holds.
            if (!CgUiStart.done()) {
                task.nextFrame();
                return false;
            }
            return preload.step(task);
        }).start();
        // THE DESKTOP BUILT BEHIND THE SCREEN, after the fonts and shaders its first frame needs, so opening it is
        // a resume. A client whose host installed no session has none to build.
        plan.task("crystalgui:desktop").after("crystalgui:paint").render(task -> {
            if (HostSession.isInstalled()) HostSession.session().preload();
            return true;
        }).start();
        // THE DESKTOP REHEARSED BEHIND THE SCREEN, on the SCREEN stage into its hidden target: its first paint is
        // hundreds of ms of pipelines, blur targets, rasters and cold code that would otherwise land inside the open
        // animation. After the engine's programs, which build its canvas's glass and text: rehearsed before them, it
        // built them at its draw.
        plan.task("crystalgui:desktop-warm").after("crystalgui:desktop", "crystalgraphics:programs").render(task -> {
            return !HostSession.isInstalled() || HostSession.session().rehearseStep();
        }).start();
    }

    /**
     * Disposals that arrived off the GL thread, waiting for {@link #onFrame}.
     *
     * <p>A queue rather than a direct hand-off because there is no way to <em>call</em> the GL thread —
     * it is a loop, not an executor. {@code onFrame} is the one place we are certainly on it.</p>
     */
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

    /**
     * Drains GL disposals that were requested from elsewhere.
     *
     * <p>The rest of CrystalGUI's per-frame work is driven by {@code UIWindow.paintFrame()}, which is
     * per-window and called by whoever owns the window rather than by the engine's tick. This hook has
     * one global job and this is it: {@code Disposer} defers anything owning GPU memory to the GL
     * thread, and this is the one moment we are certainly on it.</p>
     *
     * <p>Costs one empty-queue check per frame when nothing is pending, which is the steady state.</p>
     */
    @Override
    public void onFrame(long frame) {
        Runnable due;
        while ((due = pending.poll()) != null) due.run();
    }

    /**
     * Releases the GL resources CrystalGUI owns outright.
     *
     * <p>Runs before CrystalGraphics frees anything (see {@link CgLifecycleListener#onContextDestroy}), so
     * everything touched here is still valid.</p>
     *
     * <h3>Why this is only the paint context</h3>
     * <p>{@code destroyContext()} fires <b>only at game shutdown</b> — there is no destroy-then-init
     * cycle within a running process. So there is no "next context" to protect, and nothing needs
     * invalidating merely because CrystalGraphics is about to free it: caches of font families,
     * stylesheets and sprites are all about to die with the process regardless. Clearing them here
     * would be ceremony, not correctness.</p>
     *
     * <p>What remains is the one thing genuinely nobody else frees: the textures each {@link CgUiPaintContext}
     * requested from the graph — its frame target, surfaces, backdrop and icon rasters — which no registry holds.
     * Releasing them here matches the engine's own convention of explicit, complete teardown rather than leaving it
     * to process death.</p>
     *
     * <p>Isolated so a failure is logged rather than escaping into engine teardown — the engine
     * already isolates this listener as a whole, but keeping it local means the log names CrystalGUI
     * as the culprit.</p>
     */
    @Override
    public void onContextDestroy() {
        // NO DOCUMENT TEARDOWN HERE, and that is not an omission. `UIWindow.shutdownAll()` detached
        // each window's desktop and cleared an attached-window back-pointer -- tree bookkeeping on a
        // tree the process is about to stop having. This file's own rule for `onContextDestroy` says the same
        // thing about caches: at game shutdown there is no next context to protect, so anything that
        // only tidies state is ceremony rather than correctness. What genuinely must happen on close
        // -- writing the session -- is the desktop's, and `HostSession` does it when its screen closes.
        //
        // What stays below is the one thing nobody else frees.
        try {
            CgUiPaintContext.releaseAll();
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("CgUiLifecycle: failed to tear down the paint context", t);
        }
    }
}
