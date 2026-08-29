package com.crystalgui.core.async;

import lombok.Getter;
import lombok.Setter;

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

    // ── The assertion ────────────────────────────────────────────────────────────

    /**
     * Whether {@link #require} throws. On by default; a host may turn it off, and nothing else should.
     *
     * <p>Not {@code -ea}. Java assertions are off in production, and production -- a Minecraft client
     * with mods in it -- is the only place a foreign thread ever touches this tree. An engine whose
     * safety check is disabled exactly where it is needed has a comment, not a check.</p>
     * -- GETTER --
     * Whether 
     *  is armed. 
     * -- SETTER --
     *  Turns the assertion off, for a host that would rather take the corruption than the exception.
     *  <p>Exists because refusing to be turned off is how a safety check gets deleted. A host that ships
     *  with this false is choosing the failure mode the javadoc above describes.</p>


     */
    @Setter
    @Getter
    private static volatile boolean enforcing = true;

    /**
     * Throws unless this is the thread that runs frames.
     *
     * <h3>Why an exception and not a log line</h3>
     *
     * <p>Because the damage is already done and is silent. {@code UIElement}, {@code ElementStyle}, the
     * live Taffy tree and {@code UIInputHandler}'s hover, focus and capture state are mutable,
     * cross-referential and read during paint, and none has a safe concurrent reader -- so an off-thread
     * write does not fail, it CORRUPTS. The engine has already paid for this once: a script thread
     * emitted a signal, a listener called {@code setEnabled}, that reached
     * {@code invalidateStyleMatch()}, and the cascade's dirty-match {@code HashSet} was mutated while
     * the frame thread was copying it -- {@code ArrayIndexOutOfBoundsException: Index 358 out of bounds
     * for length 358} out of {@code HashMap.keysToArray}, thrown in {@code advanceFrame} with nothing
     * about the Run panel anywhere in the trace. A stack trace at the WRITE names the culprit; a crash
     * one frame later names the victim.</p>
     *
     * <p><b>Silent before the first frame.</b> There is no tree to own until one has run, so a headless
     * test, a dedicated server and a background load are all correctly "not the UI thread" and must not
     * be refused -- {@code headlessTest} would otherwise fail wholesale. That is also why this can be
     * added to hot paths now and tightened later: it costs one volatile read until a host marks a
     * thread, and one reference comparison after.</p>
     *
     * @param what what was being attempted, for the message. Name the OPERATION, not the class.
     */
    public static void require(String what) {
        require(what, owner);
    }

    /**
     * Throws unless this is {@code treeOwner}, the thread that runs frames <b>for the tree being
     * touched</b>.
     *
     * <h3>Ownership is per-TREE, not per-process, and the difference is the whole usability of this</h3>
     *
     * <p>A process-wide owner refuses any thread that is not the one that most recently drew -- which is
     * correct in a game, where there is one loop, and wrong everywhere else. It fails a test suite
     * wholesale: JUnit's {@code timeout} runs each method on its own thread while the runner thread that
     * drove an earlier test is still alive, so building a tree in one test is refused on behalf of a
     * thread that has nothing to do with it. Four editor tests reported exactly that, and the message
     * read {@code must happen on (Time-limited test), not on Time-limited test} -- the same name twice,
     * which reads as the check being broken rather than as two different threads.</p>
     *
     * <p>Asking the tree instead is both stricter and quieter. A tree nothing has painted has no owner
     * and cannot be refused, which is every tree a headless test or a dedicated server builds. A tree
     * that IS being painted refuses everyone but its own painter, which is the case worth catching and
     * the only one that can corrupt anything.</p>
     *
     * @param treeOwner the thread that runs frames for this tree, or null if none ever has
     */
    public static void require(String what, @Nullable Thread treeOwner) {
        if (!enforcing) return;
        Thread known = treeOwner;
        if (known == null || known == Thread.currentThread()) return;

        // A DEAD OWNER PROTECTS NOTHING. If the thread that ran frames has terminated there is no live
        // tree for it to own, so refusing on its behalf is refusing on behalf of nobody -- and the
        // caller is by definition the only thread there is now, so it takes ownership.
        //
        // Not a test accommodation, though a test is what found it: JUnit's `timeout` runs each method
        // on a fresh thread, and every one of them is named "Time-limited test", so the refusal read
        // `... must happen on (Time-limited test), not on Time-limited test` -- the same name twice,
        // which is what makes a live-thread check look broken rather than strict. In a game the frame
        // thread outlives everything, so this branch never fires there; the case it genuinely covers is
        // a host that tears its window down and builds another on a new thread.
        if (!known.isAlive()) return;

        throw new IllegalStateException(
                what + " must happen on the thread that runs frames (" + known.getName()
                        + "), not on " + Thread.currentThread().getName() + ". The UI tree has no safe "
                        + "concurrent reader, so this would corrupt it rather than fail. Anything that is "
                        + "a pure function of a snapshot belongs on JobScheduler, whose onDone hands the "
                        + "answer back here during drain().");
    }
}
