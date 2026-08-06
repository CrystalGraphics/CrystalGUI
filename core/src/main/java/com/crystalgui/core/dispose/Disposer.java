package com.crystalgui.core.dispose;

import com.crystalgui.core.CrystalGuiCore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * The ownership tree — a port of IntelliJ's {@code Disposer}.
 *
 * <h3>The one rule</h3>
 *
 * <p>Everything registers against a parent, and disposing a parent releases its children <b>first, in
 * reverse registration order</b>, before releasing the parent itself. Reverse order is not tidiness: a
 * child registered later may have been built <em>from</em> an earlier one, so releasing forwards frees
 * a dependency out from under its dependent. It is the same argument {@code CompositeEdit} makes about
 * undoing backwards, and the same one {@code CgGraphicsLifecycle} makes about VAOs before VBOs.</p>
 *
 * <h3>Identity, not equality</h3>
 *
 * <p>The maps are {@link IdentityHashMap}. A disposable is a <em>thing</em>, not a value, and two
 * equal-but-distinct objects own different resources. Using {@code equals} here would let one release
 * another's memory and leave its own — which is exactly the class of bug this exists to remove.</p>
 *
 * <h3>Iterative, not recursive</h3>
 *
 * <p>The walk is explicit rather than recursive because a disposal tree mirrors a widget tree, and a
 * deep UI is thousands of levels in the pathological case. A {@code StackOverflowError} during teardown
 * is unrecoverable and leaves half the graph freed.</p>
 *
 * @see Disposable for what this is for and, more importantly, what it is not
 */
public final class Disposer {

    private Disposer() {
    }

    /** Parent → its children, in registration order. Identity-keyed; see the class note. */
    private static final Map<Disposable, List<Disposable>> CHILDREN = new IdentityHashMap<>();

    /** Child → its parent, so disposing a child can unlink it without searching. */
    private static final Map<Disposable, Disposable> PARENTS = new IdentityHashMap<>();

    /** Everything already released. Kept so a double dispose is a no-op rather than a second free. */
    private static final Map<Disposable, Boolean> DISPOSED = new IdentityHashMap<>();

    /** {@link Disposable.Gl} instances waiting for the GL thread; see {@link #setGlGate}. */
    private static final Deque<Disposable> GL_QUEUE = new ArrayDeque<>();

    private static BooleanSupplier onGlThread = () -> true;
    private static Consumer<Runnable> deferToGlThread = Runnable::run;

    /**
     * How a {@link Disposable.Gl} reaches the GL thread.
     *
     * <p><b>The default runs everything immediately</b>, which is what makes this whole class usable
     * from {@code headlessTest} where there is no context and no GL thread to be off. A host with a
     * real context installs a gate during initialisation; nothing else changes.</p>
     *
     * @param onGl    whether the calling thread may issue GL commands
     * @param deferGl hands work to the GL thread. Called only when {@code onGl} is false
     */
    public static synchronized void setGlGate(BooleanSupplier onGl, Consumer<Runnable> deferGl) {
        onGlThread = onGl == null ? () -> true : onGl;
        deferToGlThread = deferGl == null ? Runnable::run : deferGl;
    }

    /**
     * Makes {@code child}'s lifetime a part of {@code parent}'s.
     *
     * <p>Registering against an already-disposed parent <b>disposes the child immediately</b> rather
     * than throwing or silently retaining it. Both alternatives are worse: throwing turns a lifetime
     * race into a crash on a teardown path, and retaining is the leak this class exists to prevent.
     * IntelliJ does the same.</p>
     */
    public static void register(Disposable parent, Disposable child) {
        if (!tryRegister(parent, child)) dispose(child);
    }

    /**
     * As {@link #register}, but reports rather than acting when the parent is gone.
     *
     * @return false when {@code parent} is already disposed — the child is left untouched
     */
    public static synchronized boolean tryRegister(Disposable parent, Disposable child) {
        if (parent == null || child == null) {
            throw new IllegalArgumentException("neither parent nor child may be null");
        }
        if (parent == child) throw new IllegalArgumentException("a disposable cannot own itself");
        if (DISPOSED.containsKey(parent)) return false;
        if (DISPOSED.containsKey(child)) return true;      // nothing left to own

        Disposable existing = PARENTS.get(child);
        if (existing == parent) return true;
        if (existing != null) unlink(child, existing);

        CHILDREN.computeIfAbsent(parent, key -> new ArrayList<>()).add(child);
        PARENTS.put(child, parent);
        return true;
    }

