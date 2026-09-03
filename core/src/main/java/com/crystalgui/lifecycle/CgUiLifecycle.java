package com.crystalgui.lifecycle;

import com.crystalgui.core.dispose.Disposer;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.CgUiPaintContext;

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
 * <h3>Registration is automatic</h3>
 * <p>A static initializer in {@link CgUiPaintContext} calls {@link #register()}, so CrystalGUI wires
 * itself up as soon as that class comes into play — which is exactly when it is about to own
 * something that needs releasing. There is no integration step for a loader or a harness scene to
 * remember, and no way for the two to fall out of step. A process that never paints (a dedicated
 * server) never touches that class, so it never registers and stays free of CrystalGraphics
 * entirely.</p>
 *
 * <p>Class initialization runs once per classloader, so this cannot re-register across a
 * destroy/recreate cycle.</p>
 *
 * <p>{@link #register()} remains public for explicit control and for tests; it is idempotent.</p>
 *
 * <p>The platform adapter already calls
 * {@code CgGraphicsLifecycle.initContext/tickFrame/destroyContext}; this rides along with those, so
 * no loader module needs to know CrystalGUI has a lifecycle at all.</p>
 */
public final class CgUiLifecycle implements CgLifecycleListener {

    private static final CgUiLifecycle INSTANCE = new CgUiLifecycle();

    private CgUiLifecycle() {
    }

    /**
     * Registers CrystalGUI with the engine lifecycle. Idempotent — {@code CgGraphicsLifecycle}
     * ignores a duplicate registration, which matters because a double {@code onDestroy} would be a
     * double free.
     *
     * <p>Called automatically from {@link CgUiPaintContext}'s static initializer — see the class
     * javadoc. Calling it yourself is harmless, and only useful if you want CrystalGUI registered
     * before anything has touched the paint context.</p>
     *
     * <p>Safe to call before a GL context exists or after one is already live. In the latter case —
     * which is the <em>normal</em> case here, since the paint context's class initializer runs on the
     * first paint, long after {@code initContext} — {@code CgGraphicsLifecycle.addListener} delivers
     * {@link #onInit} immediately, so the callback is not silently skipped.</p>
     */
    public static void register() {
        CgGraphicsLifecycle.addListener(INSTANCE);
    }

    /** Unregisters CrystalGUI from the engine lifecycle. Rarely needed outside tests. */
    public static void unregister() {
        CgGraphicsLifecycle.removeListener(INSTANCE);
    }

    /**
     * Nothing to eagerly build.
     *
     * <p>Deliberately empty rather than pre-warming: every CrystalGUI GL resource is created lazily
     * on the first paint, and {@link CgUiPaintContext}'s laziness is load-bearing — constructing it
     * here would trigger material compilation and font loading in every process that merely touches
     * this class, including a dedicated server that will never draw a frame. The hook is implemented
     * so the intent is on the record: this is "nothing to do", not "nobody wired it up".</p>
     *
     * <p><b>This does fire.</b> Because registration happens from a class initializer on the first
     * paint — after {@code initContext} has already run — this arrives via
     * {@code CgGraphicsLifecycle.addListener}'s immediate delivery for late registrants rather than
     * during {@code initContext} itself. Anything added here will run; it does not need the
     * registration to have been early.</p>
     */
    @Override
    public void onInit(int width, int height) {
        // The GL gate, installed the moment a context exists.
        //
        // Until now Disposer has been running every disposal immediately, which is correct while there
        // is no context to be off the thread of -- and is what keeps the whole class usable from
        // headlessTest. From here a Disposable.Gl disposed from anywhere else is queued and drained by
        // onFrame below, because freeing a GL object off the GL thread is silent corruption rather than
        // an exception.
        //
        // The thread that receives onInit IS the GL thread: CgGraphicsLifecycle.initContext() is called
        // from it by definition, and a listener registered later is delivered onInit on the caller's
        // thread for the same reason.
        Thread glThread = Thread.currentThread();
        Disposer.setGlGate(() -> Thread.currentThread() == glThread, pending::add);

        // Warmup the paint context, around 1000ms on first init done before world frame time.
        try (CgGlScope ignored = CgGlState.save(CgGlSlot.DEPTH, CgGlSlot.PROGRAM)) {
            CgUiPaintContext.getInstance().warm(width, height);
        }
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
     * <p>Runs before CrystalGraphics frees anything (see {@link CgLifecycleListener#onDestroy}), so
     * everything touched here is still valid.</p>
     *
     * <h3>Why this is only the paint context</h3>
     * <p>{@code destroyContext()} fires <b>only at game shutdown</b> — there is no destroy-then-init
     * cycle within a running process. So there is no "next context" to protect, and nothing needs
     * invalidating merely because CrystalGraphics is about to free it: caches of font families,
     * stylesheets and sprites are all about to die with the process regardless. Clearing them here
     * would be ceremony, not correctness.</p>
     *
     * <p>What remains is the one thing genuinely nobody else frees. {@link CgUiPaintContext}'s layer
     * FBO pool is built with {@code CgFrameBuffer.createOwned}, which bypasses
     * {@code CgFrameBufferRegistry} — so {@code CgFrameBufferRegistry.deleteAll()} does not reach it.
     * Releasing it here matches the engine's own convention of explicit, complete teardown rather
     * than leaving it to process death.</p>
     *
     * <p>Isolated so a failure is logged rather than escaping into engine teardown — the engine
     * already isolates this listener as a whole, but keeping it local means the log names CrystalGUI
     * as the culprit.</p>
     */
    @Override
    public void onDestroy() {
        // NO DOCUMENT TEARDOWN HERE, and that is not an omission. `UIWindow.shutdownAll()` detached
        // each window's desktop and cleared an attached-window back-pointer -- tree bookkeeping on a
        // tree the process is about to stop having. This file's own rule for `onDestroy` says the same
        // thing about caches: at game shutdown there is no next context to protect, so anything that
        // only tidies state is ceremony rather than correctness. What genuinely must happen on close
        // -- writing the session -- is the HOST's, and `CgUiScreen` does it when the screen closes.
        //
        // What stays below is the one thing nobody else frees.
        try {
            CgUiPaintContext.destroy();
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("CgUiLifecycle: failed to tear down the paint context", t);
        }
    }
}
