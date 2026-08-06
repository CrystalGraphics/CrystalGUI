package com.crystalgui.core.dispose;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ownership tree's contract.
 *
 * <p>In {@code headlessTest} deliberately. The whole point of the GL gate is that its default runs
 * everything immediately, so this class must be reachable where there is no context and no GL thread —
 * and if someone later reaches for a CrystalGraphics type in here, the absence is the assertion that
 * catches it.</p>
 */
public class DisposerTest {

    /** Records the order things were released in, which is most of what there is to assert. */
    private final List<String> released = new ArrayList<>();

    private Disposable named(String name) {
        return new Disposable() {
            @Override
            public void dispose() {
                released.add(name);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    private Disposable glNamed(String name) {
        return new Disposable.Gl() {
            @Override
            public void dispose() {
                released.add(name);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    @Before
    @After
    public void reset() {
        Disposer.resetForTesting();
        released.clear();
    }

    /**
     * <b>Children before the parent, and later siblings before earlier ones.</b>
     *
     * <p>The reverse order is the load-bearing half: a child registered later may have been built from
     * an earlier one, so releasing forwards frees a dependency out from under its dependent. Asserting
     * the exact sequence rather than mere membership is the only way to pin it.</p>
     */
    @Test
    public void childrenAreReleasedBeforeTheParentAndInReverseOrder() {
        Disposable parent = named("parent");
        Disposable first = named("first");
        Disposable second = named("second");
        Disposer.register(parent, first);
        Disposer.register(parent, second);

        Disposer.dispose(parent);
        assertEquals(List.of("second", "first", "parent"), released);
    }

    /** Depth as well as order: a grandchild goes before its parent, which goes before the root. */
    @Test
    public void aWholeSubtreeIsReleasedDeepestFirst() {
        Disposable root = named("root");
        Disposable mid = named("mid");
        Disposable leaf = named("leaf");
        Disposer.register(root, mid);
        Disposer.register(mid, leaf);

        Disposer.dispose(root);
        assertEquals(List.of("leaf", "mid", "root"), released);
    }

    /** A second dispose must free nothing again — the difference between idempotent and a double free. */
    @Test
    public void disposingTwiceIsANoOp() {
        Disposable target = named("target");
        Disposer.dispose(target);
        Disposer.dispose(target);
        assertEquals(List.of("target"), released);
        assertTrue(Disposer.isDisposed(target));
    }

    /**
     * <b>A throwing child must not take its siblings or its parent with it.</b>
     *
     * <p>Teardown is exactly when stopping halfway is worst: the caller is usually a close handler with
     * nowhere to put an exception, and what is left behind is a half-freed graph.</p>
     */
    @Test
    public void aThrowingChildDoesNotStopTheRest() {
        Disposable parent = named("parent");
        Disposable good = named("good");
        Disposable bad = new Disposable() {
            @Override
            public void dispose() {
                released.add("bad");
                throw new IllegalStateException("deliberate");
            }
        };
        Disposer.register(parent, good);
        Disposer.register(parent, bad);

        Disposer.dispose(parent);
        assertEquals(List.of("bad", "good", "parent"), released);
    }

    /** Registering against something already gone releases the child rather than retaining it. */
    @Test
    public void registeringAgainstADisposedParentDisposesTheChild() {
        Disposable parent = named("parent");
        Disposer.dispose(parent);

        Disposable child = named("child");
        assertFalse("a disposed parent still accepted a child", Disposer.tryRegister(parent, child));
        assertFalse("tryRegister must not release it either", released.contains("child"));

        Disposer.register(parent, child);
        assertTrue("register against a dead parent must not retain the child",
                released.contains("child"));
    }

    /** Disposing a child unlinks it, so the parent's later disposal does not reach it twice. */
    @Test
    public void disposingAChildUnlinksItFromItsParent() {
        Disposable parent = named("parent");
        Disposable child = named("child");
        Disposer.register(parent, child);

        Disposer.dispose(child);
        Disposer.dispose(parent);
        assertEquals(List.of("child", "parent"), released);
    }

    /** Re-parenting moves the child rather than leaving it owned twice. */
    @Test
    public void registeringUnderASecondParentMovesTheChild() {
        Disposable first = named("first");
        Disposable second = named("second");
        Disposable child = named("child");
        Disposer.register(first, child);
        Disposer.register(second, child);

        Disposer.dispose(first);
        assertEquals("the child went with its old parent", List.of("first"), released);

        Disposer.dispose(second);
        assertEquals(List.of("first", "child", "second"), released);
    }

    /**
     * <b>A GL disposable released off the GL thread is queued, not run.</b>
     *
     * <p>Freeing a GL object off-thread is silent corruption rather than an exception, so this is the
     * one place the tree deliberately does not do what it was asked at the moment it was asked.</p>
     */
    @Test
    public void aGlDisposableIsDeferredWhenOffThread() {
        AtomicBoolean onGl = new AtomicBoolean(false);
        List<Runnable> deferred = new ArrayList<>();
        Disposer.setGlGate(onGl::get, deferred::add);

        Disposable target = glNamed("gl");
        Disposer.dispose(target);
        assertTrue("a GL disposable was freed off the GL thread", released.isEmpty());
        assertEquals("nothing was handed to the GL thread", 1, deferred.size());

        onGl.set(true);
        deferred.get(0).run();
        assertEquals(List.of("gl"), released);
    }

    /** On the GL thread there is nothing to defer, and the queue stays out of the way. */
    @Test
    public void aGlDisposableRunsImmediatelyOnTheGlThread() {
        Disposer.setGlGate(() -> true, Runnable::run);
        Disposer.dispose(glNamed("gl"));
        assertEquals(List.of("gl"), released);
    }

    /** The default gate is what lets every headless test use this without a context. */
    @Test
    public void theDefaultGateRunsImmediately() {
        Disposer.dispose(glNamed("gl"));
        assertEquals(List.of("gl"), released);
    }

    /**
     * <b>The leak assertion.</b> A tree that has been disposed leaves nothing behind, which is what
     * makes {@code liveCount()} usable as a regression net elsewhere.
     */
    @Test
    public void aDisposedTreeLeavesNothingRegistered() {
        int before = Disposer.liveCount();
        Disposable root = named("root");
        for (int i = 0; i < 20; i++) Disposer.register(root, named("child" + i));
        assertTrue("registration did not grow the tree", Disposer.liveCount() > before);

        Disposer.dispose(root);
        assertEquals("a disposed tree still holds nodes", before, Disposer.liveCount());
    }

    /** Repeated open/close of an owned subtree must not accumulate — the session-growth assertion. */
    @Test
    public void repeatedRegisterAndDisposeDoesNotAccumulate() {
        int before = Disposer.liveCount();
        for (int cycle = 0; cycle < 50; cycle++) {
            Disposable owner = named("owner" + cycle);
            Disposer.register(owner, named("a" + cycle));
            Disposer.register(owner, named("b" + cycle));
            Disposer.dispose(owner);
        }
        assertEquals("fifty open/close cycles grew the tree", before, Disposer.liveCount());
        assertEquals(150, released.size());
    }

    /**
     * Deep trees must not overflow the stack. Teardown is unrecoverable if it does — half the graph is
     * freed and the rest is unreachable.
     */
    @Test
    public void aDeepTreeDisposesWithoutRecursion() {
        Disposable root = named("root");
        Disposable current = root;
        for (int i = 0; i < 5000; i++) {
            Disposable next = named("n" + i);
            Disposer.register(current, next);
            current = next;
        }
        Disposer.dispose(root);
        assertEquals(5001, released.size());
        assertEquals("the deepest node must go first", "n4999", released.get(0));
        assertEquals("root", released.get(released.size() - 1));
    }

    /** A dispose() that re-enters must find the work already claimed rather than doing it twice. */
    @Test
    public void reentrantDisposeDoesNotDoubleRelease() {
        Disposable parent = named("parent");
        Disposable child = new Disposable() {
            @Override
            public void dispose() {
                released.add("child");
                Disposer.dispose(this);      // the re-entry
            }
        };
        Disposer.register(parent, child);

        Disposer.dispose(parent);
        assertEquals(List.of("child", "parent"), released);
    }
}