    /**
     * Releases {@code target} and everything registered under it.
     *
     * <p>Children first and in reverse registration order; then the target; then it is unlinked from
     * its own parent so a later disposal of that parent does not reach it again.</p>
     *
     * <p><b>A throw from one {@code dispose()} does not stop the rest.</b> Teardown is exactly when a
     * half-finished job is worst, so a failure is logged and the walk continues — the same reasoning
     * {@code CgAssetReloader} uses for isolating each reload step.</p>
     */
    public static void dispose(@Nullable Disposable target) {
        if (target == null) return;

        List<Disposable> order;
        synchronized (Disposer.class) {
            if (DISPOSED.containsKey(target)) return;
            // Read BEFORE the maps are cleared. Reading it after finds null, the unlink never happens,
            // and the parent's child list keeps a reference to something already released -- so
            // disposing the parent later frees it a second time.
            Disposable parent = PARENTS.get(target);
            order = collectDepthFirst(target);
            // Marked BEFORE anything runs, so a dispose() that re-enters -- a listener firing, a
            // child unregistering itself -- finds the work already claimed instead of doing it twice.
            for (Disposable each : order) {
                DISPOSED.put(each, Boolean.TRUE);
                CHILDREN.remove(each);
                PARENTS.remove(each);
            }
            if (parent != null) unlink(target, parent);
        }

        for (Disposable each : order) release(each);
    }

    /** Whether {@code target} has been released. Null is treated as disposed — there is nothing to free. */
    public static synchronized boolean isDisposed(@Nullable Disposable target) {
        return target == null || DISPOSED.containsKey(target);
    }

    /**
     * A disposable that owns nothing itself and exists only to be a parent.
     *
     * <p>IntelliJ's {@code Disposer.newDisposable}. What you register against when the natural owner is
     * a scope rather than an object — a frame, a drag, one restore pass.</p>
     */
    public static Disposable newDisposable(String debugName) {
        return new Disposable() {
            @Override
            public void dispose() {
            }

            @Override
            public String toString() {
                return "Disposable(" + debugName + ")";
            }
        };
    }

    /**
     * Releases {@link Disposable.Gl} instances that were disposed off the GL thread.
     *
     * <p>Called once a frame from {@code CgUiLifecycle.onFrame}. Drains a snapshot rather than looping
     * until empty, so a disposal that queues more work cannot spin the frame.</p>
     */
    public static void drainGlQueue() {
        List<Disposable> due;
        synchronized (Disposer.class) {
            if (GL_QUEUE.isEmpty()) return;
            due = new ArrayList<>(GL_QUEUE);
            GL_QUEUE.clear();
        }
        for (Disposable each : due) invoke(each);
    }

    /** How many disposables are currently registered. The leak assertion in tests reads this. */
    public static synchronized int liveCount() {
        return PARENTS.size() + (int) CHILDREN.keySet().stream().filter(k -> !PARENTS.containsKey(k)).count();
    }

    /** Forgets everything, releasing nothing. For tests that need a clean tree, never for production. */
    public static synchronized void resetForTesting() {
        CHILDREN.clear();
        PARENTS.clear();
        DISPOSED.clear();
        GL_QUEUE.clear();
        onGlThread = () -> true;
        deferToGlThread = Runnable::run;
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code target} and its descendants, deepest-and-last-registered first.
     *
     * <p>Explicitly stacked rather than recursive — see the class note on depth. The reversal is what
     * produces "children before parent, and later siblings before earlier ones" in one pass.</p>
     */
    private static List<Disposable> collectDepthFirst(Disposable target) {
        List<Disposable> visited = new ArrayList<>();
        Deque<Disposable> stack = new ArrayDeque<>();
        stack.push(target);
        while (!stack.isEmpty()) {
            Disposable current = stack.pop();
            // Already gone: it was disposed directly earlier and has not been unlinked from a stale
            // list. Skipping it here rather than trusting the unlink keeps a double free impossible
            // even if some path forgets.
            if (DISPOSED.containsKey(current)) continue;
            visited.add(current);
            List<Disposable> kids = CHILDREN.get(current);
            if (kids == null) continue;
            // Pushed in REVERSE so they pop in registration order, which -- after the reversal below --
            // yields later-registered siblings first. Pushing forwards double-inverts and silently
            // produces registration order, which frees a dependency out from under its dependent.
            for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
        }
        // visited is parents-before-children; the release order is the exact opposite.
        List<Disposable> order = new ArrayList<>(visited);
        java.util.Collections.reverse(order);
        return order;
    }

    private static void unlink(Disposable child, Disposable parent) {
        List<Disposable> siblings = CHILDREN.get(parent);
        if (siblings == null) return;
        siblings.removeIf(each -> each == child);
        if (siblings.isEmpty()) CHILDREN.remove(parent);
    }

    /** Routes to the GL thread when needed, then invokes. */
    private static void release(Disposable target) {
        boolean needsGl;
        boolean here;
        synchronized (Disposer.class) {
            needsGl = target instanceof Disposable.Gl;
            here = onGlThread.getAsBoolean();
            if (needsGl && !here) {
                GL_QUEUE.add(target);
            }
        }
        if (needsGl && !here) {
            deferToGlThread.accept(Disposer::drainGlQueue);
            return;
        }
        invoke(target);
    }

    private static void invoke(Disposable target) {
        try {
            target.dispose();
        } catch (RuntimeException failed) {
            // Logged, never rethrown: teardown is exactly when stopping halfway is worst, and the
            // caller of dispose() is usually a close handler with nowhere to put an exception.
            CrystalGuiCore.LOGGER.warn("dispose() failed for {}", target, failed);
        }
    }
}
