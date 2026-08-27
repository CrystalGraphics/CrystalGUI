package com.crystalgui.core.async;

import javax.annotation.Nullable;

/**
 * Which thread runs frames — the one that <b>owns the retained tree</b>.
 *
 * <h3>Ownership, not cost, is what decides where something may run</h3>
 *
 * <p>{@code UIElement}, {@code ElementStyle}, the live Taffy tree, {@code RuntimeCache}'s memo cells and
 * {@code UIInputHandler}'s hover, focus and capture state are all mutable, cross-referential, and read
 * during paint. None of them has a safe concurrent reader, so everything that touches the tree —
 * the cascade, layout, hit-testing, input dispatch, paint, and every listener that reaches an element —
 * <b>must</b> run here. That is the same line every reference draws: Chrome's main thread runs script,
 * style, layout and paint recording while only compositing and raster leave it; AppKit, GTK, Swing's EDT,
 * the JavaFX application thread and Android's UI thread are the same choice.</p>
 *
 * <p>The rule that follows is the one worth stating: <b>anything that is a pure function of a snapshot
 * must NOT run here</b> — I/O, opening an archive, compiling, decompiling, building an index, probing a
 * classpath. Those go to {@link JobScheduler}, whose {@code onDone} hands the answer back on this thread
 * during {@code drain()}.</p>
 *
 * <h3>Why this is worth knowing at runtime</h3>
 *
 * <p>Because the expensive things do not look expensive from the call site. {@code symbolOf(Resource)}
 * reads like a property getter and was a 761ms compile; {@code HostClasspath.detect()} reads like a
 * getter and opened every jar on the classpath. Neither caller did anything wrong, so an audit of
 * callers could not have found either. {@link UiBudget} uses this to name the operation instead.</p>
 *
 * <p><b>Not known until the first frame</b>, and that is deliberate rather than a gap: before one has
 * run there is no tree to own, so a headless test, a server and a background load are all correctly
 * "not the UI thread" and pay nothing.</p>
 */
public final class UiThread {

    private UiThread() {
    }

    /** Volatile rather than synchronized: written once per process, read on every provider call. */
    @Nullable
    private static volatile Thread owner;

    /**
     * Records the calling thread as the one that runs frames.
     *
     * <p>Called from the frame itself, so it is right whatever drives it — a real window, the harness,
     * or a test stepping frames by hand. Re-marking is free and keeps it correct if a host ever moves
     * its loop.</p>
     */
    public static void markCurrent() {
        Thread current = Thread.currentThread();
        if (owner != current) owner = current;
    }

    /** Whether this is the thread that runs frames. False before the first frame — see the class note. */
    public static boolean isCurrent() {
        Thread known = owner;
        return known != null && known == Thread.currentThread();
    }

    /** Forgets the marked thread, so a test can assert what happens before any frame has run. */
    public static void forgetForTesting() {
        owner = null;
    }
}
